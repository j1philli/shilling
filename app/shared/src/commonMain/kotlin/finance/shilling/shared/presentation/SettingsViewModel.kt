package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.DEFAULT_SERVER_URL
import finance.shilling.shared.data.HouseholdIdCallback
import finance.shilling.shared.data.ResetOnboardingCallback
import finance.shilling.shared.data.RestartHostedLoginCallback
import finance.shilling.shared.data.SETTINGS_KEY_HOSTED_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_LOCAL_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.ServerUrlCallback
import finance.shilling.shared.data.analytics.ProductAnalytics
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.AuthState
import finance.shilling.shared.data.auth.DependencyReachability
import finance.shilling.shared.data.auth.HostedBootstrapPhase
import finance.shilling.shared.data.auth.HostedBootstrapRetryCallback
import finance.shilling.shared.data.auth.HostedBootstrapState
import finance.shilling.shared.data.auth.HostedBootstrapStatus
import finance.shilling.shared.data.auth.UserTier
import finance.shilling.shared.data.auth.resolveHostedAuthUiState
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.session.HostedCredentialsMode
import finance.shilling.shared.session.HostedCredentialsSubmitResult
import finance.shilling.shared.session.SessionPhase
import finance.shilling.shared.session.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val DEVELOPER_UNLOCK_TAPS = 7

/** A confirmation dialog's copy. */
data class ConfirmCopy(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val destructive: Boolean
)

/** The Account section: which variant, plus its copy. */
sealed interface SettingsAccount {
    /** The action button (Start over / Sign out / Sign out and reset) and its confirmation. */
    val actionLabel: String
    val confirm: ConfirmCopy

    data object SelfHosted : SettingsAccount {
        const val TITLE = "Self-hosted"
        const val BODY = "This device syncs through a server you operate. No Shilling account is used."
        override val actionLabel = "Sign out and reset"
        override val confirm = ConfirmCopy(
            title = "Sign out and reset?",
            message = "This deletes all budget data on this device and returns to setup. " +
                "Data already synced to your other devices is not affected.",
            confirmLabel = "Reset device",
            destructive = true
        )
    }

    /** Anonymous: offer to create an account (or sign in). */
    data class Guest(
        val authAvailable: Boolean,
        val disabledReason: String?,
        val pendingConfirmation: String?
    ) : SettingsAccount {
        val title get() = "Guest"
        val body get() = "Continue with your email to keep this budget or use an existing account."
        override val actionLabel get() = "Start over"
        override val confirm get() = ConfirmCopy(
            title = "Start over?",
            message = "Guest data on this device will be deleted. Create an account first if you want to keep it.",
            confirmLabel = "Delete and start over",
            destructive = true
        )
    }

    data class SignedIn(
        val title: String,
        val planLabel: String,
        val pendingConfirmation: String?,
        val needsPasswordSetup: Boolean
    ) : SettingsAccount {
        override val actionLabel get() = "Sign out"
        override val confirm get() = ConfirmCopy(
            title = "Sign out?",
            message = "You'll return to the welcome screen. Your budget stays in your account.",
            confirmLabel = "Sign out",
            destructive = false
        )
    }
}

/** User-facing sync status (hosted mode). */
data class SyncStatusUi(
    val title: String,
    val detail: String,
    val showRetry: Boolean,
    val retryEnabled: Boolean
)

/** Developer tools: identifiers, connection settings and raw bootstrap status. */
data class DeveloperInfo(
    val selfHosted: Boolean,
    val connectionSummary: String,
    val serverUrl: String,
    val householdId: String,
    /** "Phase: Ready", "Server: Reachable", … (hosted only). */
    val bootstrapLines: List<String>,
    val bootstrapError: String?,
    val bootstrapChecking: Boolean
)

data class SettingsUiState(
    val analyticsConfigured: Boolean = false,
    val analyticsConsent: Boolean = false,
    val prefs: DisplayPrefs = DisplayPrefs(),
    val account: SettingsAccount? = null,
    /** Null when self-hosted (no managed sync status). */
    val sync: SyncStatusUi? = null,
    val developer: DeveloperInfo? = null,
    /** Unlocked by tapping About seven times (UIs also show them in debug builds). */
    val developerToolsUnlocked: Boolean = false
) {
    val currencyExample: String get() = "Example: ${formatCurrency(1234.5)}"
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val sessionState: SessionState,
    hostedBootstrapState: HostedBootstrapState,
    private val settings: Settings,
    private val retryCallback: HostedBootstrapRetryCallback,
    private val resetCallback: ResetOnboardingCallback,
    private val restartLoginCallback: RestartHostedLoginCallback,
    private val serverUrlCallback: ServerUrlCallback,
    private val householdIdCallback: HouseholdIdCallback,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val windowUseCase: ComputeWindowUseCase,
    private val analytics: ProductAnalytics
) : ViewModel() {
    private val analyticsState = MutableStateFlow(analytics.consent to analytics.configured)
    private val aboutTaps = MutableStateFlow(0)
    private val ready = sessionState.phase.filterIsInstance<SessionPhase.Ready>()
    private var authService: AuthService? = null

    val currencyOptions: List<String> = DisplayPreferences.currencyOptions
    val themeModes: List<ThemeMode> = ThemeMode.entries

    val state: StateFlow<SettingsUiState> = combine(
        ready.flatMapLatest { phase -> phase.authService.authState.map { phase to it } },
        hostedBootstrapState.status,
        DisplayPreferences.state,
        aboutTaps,
        analyticsState
    ) { (phase, authState), status, prefs, taps, analyticsStatus ->
        authService = phase.authService
        SettingsUiState(
            analyticsConfigured = analyticsStatus.second,
            analyticsConsent = analyticsStatus.first,
            prefs = prefs,
            account = account(phase, authState, status),
            sync = if (phase.selfHosted) null else syncStatus(status),
            developer = developerInfo(phase.selfHosted, authState, status),
            developerToolsUnlocked = taps >= DEVELOPER_UNLOCK_TAPS
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(prefs = DisplayPreferences.state.value, analyticsConfigured = analytics.configured, analyticsConsent = analytics.consent))

    fun setAnalyticsConsent(value: Boolean) {
        analytics.consent = value
        analyticsState.value = value to analytics.configured
    }

    fun saveAnalyticsConfig(host: String, projectToken: String): Boolean {
        analytics.host = host
        analytics.projectToken = projectToken
        analyticsState.value = analytics.consent to analytics.configured
        return analytics.configured
    }

    val analyticsHost: String get() = analytics.host
    val analyticsProjectToken: String get() = analytics.projectToken

    fun setThemeMode(mode: ThemeMode) = DisplayPreferences.updateThemeMode(mode)

    fun setCurrencySymbol(symbol: String) = DisplayPreferences.updateCurrencySymbol(symbol)

    fun setWeekStart(day: kotlinx.datetime.DayOfWeek) = DisplayPreferences.updateWeekStart(day)

    /** Counts About taps; true when this tap unlocked developer tools. */
    fun aboutTapped(): Boolean {
        if (aboutTaps.value >= DEVELOPER_UNLOCK_TAPS) return false
        aboutTaps.value += 1
        return aboutTaps.value == DEVELOPER_UNLOCK_TAPS
    }

    /** Guest account form: create an account (upgrading the guest) or sign in. */
    suspend fun submitCredentials(
        mode: HostedCredentialsMode,
        email: String,
        password: String
    ): Result<HostedCredentialsSubmitResult> {
        val service = authService ?: return Result.failure(IllegalStateException("Not ready yet"))
        return when (mode) {
            HostedCredentialsMode.SIGN_IN -> service.signIn(email, password).map {
                HostedCredentialsSubmitResult(mode = mode)
            }
            HostedCredentialsMode.CREATE_ACCOUNT -> service.signUp(email, password).map { signUp ->
                HostedCredentialsSubmitResult(mode = mode, signUpResult = signUp)
            }
        }
    }

    suspend fun refreshAccountStatus(): Result<Unit> =
        ((sessionState.phase.value as? SessionPhase.Ready)?.authService ?: authService)
            ?.refreshAccountStatus() ?: Result.failure(IllegalStateException("Not ready yet"))

    suspend fun setPassword(password: String): Result<Unit> =
        authService?.setPassword(password) ?: Result.failure(IllegalStateException("Not ready yet"))

    suspend fun sendSignInLink(email: String): Result<Unit> =
        authService?.sendSignInLink(email.trim()) ?: Result.failure(IllegalStateException("Not ready yet"))

    /** The Account section's confirmed action: reset (self-hosted, guest) or sign out. */
    suspend fun confirmAccountAction() {
        when (state.value.account) {
            is SettingsAccount.SignedIn -> restartLoginCallback.onRestart()
            else -> resetCallback.onReset()
        }
    }

    fun retrySync() = retryCallback.onRetry()

    /** Returns the snackbar message. */
    fun saveServerUrl(url: String): String {
        val trimmed = url.trim()
        settings.putString(SETTINGS_KEY_SERVER_URL, trimmed)
        serverUrlCallback.onChange(trimmed)
        return "Reconnecting to $trimmed"
    }

    /** Self-hosted only. Returns the snackbar message. */
    fun saveHouseholdId(id: String): String {
        val newId = id.trim()
        settings.putString(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID, newId)
        householdIdCallback.onChange(newId)
        return "Household ID updated. Sync will reconnect."
    }

    /** Returns the snackbar message. */
    suspend fun addSampleData(): String {
        seedDemoData(accountRepository, categoryRepository, scheduleRepository, windowUseCase)
        return "Sample data added"
    }

    private fun account(
        phase: SessionPhase.Ready,
        authState: AuthState,
        status: HostedBootstrapStatus
    ): SettingsAccount {
        if (phase.selfHosted) return SettingsAccount.SelfHosted
        val pending = if (authState.pendingEmailConfirmation) {
            "Check ${authState.pendingEmail ?: authState.email ?: "your inbox"} to confirm your email address."
        } else {
            null
        }
        return if (authState.isAnonymous) {
            val authUi = resolveHostedAuthUiState(phase.authService, status)
            SettingsAccount.Guest(authUi.authAvailable, authUi.disabledReason, pending)
        } else {
            SettingsAccount.SignedIn(
                title = authState.email ?: "Signed in",
                planLabel = "${tierLabel(authState.tier)} plan",
                pendingConfirmation = pending,
                needsPasswordSetup = authState.needsPasswordSetup
            )
        }
    }

    private fun syncStatus(status: HostedBootstrapStatus): SyncStatusUi {
        val (title, detail) = when {
            status.syncReady -> "Connected" to "Changes sync directly to your other signed-in devices."
            status.isChecking -> "Connecting…" to "Checking the connection."
            status.phase == HostedBootstrapPhase.LOGIN_REQUIRED -> "Sign-in required" to "Sign in again to resume syncing."
            status.phase == HostedBootstrapPhase.LOCAL_ONLY -> "This device only" to "Create an account to sync with other devices."
            else -> "Offline" to "Changes are saved on this device and will sync when the connection returns."
        }
        return SyncStatusUi(
            title = title,
            detail = detail,
            showRetry = !status.syncReady && status.phase != HostedBootstrapPhase.LOCAL_ONLY,
            retryEnabled = !status.isChecking
        )
    }

    private fun developerInfo(selfHosted: Boolean, authState: AuthState, status: HostedBootstrapStatus): DeveloperInfo {
        val householdId = when {
            !selfHosted -> settings.getStringOrNull(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID)
                ?: settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID)
            else -> settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID)
        } ?: "unknown"
        return DeveloperInfo(
            selfHosted = selfHosted,
            connectionSummary = buildString {
                appendLine("Mode: ${if (selfHosted) "Self-hosted" else "Managed"}")
                appendLine("Device ID: ${authState.deviceId}")
                if (!selfHosted) appendLine("User ID: ${authState.userId ?: "not signed in"}")
                append("Household ID: $householdId")
            },
            serverUrl = settings.getStringOrNull(SETTINGS_KEY_SERVER_URL) ?: DEFAULT_SERVER_URL,
            householdId = householdId,
            bootstrapLines = if (selfHosted) emptyList() else listOf(
                "Phase: ${bootstrapPhaseLabel(status.phase)}",
                "Server: ${reachabilityLabel(status.serverReachability)}",
                "Supabase: ${reachabilityLabel(status.supabaseReachability)}",
                "Sync: ${if (status.syncReady) "Ready" else "Not ready"}"
            ),
            bootstrapError = status.lastError,
            bootstrapChecking = status.isChecking
        )
    }
}

fun tierLabel(tier: UserTier): String = when (tier) {
    UserTier.ANONYMOUS -> "Guest"
    UserTier.FREE -> "Free"
    UserTier.PAID -> "Paid"
}

fun bootstrapPhaseLabel(phase: HostedBootstrapPhase): String = when (phase) {
    HostedBootstrapPhase.LOCAL_ONLY -> "Local-only"
    HostedBootstrapPhase.WAITING_FOR_SERVER -> "Waiting for server"
    HostedBootstrapPhase.WAITING_FOR_SUPABASE -> "Waiting for Supabase"
    HostedBootstrapPhase.WAITING_FOR_HOUSEHOLD -> "Waiting for household"
    HostedBootstrapPhase.READY -> "Ready"
    HostedBootstrapPhase.LOGIN_REQUIRED -> "Login required"
}

fun reachabilityLabel(reachability: DependencyReachability): String = when (reachability) {
    DependencyReachability.UNKNOWN -> "Unknown"
    DependencyReachability.REACHABLE -> "Reachable"
    DependencyReachability.UNREACHABLE -> "Unreachable"
}
