package finance.shilling.shared.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedAuthUiStateTest {
    @Test
    fun effectiveBootstrapStatusIgnoresStalePreviousSnapshot() {
        val effectiveStatus = resolveEffectiveHostedBootstrapStatus(
            authoritativeStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.WAITING_FOR_SERVER,
                serverReachability = DependencyReachability.UNKNOWN,
                syncReady = false
            ),
            liveStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.LOCAL_ONLY,
                serverReachability = DependencyReachability.UNKNOWN,
                syncReady = true
            )
        )

        assertEquals(HostedBootstrapPhase.WAITING_FOR_SERVER, effectiveStatus.phase)
        assertFalse(effectiveStatus.syncReady)
    }

    @Test
    fun effectiveBootstrapStatusKeepsLiveCheckingFlagForCurrentSnapshot() {
        val effectiveStatus = resolveEffectiveHostedBootstrapStatus(
            authoritativeStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.LOGIN_REQUIRED,
                serverReachability = DependencyReachability.REACHABLE,
                syncReady = false
            ),
            liveStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.LOGIN_REQUIRED,
                serverReachability = DependencyReachability.REACHABLE,
                syncReady = false,
                isChecking = true
            )
        )

        assertEquals(HostedBootstrapPhase.LOGIN_REQUIRED, effectiveStatus.phase)
        assertTrue(effectiveStatus.isChecking)
    }

    @Test
    fun loginRequiredWithLiveAuthServiceEnablesAccountActions() {
        val uiState = resolveHostedAuthUiState(
            authAvailable = true,
            authState = unauthenticatedState(),
            bootstrapStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.LOGIN_REQUIRED,
                serverReachability = DependencyReachability.REACHABLE
            )
        )

        assertTrue(uiState.authAvailable)
        assertEquals(HostedAuthUiPhase.READY, uiState.phase)
        assertEquals(
            "Sign in or create an account to continue.",
            uiState.statusText
        )
        assertFalse(uiState.showStatusCard)
        assertNull(uiState.disabledReason)
        assertFalse(uiState.shouldLogUnexpectedNoOpLoginState)
    }

    @Test
    fun readyWithoutSessionStaysQuietOnTheLoginGate() {
        val uiState = resolveHostedAuthUiState(
            authAvailable = true,
            authState = unauthenticatedState(),
            bootstrapStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.READY,
                serverReachability = DependencyReachability.REACHABLE,
                supabaseReachability = DependencyReachability.REACHABLE,
                syncReady = true
            )
        )

        assertTrue(uiState.authAvailable)
        assertEquals(HostedAuthUiPhase.READY, uiState.phase)
        assertEquals("Sign in or create an account to continue.", uiState.statusText)
        assertFalse(uiState.showStatusCard)
        assertNull(uiState.disabledReason)
    }

    @Test
    fun waitingForServerWithoutReachabilityFailureStaysInPreparingState() {
        val uiState = resolveHostedAuthUiState(
            authAvailable = false,
            authState = unauthenticatedState(),
            bootstrapStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.WAITING_FOR_SERVER,
                serverReachability = DependencyReachability.UNKNOWN
            )
        )

        assertFalse(uiState.authAvailable)
        assertEquals(HostedAuthUiPhase.PREPARING, uiState.phase)
        assertEquals(
            "Preparing managed login.",
            uiState.statusText
        )
        assertEquals(
            "Managed account actions stay disabled until sign-in is ready.",
            uiState.disabledReason
        )
        assertFalse(uiState.showStatusCard)
        assertFalse(uiState.shouldLogUnexpectedNoOpLoginState)
    }

    @Test
    fun waitingForServerShowsDiagnosticsAfterReachabilityFailure() {
        val uiState = resolveHostedAuthUiState(
            authAvailable = false,
            authState = unauthenticatedState(),
            bootstrapStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.WAITING_FOR_SERVER,
                serverReachability = DependencyReachability.UNREACHABLE,
                lastError = "Server unavailable"
            )
        )

        assertFalse(uiState.authAvailable)
        assertEquals(HostedAuthUiPhase.ISSUE, uiState.phase)
        assertEquals(
            "Managed login can't start until the server config is reachable.",
            uiState.statusText
        )
        assertEquals(
            "Managed account actions stay disabled until the server config problem is resolved.",
            uiState.disabledReason
        )
        assertTrue(uiState.showStatusCard)
        assertFalse(uiState.shouldLogUnexpectedNoOpLoginState)
    }

    @Test
    fun waitingForSupabaseUsesProblemCopyOnlyWhenItActuallyFails() {
        val uiState = resolveHostedAuthUiState(
            authAvailable = false,
            authState = unauthenticatedState(),
            bootstrapStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.WAITING_FOR_SUPABASE,
                serverReachability = DependencyReachability.REACHABLE,
                supabaseReachability = DependencyReachability.UNREACHABLE,
                lastError = "Supabase unavailable"
            )
        )

        assertFalse(uiState.authAvailable)
        assertEquals(HostedAuthUiPhase.ISSUE, uiState.phase)
        assertEquals(
            "Managed login hit a problem. Retry after the server is healthy.",
            uiState.statusText
        )
        assertEquals(
            "Managed account actions stay disabled until the managed login problem is resolved.",
            uiState.disabledReason
        )
        assertTrue(uiState.showStatusCard)
        assertFalse(uiState.shouldLogUnexpectedNoOpLoginState)
    }

    @Test
    fun loginRequiredWithNoOpAuthStaysInPreparingStateWithoutDiagnostics() {
        val uiState = resolveHostedAuthUiState(
            authAvailable = false,
            authState = unauthenticatedState(),
            bootstrapStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.LOGIN_REQUIRED,
                serverReachability = DependencyReachability.REACHABLE
            )
        )

        assertFalse(uiState.authAvailable)
        assertEquals(HostedAuthUiPhase.PREPARING, uiState.phase)
        assertEquals(
            "Preparing managed login.",
            uiState.statusText
        )
        assertEquals(
            "Managed account actions stay disabled until sign-in is ready.",
            uiState.disabledReason
        )
        assertFalse(uiState.showStatusCard)
        assertTrue(uiState.shouldLogUnexpectedNoOpLoginState)
    }

    private fun unauthenticatedState() = AuthState(
        isAuthenticated = false,
        userId = null,
        email = null,
        tier = UserTier.ANONYMOUS,
        accessToken = null,
        isAnonymous = true,
        deviceId = "device-1"
    )
}
