package finance.shilling.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ensureLocalSchemaReady
import finance.shilling.shared.data.initKoin
import finance.shilling.shared.data.sync.BOOTSTRAP_NETWORK_TIMEOUT_MS
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.ui.AppBootstrapScaffoldConfig
import finance.shilling.shared.ui.ShelfDestination
import finance.shilling.shared.ui.ShillingAppBootstrap
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.webrtc.IosWebRtc
import io.ktor.client.webrtc.WebRtcClient
import io.ktor.serialization.kotlinx.json.json
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSTemporaryDirectory
import platform.UIKit.UIViewController
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fprintf
import finance.shilling.shared.data.sync.WebRtcPlatform
import finance.shilling.shared.session.AppSession
import finance.shilling.shared.session.AppSessionConfig
import finance.shilling.shared.session.sessionModule

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
 * Starts logging and the app's Koin graph. Called from `App.init` in Swift so native code can
 * resolve dependencies before any UI exists; safe to call again (returns early).
 */
@OptIn(ExperimentalForeignApi::class)
fun startIosKoin() {
    if (KoinPlatform.getKoinOrNull() != null) return
    // File-based logging only — OSLogWriter blocks the calling thread after
    // the initial burst due to os_log rate limiting, which freezes all log
    // calls across every thread (coroutines, GCD, etc.).
    Logger.setLogWriters(iosLogWriter)

    val driver = provideNativeDriver()
    runBlocking {
        ensureLocalSchemaReady(driver, logTag = "iOS")
    }
    initKoin(module {
        single { AppSessionConfig(logTag = "iOS") }
        single { ShillingDatabase(driver) }
        single { Settings() }
        single<IdGenerator> { IosIdGenerator() }
        single<ReceiptFileStore> { IosReceiptFileStore() }
        single { createIosHttpClient() }
        single {
            WebRtcPlatform(
                createClient = { currentIceServers ->
                    WebRtcClient(IosWebRtc) {
                        defaultConnectionConfig = { iceServers = currentIceServers() }
                    }
                },
                delayFn = iosDelay
            )
        }
    }, sessionModule).get<AppSession>().start()
}

fun MainViewController(): UIViewController {
    startIosKoin()
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
            scaffoldConfig = AppBootstrapScaffoldConfig(
                cameraButton = { onFile -> MobileCameraReceiptButton(onFile) },
                photoButton = { onFile -> MobilePhotoLibraryReceiptButton(onFile) },
                developerToolsEnabled = isDebugBuild(),
                externalNavRequest = externalNavRequest,
                pendingReceiptFile = pendingReceiptFile,
                onPendingReceiptConsumed = { pendingReceiptFile = null },
                platformTabBar = NativeTabBridge.tabBar,
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

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal fun isDebugBuild(): Boolean = kotlin.native.Platform.isDebugBinary
