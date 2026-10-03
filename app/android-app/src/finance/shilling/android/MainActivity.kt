package finance.shilling.android

import android.content.pm.ApplicationInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.ensureLocalSchemaReady
import finance.shilling.shared.data.initKoin
import finance.shilling.shared.data.sync.BOOTSTRAP_NETWORK_TIMEOUT_MS
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.ui.AppBootstrapScaffoldConfig
import finance.shilling.shared.ui.ShillingAppBootstrap
import finance.shilling.shared.ui.ShillingTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.webrtc.AndroidWebRtc
import io.ktor.client.webrtc.WebRtcClient
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.dsl.module
import finance.shilling.shared.data.sync.WebRtcPlatform
import finance.shilling.shared.session.AppSession
import finance.shilling.shared.session.AppSessionConfig
import finance.shilling.shared.session.SessionPhase
import finance.shilling.shared.session.sessionModule

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
    private val accountRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var appSession: AppSession

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
        val appContext = applicationContext
        // Returns the running graph if this Activity is recreated in the same process.
        initKoin(module {
            single {
                finance.shilling.shared.data.analytics.ProductAnalyticsEnvironment(
                    developmentBuild = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                )
            }
            single { AppSessionConfig(logTag = "Android", authRedirectUrl = "shilling.finance://auth-callback") }
            single { ShillingDatabase(driver) }
            single { settings }
            single<IdGenerator> { AndroidIdGenerator() }
            single<finance.shilling.shared.data.store.ReceiptFileStoreFactory> {
                finance.shilling.shared.data.store.ReceiptFileStoreFactory { id, legacy -> AndroidReceiptFileStore(appContext, if (legacy) null else id) }
            }
            single { createAndroidHttpClient() }
            single {
                WebRtcPlatform(createClient = { currentIceServers ->
                    WebRtcClient(AndroidWebRtc) {
                        context = appContext
                        defaultConnectionConfig = { iceServers = currentIceServers() }
                    }
                })
            }
        }, sessionModule).get<AppSession>().also { appSession = it }.start()

        intent?.data?.let(::handleAuthCallback)

        setContent {
            ShillingAppBootstrap(
                scaffoldConfig = AppBootstrapScaffoldConfig(
                    cameraButton = { onFile -> MobileCameraReceiptButton(onFile) },
                    photoButton = { onFile -> MobilePhotoLibraryReceiptButton(onFile) },
                    developerToolsEnabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                    startupPendingContent = {
                        ShillingTheme {
                            Surface(modifier = Modifier.fillMaxSize()) {}
                        }
                    }
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccountStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data?.scheme == "shilling.finance" && intent.data?.host == "auth-callback") {
            intent.data?.let(::handleAuthCallback)
        }
    }

    override fun onDestroy() {
        accountRefreshScope.cancel()
        super.onDestroy()
    }

    private fun refreshAccountStatus() {
        if (!::appSession.isInitialized) return
        accountRefreshScope.launch {
            val ready = withTimeoutOrNull(10_000) {
                appSession.phase.filterIsInstance<SessionPhase.Ready>().first()
            } ?: return@launch
            if (!ready.selfHosted && ready.authService.authState.value.isAuthenticated) {
                ready.authService.refreshAccountStatus().onFailure { error ->
                    log.w { "Could not refresh account status: ${error.message}" }
                }
            }
        }
    }

    private fun handleAuthCallback(url: android.net.Uri) {
        if (url.scheme != "shilling.finance" || url.host != "auth-callback") return
        accountRefreshScope.launch {
            appSession.handleAuthCallback(url.toString()).onFailure { error ->
                log.w { "Could not open auth callback: ${error.message}" }
            }
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
