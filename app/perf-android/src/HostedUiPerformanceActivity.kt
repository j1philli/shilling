package finance.shilling.perf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.sqldelight.db.*
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.*
import finance.shilling.shared.data.auth.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.sync.createSyncHttpClient
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.presentation.SpaceSelectionCallback
import finance.shilling.shared.session.*
import finance.shilling.shared.ui.SettingsView
import finance.shilling.shared.ui.ShillingTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.module
import java.io.File
import java.util.UUID

/** Production Settings UI against a disposable local hosted control-plane fixture. */
class HostedUiPerformanceActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var driver: SqlDriver? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = requireNotNull(intent.getStringExtra("token"))
        val url = intent.getStringExtra("server") ?: "http://127.0.0.1:18085"
        require(url.startsWith("http://127.0.0.1:"))
        scope.launch {
            withContext(Dispatchers.IO) {
                val schema = object : SqlSchema<QueryResult.Value<Unit>> {
                    override val version = ShillingDatabase.Schema.version
                    override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
                    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Value(Unit)
                }
                val sql = AndroidSqliteDriver(schema, applicationContext, "hosted-synthetic.db")
                driver = sql
                ensureLocalSchemaReady(sql)
                val preferences = SharedPreferencesSettings(getSharedPreferences("hosted-synthetic", MODE_PRIVATE))
                preferences.putString(SETTINGS_KEY_SERVER_URL, url)
                preferences.putBoolean("posthog_consent", false)
                preferences.putBoolean("posthog_development_consent", false)
                val auth = object : AuthService by NoOpAuthService("synthetic-device") {
                    override val authState = MutableStateFlow(AuthState(true, "synthetic-user", null, UserTier.ANONYMOUS, token, true, "synthetic-device"))
                    override suspend fun refreshTokenIfNeeded() = token
                }
                initKoin(module {
                    single { ShillingDatabase(sql) }
                    single<Settings> { preferences }
                    single<IdGenerator> { object : IdGenerator { override fun newId() = UUID.randomUUID().toString() } }
                    single<ReceiptFileStoreFactory> { ReceiptFileStoreFactory { space, _ ->
                        Store5ReceiptFileStore(SyntheticFiles(File(filesDir, "hosted-$space")), { _, _, _ -> }, Dispatchers.IO)
                    } }
                    single { createSyncHttpClient() }
                    single<SessionState> { object : SessionState {
                        override val phase = MutableStateFlow<SessionPhase>(SessionPhase.Ready(auth, FeatureGate(auth, false), false))
                    } }
                    single { HostedBootstrapState(MutableStateFlow(HostedBootstrapStatus(HostedBootstrapPhase.READY))) }
                    single { HostedBootstrapRetryCallback { } }
                    single { ResetOnboardingCallback { error("Synthetic fixture") } }
                    single { RestartHostedLoginCallback { error("Synthetic fixture") } }
                    single { ServerUrlCallback { error("Synthetic fixture") } }
                    single { HouseholdIdCallback { error("Synthetic fixture") } }
                    single { CloudRelayCallback { } }
                    single { SpaceSelectionCallback { error("Synthetic fixture") } }
                })
            }
            setContent { ShillingTheme { SettingsView(developerToolsEnabled = false) } }
        }
    }
    override fun onDestroy() { scope.cancel(); driver?.close(); super.onDestroy() }
}
