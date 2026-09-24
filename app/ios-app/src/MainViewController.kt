package finance.shilling.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import finance.shilling.shared.ui.ShillingScaffold
import finance.shilling.shared.ui.ShillingTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.DEFAULT_SERVER_URL
import finance.shilling.shared.data.DeploymentSelection
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.SETTINGS_KEY_HOSTED_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.ensureLocalSchemaReady
import finance.shilling.shared.data.HouseholdIdCallback
import finance.shilling.shared.data.ServerUrlCallback
import finance.shilling.shared.data.auth.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.ui.AppBootstrapServices
import finance.shilling.shared.ui.AppBootstrapScaffoldConfig
import finance.shilling.shared.ui.PlatformSyncRuntime
import finance.shilling.shared.ui.ShillingAppBootstrap
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.webrtc.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.module
import finance.shilling.shared.ui.ShelfDestination
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import co.touchlab.kermit.Logger
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import platform.Foundation.NSTemporaryDirectory
import platform.UIKit.UIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fprintf
import platform.posix.memcpy
import WebRTC.RTCDataBuffer
import WebRTC.RTCDataChannel
import WebRTC.RTCDataChannelDelegateProtocol
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withContext
import platform.darwin.NSObject

private val log = Logger.withTag("iOS")

/**
 * Log writer for iOS — writes to both a file and NSLog for debugging.
 * NSLog output is capturable via idevicesyslog on real devices.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosMirrorLogWriter : LogWriter() {
    private val logPath = "${NSTemporaryDirectory()}shilling-ios.log"

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val throwableSuffix = throwable?.let { t ->
            " (${t::class.simpleName ?: "Throwable"}: ${t.message ?: "?"})"
        } ?: ""
        val formatted = "[$tag] ${severity.name}: $message$throwableSuffix"
        // NSLog for idevicesyslog capture (no format specifiers — K/N bridging issues with %@)
        platform.Foundation.NSLog("Shilling: $formatted")
        // File for persistence
        val f = fopen(logPath, "a")
        if (f != null) {
            fprintf(f, "%s\n", formatted)
            fflush(f)
            fclose(f)
        }
    }
}

// Strong reference prevents K/N GC from collecting the writer
@OptIn(ExperimentalForeignApi::class)
private val iosLogWriter = IosMirrorLogWriter()

/**
 * Non-blocking delay for iOS — cooperative yield with cross-dispatcher throttling.
 *
 * kotlinx.coroutines delay() doesn't fire on Kotlin/Native in this Compose Multiplatform
 * iOS environment (DefaultExecutor doesn't run). This implementation uses
 * withContext(Dispatchers.Main) for natural throttling: each iteration context-switches
 * Default→Main→Default, which takes ~1-10ms of real wall time due to GCD dispatch overhead.
 */
@OptIn(ExperimentalForeignApi::class)
private val iosDelay: suspend (Long) -> Unit = { ms ->
    val endSec = platform.posix.time(null) + (ms + 999) / 1000
    while (platform.posix.time(null) < endSec) {
        try {
            withContext(Dispatchers.Main) { kotlinx.coroutines.yield() }
        } catch (_: Exception) {
            kotlinx.coroutines.yield()
        }
    }
}

/**
 * Strongly-held ObjC delegate for RTCDataChannel that routes received messages
 * into a Kotlin coroutine [Channel] for non-blocking polling.
 *
 * Root cause of the iOS receive bug:
 *   RTCDataChannel.delegate is declared @property(nonatomic, weak) in RTCDataChannel.h.
 *   ktor's setupEvents() creates an anonymous K/N object and assigns it to this weak ObjC
 *   property. K/N GC immediately collects the anonymous object (nothing holds a strong K/N
 *   reference). Result: didReceiveMessageWithBuffer never fires, iOS silently drops all
 *   incoming data channel messages.
 *
 * Fix: this named class is held strongly in [nativeReceivers]. The ObjC weak property
 * points to our instance, which K/N GC cannot collect while the strong reference lives.
 */
@OptIn(ExperimentalForeignApi::class)
private class NativeChannelReceiver : NSObject(), RTCDataChannelDelegateProtocol {
    val messages = Channel<String>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun dataChannelDidChangeState(dataChannel: RTCDataChannel) {
        // State changes are handled by ktor's native peer connection layer.
    }

    override fun dataChannel(dataChannel: RTCDataChannel, didReceiveMessageWithBuffer: RTCDataBuffer) {
        val data = didReceiveMessageWithBuffer.data
        val length = data.length.toInt()
        if (length == 0) return
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        messages.trySend(bytes.decodeToString())
    }
}

// Strong references to our native channel receivers — prevents K/N GC from collecting them.
// Keyed by WebRtcDataChannel identity (one entry per active channel).
private val nativeReceivers = mutableMapOf<io.ktor.client.webrtc.WebRtcDataChannel, NativeChannelReceiver>()

/**
 * Eagerly installs [NativeChannelReceiver] as soon as a channel is registered — before
 * [listenForMessages] starts polling. This eliminates the window where incoming messages
 * land on ktor's already-GC'd anonymous RTCDataChannel.delegate and are silently dropped.
 *
 * Called by [WebRtcConnectionManager.onChannelOpen] synchronously at:
 *   • offerer path: immediately after createDataChannel (channel is CONNECTING)
 *   • answerer path: at DataChannelEvent.Open, before attachListener
 */
@OptIn(ExperimentalForeignApi::class)
private val nativeChannelOpen: (io.ktor.client.webrtc.WebRtcDataChannel) -> Unit = { ch ->
    val nativeCh = ch.getNative()  // io.ktor.client.webrtc extension: RTCDataChannel on iOS
    val rcvr = NativeChannelReceiver()
    nativeCh.delegate = rcvr  // replace ktor's already-dead anonymous delegate
    nativeReceivers[ch] = rcvr
    log.i { "Pre-installed NativeChannelReceiver for channel '${ch.label}'" }
}

/**
 * Non-blocking receive for iOS Kotlin/Native.
 *
 * Channel.receive() doesn't resume reliably on K/N in this Compose Multiplatform
 * environment, so we poll with tryReceive(). To avoid saturating the thread pool
 * when multiple peer channels are active, each poll iteration context-switches
 * Default→Main→Default via withContext(Dispatchers.Main), adding ~1-10ms of natural
 * GCD dispatch overhead per iteration. This keeps the Default thread pool available
 * for signaling, ICE callbacks, and other peer channel listeners.
 */
@OptIn(ExperimentalForeignApi::class)
private val iosReceiveText: suspend (io.ktor.client.webrtc.WebRtcDataChannel) -> String = { ch ->
    val receiver = nativeReceivers.getOrPut(ch) {
        // Safety net: should have been pre-installed by nativeChannelOpen, but install here
        // if somehow missed (e.g. future code path changes).
        val nativeCh = ch.getNative()
        val rcvr = NativeChannelReceiver()
        nativeCh.delegate = rcvr
        log.w { "Lazy-installed NativeChannelReceiver for channel '${ch.label}' (missed onChannelOpen?)" }
        rcvr
    }
    var text: String? = null
    while (text == null) {
        text = receiver.messages.tryReceive().getOrNull()
        if (text == null) {
            // Context-switch to Main and back for natural throttling (~1-10ms per iteration).
            // This prevents tight yield() loops from starving the Default thread pool when
            // multiple peer channels are active.
            try {
                withContext(Dispatchers.Main) { kotlinx.coroutines.yield() }
            } catch (_: Exception) {
                kotlinx.coroutines.yield()
            }
        }
    }
    text
}

@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController {
    // File-based logging only — OSLogWriter blocks the calling thread after
    // the initial burst due to os_log rate limiting, which freezes all log
    // calls across every thread (coroutines, GCD, etc.).
    Logger.setLogWriters(iosLogWriter)

    val driver = provideNativeDriver()
    runBlocking {
        ensureLocalSchemaReady(driver, logTag = "iOS")
    }
    val settings = Settings()
    val idGenerator = IosIdGenerator()
    val services = AppBootstrapServices(
        db = ShillingDatabase(driver),
        settings = settings,
        idGenerator = idGenerator,
        receiptFileStore = IosReceiptFileStore(),
        httpClient = createIosHttpClient(),
        deviceIdentity = DeviceIdentity(settings, idGenerator),
        notifier = ChangeNotifier()
    )

    return ComposeUIViewController {
        val deepLinkAction by DeepLinkState.pendingAction.collectAsState()
        var cameraLaunchToken by remember { mutableStateOf(0L) }
        var pendingReceiptFile by remember { mutableStateOf<PlatformFile?>(null) }
        val externalNavRequest = remember { MutableStateFlow<ShelfDestination?>(null) }

        LaunchedEffect(deepLinkAction) {
            if (deepLinkAction == DeepLinkAction.RECEIPT_CAMERA) {
                cameraLaunchToken += 1L
                DeepLinkState.consume()
            }
        }

        ShillingAppBootstrap(
            services = services,
            logTag = "iOS",
            syncRuntimeFactory = { syncConfig, authService, iceServers, bootstrapServices ->
                rememberIosSyncRuntime(syncConfig, authService, iceServersState = iceServers, services = bootstrapServices)
            },
            scaffoldConfig = AppBootstrapScaffoldConfig(
                cameraButton = { onFile -> MobileCameraReceiptButton(onFile) },
                photoButton = { onFile -> MobilePhotoLibraryReceiptButton(onFile) },
                externalNavRequest = externalNavRequest,
                pendingReceiptFile = pendingReceiptFile,
                onPendingReceiptConsumed = { pendingReceiptFile = null },
                preScaffoldContent = {
                    MobileAutoLaunchReceiptCamera(
                        launchToken = cameraLaunchToken.takeIf { it > 0L },
                        onFile = { file ->
                            cameraLaunchToken = 0L
                            if (file != null) {
                                pendingReceiptFile = file
                                externalNavRequest.value = ShelfDestination.RECEIPTS
                            }
                        }
                    )
                }
            )
        )
    }
}

private fun createIosHttpClient(): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(WebSockets) {
        pingIntervalMillis = 10_000
    }
    install(HttpTimeout) {
        connectTimeoutMillis = BOOTSTRAP_NETWORK_TIMEOUT_MS
        requestTimeoutMillis = BOOTSTRAP_NETWORK_TIMEOUT_MS
        socketTimeoutMillis = BOOTSTRAP_NETWORK_TIMEOUT_MS
    }
}

@Composable
private fun rememberIosSyncRuntime(
    syncConfig: SyncConfig,
    authService: AuthService,
    iceServersState: androidx.compose.runtime.State<List<WebRtc.IceServer>>,
    services: AppBootstrapServices
): PlatformSyncRuntime {
    val currentIceServers = androidx.compose.runtime.rememberUpdatedState(iceServersState.value)
    val webRtcClient = remember {
        WebRtcClient(IosWebRtc) {
            defaultConnectionConfig = { this.iceServers = currentIceServers.value }
        }
    }
    val signalingClient = remember(services.httpClient, syncConfig.serverUrl, syncConfig.deviceId, syncConfig.householdId, authService) {
        SignalingClient(services.httpClient, syncConfig.serverUrl, syncConfig.deviceId, syncConfig.householdId, authService)
    }
    val webRtcManager = remember(signalingClient, syncConfig.deviceId) {
        WebRtcConnectionManager(
            webRtcClient,
            signalingClient,
            syncConfig.deviceId,
            iosReceiveText,
            onChannelOpen = nativeChannelOpen,
            delayFn = iosDelay
        )
    }
    val fileTransferManager = remember(services.receiptFileStore) {
        FileTransferManager(services.receiptFileStore)
    }
    val syncStoreFacade = koinInject<SyncStoreFacade>()
    val incomingChangeRouter = remember(syncStoreFacade, webRtcManager, services.receiptFileStore, syncConfig.deviceId) {
        IncomingChangeRouter(
            syncStoreFacade,
            services.notifier,
            webRtcManager,
            fileTransferManager,
            services.receiptFileStore,
            delayFn = iosDelay,
            deviceId = syncConfig.deviceId,
            idGenerator = services.idGenerator
        )
    }
    return remember(signalingClient, webRtcManager, incomingChangeRouter, fileTransferManager) {
        PlatformSyncRuntime(
            signalingClient = signalingClient,
            peerSyncManager = webRtcManager,
            incomingChangeRouter = incomingChangeRouter,
            fileTransferManager = fileTransferManager
        )
    }
}
