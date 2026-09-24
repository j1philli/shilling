package finance.shilling.shared.data

import finance.shilling.shared.data.auth.AuthState
import finance.shilling.shared.data.auth.HostedSessionRequirement
import finance.shilling.shared.data.auth.UserTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingRoutingTest {
    @Test
    fun hostedGuestRequirementWhenNoPersistedAccount() {
        val decision = resolveOnboardingRouting(
            onboardingComplete = true,
            savedDeploymentSelection = DeploymentSelection.HOSTED,
            hadPersistedAccountSession = false,
            selfHosted = false,
            authState = anonymousAuthenticatedState()
        )

        assertEquals(DeploymentSelection.HOSTED, decision.deploymentSelection)
        assertEquals(HostedSessionRequirement.GUEST_ALLOWED, decision.hostedSessionRequirement)
        assertFalse(decision.shouldSoftReturnToWelcome)
    }

    @Test
    fun softReturnsWhenPersistedAccountSessionIsMissing() {
        val decision = resolveOnboardingRouting(
            onboardingComplete = true,
            savedDeploymentSelection = DeploymentSelection.HOSTED,
            hadPersistedAccountSession = true,
            selfHosted = false,
            authState = unauthenticatedState()
        )

        assertEquals(HostedSessionRequirement.ACCOUNT_REQUIRED, decision.hostedSessionRequirement)
        assertTrue(decision.shouldSoftReturnToWelcome)
    }

    @Test
    fun keepsAppWhenPersistedAccountSessionIsRestored() {
        val decision = resolveOnboardingRouting(
            onboardingComplete = true,
            savedDeploymentSelection = DeploymentSelection.HOSTED,
            hadPersistedAccountSession = true,
            selfHosted = false,
            authState = accountAuthenticatedState()
        )

        assertEquals(HostedSessionRequirement.ACCOUNT_REQUIRED, decision.hostedSessionRequirement)
        assertFalse(decision.shouldSoftReturnToWelcome)
    }

    @Test
    fun selfHostedNeverSoftReturns() {
        val decision = resolveOnboardingRouting(
            onboardingComplete = true,
            savedDeploymentSelection = DeploymentSelection.SELF_HOSTED,
            hadPersistedAccountSession = true,
            selfHosted = true,
            authState = unauthenticatedState()
        )

        assertNull(decision.hostedSessionRequirement)
        assertFalse(decision.shouldSoftReturnToWelcome)
    }

    private fun anonymousAuthenticatedState() = AuthState(
        isAuthenticated = true,
        userId = "user-1",
        email = null,
        tier = UserTier.ANONYMOUS,
        accessToken = "token-1",
        isAnonymous = true,
        deviceId = "device-1"
    )

    private fun accountAuthenticatedState() = AuthState(
        isAuthenticated = true,
        userId = "user-1",
        email = "user@example.com",
        tier = UserTier.FREE,
        accessToken = "token-1",
        isAnonymous = false,
        deviceId = "device-1"
    )

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
