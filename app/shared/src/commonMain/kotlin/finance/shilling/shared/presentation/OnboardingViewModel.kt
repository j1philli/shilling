package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.DEFAULT_SELF_HOSTED_SERVER_URL
import finance.shilling.shared.data.analytics.ProductAnalytics
import finance.shilling.shared.data.analytics.ProductEvent
import finance.shilling.shared.session.HostedCredentialsMode
import finance.shilling.shared.session.HostedCredentialsSubmitResult
import finance.shilling.shared.session.NonMatchingAccountException
import finance.shilling.shared.session.OnboardingActions
import finance.shilling.shared.session.SessionPhase
import finance.shilling.shared.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class OnboardingRoute { LANDING, LOGIN, SELF_HOSTED }

/** A tappable Welcome option. */
data class OnboardingOption(val title: String, val body: String)

data class OnboardingUiState(
    val analyticsAvailable: Boolean = false,
    val analyticsConsent: Boolean = false,
    val route: OnboardingRoute = OnboardingRoute.LANDING,
    val selfHostedOnly: Boolean = false,
    val hasHeldLocalData: Boolean = false,
    /** Signed out but the budget is still here: title of the "sign in to keep it" card. */
    val heldDataTitle: String? = null,
    val getStarted: OnboardingOption = OnboardingOption("", ""),
    val signIn: OnboardingOption = OnboardingOption("", ""),
    val loginSubtitle: String = "",
    val authReady: Boolean = false,
    val authDisabledReason: String? = null,
    val selfHostedUrl: String = DEFAULT_SELF_HOSTED_SERVER_URL,
    val selfHostedError: String? = null,
    val validatingSelfHosted: Boolean = false,
    /** Set while the "Replace your saved budget?" confirmation is up. */
    val destructiveConfirm: ConfirmCopy? = null
) {
    val welcomeTitle: String get() = "Welcome to Shilling"
    val welcomeMessage: String get() =
        "Get started quickly, sign in to an existing account, or connect to your own server."
    val heldDataMessage: String get() =
        "Sign in with the same account to pick up where you left off. Starting over or using a different account will replace what's here."
    val heldDataAction: String get() = "Sign in to keep it"
    val selfHostedLabel: String get() = "Self-hosted"
    val selfHostedMessage: String get() = "Connect to a server you operate yourself."
    val selfHostedHint: String get() = selfHostedError ?: "Enter the server URL for the instance you operate."
    val canContinueSelfHosted: Boolean get() = selfHostedUrl.isNotBlank() && !validatingSelfHosted
    val keepLabel: String get() = "Keep budget and go back"
    /** The self-hosted screen has no way back when it's the only option. */
    val canLeaveSelfHosted: Boolean get() = !selfHostedOnly
}

/** The Welcome flow: guest start, sign-in / sign-up, or a self-hosted server. */
class OnboardingViewModel(
    private val session: SessionState,
    private val actions: OnboardingActions,
    private val analytics: ProductAnalytics
) : ViewModel() {
    private val analyticsConsent = MutableStateFlow(analytics.consent)
    private enum class Pending { GET_STARTED, NON_MATCHING_AUTH }

    private data class Local(
        val route: OnboardingRoute? = null,
        val selfHostedUrl: String? = null,
        val selfHostedError: String? = null,
        val validating: Boolean = false,
        val pending: Pending? = null
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<OnboardingUiState> = combine(session.phase, local, analyticsConsent) { phase, l, consent ->
        val onboarding = phase as? SessionPhase.Onboarding
        val held = onboarding?.hasHeldLocalData == true
        val selfHostedOnly = onboarding?.selfHostedOnly == true
        OnboardingUiState(
            analyticsAvailable = analytics.configured,
            analyticsConsent = consent,
            route = if (selfHostedOnly) OnboardingRoute.SELF_HOSTED else l.route ?: OnboardingRoute.LANDING,
            selfHostedOnly = selfHostedOnly,
            hasHeldLocalData = held,
            heldDataTitle = if (!held) null else when (onboarding?.welcomeNotice) {
                "session_expired", "signed_out" -> "You're signed out, but your budget is still here."
                else -> "Your budget is still saved on this device."
            },
            getStarted = if (held) {
                OnboardingOption("Start over", "Begin fresh as a guest. This replaces the budget saved on this device.")
            } else {
                OnboardingOption("Get Started", "Create a guest account and jump straight into the app.")
            },
            signIn = OnboardingOption(
                "Continue with email",
                if (held) "Use the same account to keep your current budget."
                else "Create an account or continue with one you already have."
            ),
            loginSubtitle = if (held) {
                "Use the same account to keep the budget saved on this device."
            } else {
                "Sign in or create an account to continue."
            },
            authReady = onboarding?.authReady == true,
            authDisabledReason = onboarding?.authDisabledReason,
            selfHostedUrl = l.selfHostedUrl ?: onboarding?.initialSelfHostedUrl ?: DEFAULT_SELF_HOSTED_SERVER_URL,
            selfHostedError = l.selfHostedError,
            validatingSelfHosted = l.validating,
            destructiveConfirm = l.pending?.let {
                ConfirmCopy(
                    title = "Replace your saved budget?",
                    message = "The budget on this device will be erased so you can start fresh. This can't be undone.",
                    confirmLabel = "Replace budget",
                    destructive = true
                )
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState())

    init {
        // A later visit (sign out, start over) begins at the landing page again.
        viewModelScope.launch {
            session.phase.collect { if (it !is SessionPhase.Onboarding) local.value = Local() }
        }
    }

    fun open(route: OnboardingRoute) = local.update { it.copy(route = route) }
    fun setAnalyticsConsent(value: Boolean) {
        analytics.consent = value
        analyticsConsent.value = value
    }
    fun back() = local.update { it.copy(route = OnboardingRoute.LANDING) }

    /** "Get Started" / "Start over": asks first when it would replace a held budget. */
    fun getStarted() {
        if (state.value.hasHeldLocalData) {
            local.update { it.copy(pending = Pending.GET_STARTED) }
        } else {
            viewModelScope.launch {
                actions.getStarted(wipeHeldData = false)
                analytics.captureAsync(ProductEvent.ONBOARDING_COMPLETED, "guest")
            }
        }
    }

    /** Signs in or up; a non-matching account asks before replacing the held budget. */
    suspend fun submitCredentials(mode: HostedCredentialsMode, email: String, password: String): Result<HostedCredentialsSubmitResult> {
        val result = actions.submitCredentials(mode, email, password)
        result.fold(
            onSuccess = {
                if (it.signUpResult?.existingAccount != true) {
                    actions.completeSignIn(wipeHeldData = false)
                    analytics.captureAsync(ProductEvent.ONBOARDING_COMPLETED, "hosted")
                }
            },
            onFailure = { error ->
                if (error is NonMatchingAccountException) local.update { it.copy(pending = Pending.NON_MATCHING_AUTH) }
            }
        )
        return result
    }

    suspend fun sendSignInLink(email: String): Result<Unit> = actions.sendSignInLink(email.trim())

    fun confirmDestructive() {
        val pending = local.value.pending ?: return
        local.update { it.copy(pending = null) }
        viewModelScope.launch {
            when (pending) {
                Pending.GET_STARTED -> {
                    actions.getStarted(wipeHeldData = true)
                    analytics.captureAsync(ProductEvent.ONBOARDING_COMPLETED, "guest")
                }
                Pending.NON_MATCHING_AUTH -> {
                    actions.completeSignIn(wipeHeldData = true)
                    analytics.captureAsync(ProductEvent.ONBOARDING_COMPLETED, "hosted")
                }
            }
        }
    }

    fun dismissDestructive() {
        val pending = local.value.pending ?: return
        local.update { it.copy(pending = null, route = OnboardingRoute.LANDING) }
        if (pending == Pending.NON_MATCHING_AUTH) viewModelScope.launch { actions.cancelDestructiveAuth() }
    }

    fun setSelfHostedUrl(value: String) = local.update { it.copy(selfHostedUrl = value, selfHostedError = null) }

    fun continueSelfHosted() {
        val url = state.value.selfHostedUrl.trim()
        if (url.isEmpty()) return
        local.update { it.copy(selfHostedError = null, validating = true) }
        viewModelScope.launch {
            val result = actions.continueSelfHosted(url)
            if (result.isSuccess) analytics.captureAsync(ProductEvent.ONBOARDING_COMPLETED, "self_hosted")
            local.update { it.copy(selfHostedError = result.exceptionOrNull()?.message, validating = false) }
        }
    }
}
