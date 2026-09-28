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
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fprintf
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.withContext

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
