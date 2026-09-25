package finance.shilling.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.DEFAULT_SELF_HOSTED_SERVER_URL
import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.SyncStoreFacade
import finance.shilling.shared.data.sync.FileTransferManager
import finance.shilling.shared.data.sync.IncomingChangeRouter
import finance.shilling.shared.data.sync.SignalingClient
import finance.shilling.shared.data.sync.SyncConfig
import finance.shilling.shared.data.sync.WebRtcConnectionManager
import finance.shilling.shared.data.sync.createSyncHttpClient
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.ui.AppBootstrapServices
import finance.shilling.shared.ui.AppBootstrapScaffoldConfig
import finance.shilling.shared.ui.PlatformSyncRuntime
import finance.shilling.shared.ui.ShillingAppBootstrap
import finance.shilling.shared.ui.ShillingTheme
import io.ktor.client.webrtc.JsWebRtc
import io.ktor.client.webrtc.WebRtc
import io.ktor.client.webrtc.WebRtcClient
import kotlinx.browser.document
import org.koin.compose.koinInject
import org.w3c.dom.Worker

private fun isTauriEnvironment(): Boolean = js("typeof window.__TAURI__ !== 'undefined'")

private fun isSelfHostedDistribution(): Boolean = js("window.SHILLING_SELF_HOSTED_ONLY === true")

private fun browserOrigin(): String = js("window.location.origin")

private fun installTauriDragHandler(): JsAny? = js("(function(){document.addEventListener('mousedown',function(e){if(e.clientY<52&&e.button===0){e.preventDefault();e.stopPropagation();window.__TAURI__.window.getCurrentWindow().startDragging();}},true);})()")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    if (isTauriEnvironment()) installTauriDragHandler()
    ComposeViewport(document.body!!) {
        val selfHostedOnly = remember { isSelfHostedDistribution() }
        var db by remember { mutableStateOf<ShillingDatabase?>(null) }

        LaunchedEffect(Unit) {
            val driver = WebWorkerDriver(Worker("sqldelight.worker.js"))
            (ShillingDatabase.Schema.create(driver) as QueryResult.AsyncValue).await()
            (driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version};", 0) as QueryResult.AsyncValue).await()
            db = ShillingDatabase(driver)
        }

        val currentDb = db
        if (currentDb == null) {
            ShillingTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading...", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            return@ComposeViewport
        }

        val settings = remember { Settings() }
        val idGenerator = remember { WasmIdGenerator() }
        val services = remember(currentDb) {
            AppBootstrapServices(
                db = currentDb,
                settings = settings,
                idGenerator = idGenerator,
                receiptFileStore = WasmReceiptFileStore(currentDb),
                httpClient = createSyncHttpClient(),
                deviceIdentity = DeviceIdentity(settings, idGenerator),
                notifier = ChangeNotifier()
            )
        }
        val tauriTopPadding = if (isTauriEnvironment()) 32.dp else 0.dp

        ShillingAppBootstrap(
            services = services,
            logTag = "Sync",
            syncRuntimeFactory = { syncConfig, authService, iceServers, bootstrapServices ->
                rememberWebSyncRuntime(syncConfig, authService, iceServersState = iceServers, services = bootstrapServices)
            },
            scaffoldConfig = AppBootstrapScaffoldConfig(
                onboardingTopPadding = tauriTopPadding,
                navRailTopPadding = tauriTopPadding,
                selfHostedOnly = selfHostedOnly,
                defaultSelfHostedServerUrl = if (selfHostedOnly) browserOrigin() else DEFAULT_SELF_HOSTED_SERVER_URL
            )
        )
    }
}

@Composable
private fun rememberWebSyncRuntime(
    syncConfig: SyncConfig,
    authService: finance.shilling.shared.data.auth.AuthService,
    iceServersState: State<List<WebRtc.IceServer>>,
    services: AppBootstrapServices
): PlatformSyncRuntime {
    val currentIceServers = rememberUpdatedState(iceServersState.value)
    val webRtcClient = remember {
        WebRtcClient(JsWebRtc) {
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
    val incomingChangeRouter = remember(syncStoreFacade, webRtcManager, syncConfig.deviceId) {
        IncomingChangeRouter(
            syncStoreFacade,
            services.notifier,
            webRtcManager,
            fileTransferManager,
            services.receiptFileStore,
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
