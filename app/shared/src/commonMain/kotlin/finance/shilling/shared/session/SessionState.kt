package finance.shilling.shared.session

import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.FeatureGate
import finance.shilling.shared.data.auth.NoOpAuthService
import kotlinx.coroutines.flow.StateFlow

internal const val MANAGED_AUTH_UNAVAILABLE =
    "Managed sign-in is unavailable until the server config is reachable."

/** The app session's current phase, readable from common code (view models); `AppSession` implements it. */
interface SessionState {
    val phase: StateFlow<SessionPhase>
}

/** The Welcome flow's actions (readable from common code); `AppSession` implements them. */
interface OnboardingActions {
    /** Continue as a guest; [wipeHeldData] replaces a budget kept from a signed-out session. */
    suspend fun getStarted(wipeHeldData: Boolean)

    /** Signed in (or signed up) with a managed account. */
    suspend fun completeSignIn(wipeHeldData: Boolean)

    /** Fails with [NonMatchingAccountException] when the account doesn't match held local data. */
    suspend fun submitCredentials(mode: HostedCredentialsMode, email: String, password: String): Result<HostedCredentialsSubmitResult>

    suspend fun sendSignInLink(email: String): Result<Unit>

    suspend fun continueSelfHosted(selectedUrl: String): Result<Unit>

    /** The user kept their held budget after signing in to a non-matching account. */
    suspend fun cancelDestructiveAuth()
}

/** What the app shell shows. */
sealed interface SessionPhase {
    /** First launch or signed out: the Welcome flow. */
    data class Onboarding(
        val selfHostedOnly: Boolean,
        val initialSelfHostedUrl: String,
        val hasHeldLocalData: Boolean,
        val welcomeNotice: String?,
        /** Managed sign-in, once the server config has been fetched. */
        val authService: AuthService?
    ) : SessionPhase {
        val authReady: Boolean get() = authService != null && authService !is NoOpAuthService
        val authDisabledReason: String? get() = if (authReady) null else MANAGED_AUTH_UNAVAILABLE
    }

    /** Onboarding done, startup identity not resolved yet. */
    data object Starting : SessionPhase

    /** The main app. */
    data class Ready(
        val authService: AuthService,
        val featureGate: FeatureGate,
        val selfHosted: Boolean
    ) : SessionPhase
}
