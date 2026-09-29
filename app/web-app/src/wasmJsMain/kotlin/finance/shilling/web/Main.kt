package finance.shilling.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.initKoin
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.sync.createSyncHttpClient
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.ui.AppBootstrapScaffoldConfig
import finance.shilling.shared.ui.ShillingAppBootstrap
import finance.shilling.shared.ui.ShillingTheme
import io.ktor.client.webrtc.JsWebRtc
import io.ktor.client.webrtc.WebRtcClient
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.koin.dsl.module
import org.w3c.dom.Worker
import finance.shilling.shared.data.sync.WebRtcPlatform
import finance.shilling.shared.session.AppSession
import finance.shilling.shared.session.AppSessionConfig
import finance.shilling.shared.session.sessionModule

private fun isTauriEnvironment(): Boolean = js("typeof window.__TAURI__ !== 'undefined'")

private fun isSelfHostedDistribution(): Boolean = js("window.SHILLING_SELF_HOSTED_ONLY === true")

private fun browserOrigin(): String = js("window.location.origin")

private fun installTauriDragHandler(): JsAny? = js("(function(){document.addEventListener('mousedown',function(e){if(e.clientY<52&&e.button===0){e.preventDefault();e.stopPropagation();window.__TAURI__.window.getCurrentWindow().startDragging();}},true);})()")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    if (isTauriEnvironment()) installTauriDragHandler()
    // The web database opens asynchronously, so Koin starts once it's ready; UI waits on this.
    var koinReady by mutableStateOf(false)
    MainScope().launch {
        val driver = WebWorkerDriver(Worker("sqldelight.worker.js"))
        (ShillingDatabase.Schema.create(driver) as QueryResult.AsyncValue).await()
        (driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version};", 0) as QueryResult.AsyncValue).await()
        initKoin(webPlatformModule(ShillingDatabase(driver)), sessionModule).get<AppSession>().start()
        koinReady = true
    }
    ComposeViewport(document.body!!) {
        if (!koinReady) {
            ShillingTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading...", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            return@ComposeViewport
        }

        val tauriTopPadding = if (isTauriEnvironment()) 32.dp else 0.dp

        ShillingAppBootstrap(
            scaffoldConfig = AppBootstrapScaffoldConfig(
                onboardingTopPadding = tauriTopPadding,
                navRailTopPadding = tauriTopPadding,
                navControllerHook = { navController -> BrowserHistoryBinding(navController) },
                developerToolsEnabled = isDevToolsBuild()
            )
        )
    }
}

private fun webPlatformModule(db: ShillingDatabase) = module {
    single { db }
    single {
        val selfHostedOnly = isSelfHostedDistribution()
        AppSessionConfig(
            selfHostedOnly = selfHostedOnly,
            defaultSelfHostedServerUrl = if (selfHostedOnly) browserOrigin() else DEFAULT_SELF_HOSTED_SERVER_URL,
            logTag = "Sync"
        )
    }
    single { Settings() }
    single<IdGenerator> { WasmIdGenerator() }
    single<ReceiptFileStore> { WasmReceiptFileStore(get()) }
    single { createSyncHttpClient() }
    single {
        WebRtcPlatform(createClient = { currentIceServers ->
            WebRtcClient(JsWebRtc) {
                defaultConnectionConfig = { iceServers = currentIceServers() }
            }
        })
    }
}

/** Set at build time by build-web.sh (SHILLING_DEV_TOOLS) via a meta tag in index.html. */
private fun isDevToolsBuild(): Boolean =
    kotlinx.browser.document.querySelector("meta[name=shilling-dev-tools]")
        ?.getAttribute("content") == "true"
