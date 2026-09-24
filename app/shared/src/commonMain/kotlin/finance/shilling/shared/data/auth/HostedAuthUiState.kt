package finance.shilling.shared.data.auth

enum class HostedAuthUiPhase {
    PREPARING,
    READY,
    ISSUE
}

data class HostedAuthUiState(
    val phase: HostedAuthUiPhase,
    val authAvailable: Boolean,
    val statusText: String,
    val showStatusCard: Boolean,
    val disabledReason: String? = null,
    val shouldLogUnexpectedNoOpLoginState: Boolean = false
)

fun resolveHostedAuthUiState(
    authService: AuthService,
    bootstrapStatus: HostedBootstrapStatus
): HostedAuthUiState =
    resolveHostedAuthUiState(
        authAvailable = authService !is NoOpAuthService,
        authState = authService.authState.value,
        bootstrapStatus = bootstrapStatus
    )

fun resolveHostedAuthUiState(
    authAvailable: Boolean,
    authState: AuthState,
    bootstrapStatus: HostedBootstrapStatus
): HostedAuthUiState {
    if (authAvailable) {
        return HostedAuthUiState(
            phase = HostedAuthUiPhase.READY,
            authAvailable = true,
            statusText = if (authState.isAuthenticated) {
                "Managed auth is ready."
            } else {
                "Sign in or create an account to continue."
            },
            showStatusCard = false
        )
    }

    if (!bootstrapStatus.hasUserVisibleIssue()) {
        return HostedAuthUiState(
            phase = HostedAuthUiPhase.PREPARING,
            authAvailable = false,
            statusText = "Preparing managed login.",
            showStatusCard = false,
            disabledReason = "Managed account actions stay disabled until sign-in is ready.",
            shouldLogUnexpectedNoOpLoginState = bootstrapStatus.phase == HostedBootstrapPhase.LOGIN_REQUIRED
        )
    }

    val disabledReason = when (bootstrapStatus.phase) {
        HostedBootstrapPhase.WAITING_FOR_SERVER ->
            "Managed account actions stay disabled until the server config problem is resolved."
        HostedBootstrapPhase.LOCAL_ONLY ->
            "Managed account actions are unavailable in local-only mode."
        else ->
            "Managed account actions stay disabled until the managed login problem is resolved."
    }

    val statusText = when (bootstrapStatus.phase) {
        HostedBootstrapPhase.WAITING_FOR_SERVER ->
            "Managed login can't start until the server config is reachable."
        HostedBootstrapPhase.LOCAL_ONLY ->
            "Managed account sign-in is unavailable in local-only mode."
        else ->
            "Managed login hit a problem. Retry after the server is healthy."
    }

    return HostedAuthUiState(
        phase = HostedAuthUiPhase.ISSUE,
        authAvailable = false,
        statusText = statusText,
        showStatusCard = true,
        disabledReason = disabledReason,
        shouldLogUnexpectedNoOpLoginState = bootstrapStatus.phase == HostedBootstrapPhase.LOGIN_REQUIRED
    )
}

private fun HostedBootstrapStatus.hasUserVisibleIssue(): Boolean =
    phase == HostedBootstrapPhase.LOCAL_ONLY ||
        serverReachability == DependencyReachability.UNREACHABLE ||
        supabaseReachability == DependencyReachability.UNREACHABLE ||
        !lastError.isNullOrBlank()
