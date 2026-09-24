package finance.shilling.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.data.ensureLocalSchemaReady
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.SyncStoreFacade
import finance.shilling.shared.data.sync.BOOTSTRAP_NETWORK_TIMEOUT_MS
import finance.shilling.shared.data.sync.FileTransferManager
import finance.shilling.shared.data.sync.IncomingChangeRouter
import finance.shilling.shared.data.sync.SignalingClient
import finance.shilling.shared.data.sync.SyncConfig
import finance.shilling.shared.data.sync.WebRtcConnectionManager
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.ui.AppBootstrapScaffoldConfig
import finance.shilling.shared.ui.AppBootstrapServices
import finance.shilling.shared.ui.PlatformSyncRuntime
import finance.shilling.shared.ui.ShillingAppBootstrap
import finance.shilling.shared.ui.ShillingTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.webrtc.AndroidWebRtc
import io.ktor.client.webrtc.WebRtc
import io.ktor.client.webrtc.WebRtcClient
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.koin.compose.koinInject

private val log = Logger.withTag("Android")

private fun isRunningOnEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val product = Build.PRODUCT.lowercase()
    return fingerprint.contains("generic") ||
        fingerprint.contains("emulator") ||
        model.contains("emulator") ||
        model.contains("android sdk built for") ||
        product.contains("sdk") ||
        product.contains("emulator") ||
        Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
        Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
        Build.HARDWARE.contains("ranchu", ignoreCase = true)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val driver = provideAndroidDriver(applicationContext)
        runBlocking {
            ensureLocalSchemaReady(driver, logTag = "Android")
        }

        val settings = Settings()
        // Emulators cannot reach the host via 127.0.0.1/localhost — use the
        // special host-loopback alias so self-hosted signaling works out of the box.
        val existingServerUrl = settings.getStringOrNull(SETTINGS_KEY_SERVER_URL)
        if (isRunningOnEmulator() && (
                existingServerUrl.isNullOrBlank() ||
                    existingServerUrl.contains("localhost") ||
                    existingServerUrl.contains("127.0.0.1")
                )
        ) {
            settings.putString(SETTINGS_KEY_SERVER_URL, "http://10.0.2.2:8081")
            log.i { "Emulator detected — defaulting server URL to http://10.0.2.2:8081" }
        }
        val idGenerator = AndroidIdGenerator()
        val services = AppBootstrapServices(
            db = ShillingDatabase(driver),
            settings = settings,
            idGenerator = idGenerator,
            receiptFileStore = AndroidReceiptFileStore(applicationContext),
            httpClient = createAndroidHttpClient(),
            deviceIdentity = DeviceIdentity(settings, idGenerator),
            notifier = ChangeNotifier()
        )

        setContent {
            ShillingAppBootstrap(
                services = services,
                logTag = "Android",
                syncRuntimeFactory = { syncConfig, authService, iceServers, bootstrapServices ->
                    rememberAndroidSyncRuntime(
                        syncConfig = syncConfig,
                        authService = authService,
                        iceServersState = iceServers,
                        services = bootstrapServices
                    )
                },
                scaffoldConfig = AppBootstrapScaffoldConfig(
                    cameraButton = { onFile -> MobileCameraReceiptButton(onFile) },
                    photoButton = { onFile -> MobilePhotoLibraryReceiptButton(onFile) },
                    startupPendingContent = {
                        ShillingTheme {
                            Surface(modifier = Modifier.fillMaxSize()) {}
                        }
                    }
                )
            )
        }
    }

    @Composable
    private fun rememberAndroidSyncRuntime(
        syncConfig: SyncConfig,
        authService: AuthService,
        iceServersState: State<List<WebRtc.IceServer>>,
        services: AppBootstrapServices
    ): PlatformSyncRuntime {
        val currentIceServers = rememberUpdatedState(iceServersState.value)
        val webRtcClient = remember {
            WebRtcClient(AndroidWebRtc) {
                context = applicationContext
                defaultConnectionConfig = { this.iceServers = currentIceServers.value }
            }
        }
        val signalingClient = remember(services.httpClient, syncConfig.serverUrl, syncConfig.deviceId, syncConfig.householdId, authService) {
            SignalingClient(services.httpClient, syncConfig.serverUrl, syncConfig.deviceId, syncConfig.householdId, authService)
        }
        val webRtcManager = remember(signalingClient, syncConfig.deviceId) {
            WebRtcConnectionManager(webRtcClient, signalingClient, syncConfig.deviceId)
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
                deviceId = services.deviceIdentity.deviceId,
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
}

private fun createAndroidHttpClient(): HttpClient = HttpClient(OkHttp) {
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
