package finance.shilling.shared.data

import finance.shilling.shared.data.auth.AuthState
import finance.shilling.shared.data.auth.HostedSessionRequirement
import finance.shilling.shared.data.auth.satisfiesHostedSessionRequirement

data class OnboardingRoutingDecision(
    val deploymentSelection: DeploymentSelection?,
    val hostedSessionRequirement: HostedSessionRequirement?,
    val shouldSoftReturnToWelcome: Boolean
)

fun resolveDeploymentSelection(
    onboardingComplete: Boolean,
    savedDeploymentSelection: DeploymentSelection?
): DeploymentSelection? =
    if (onboardingComplete) savedDeploymentSelection else null

fun resolveHostedSessionRequirement(
    deploymentSelection: DeploymentSelection?,
    hadPersistedAccountSession: Boolean
): HostedSessionRequirement? =
    when (deploymentSelection) {
        DeploymentSelection.HOSTED ->
            if (hadPersistedAccountSession) {
                HostedSessionRequirement.ACCOUNT_REQUIRED
            } else {
                HostedSessionRequirement.GUEST_ALLOWED
            }
        else -> null
    }

fun resolveOnboardingRouting(
    onboardingComplete: Boolean,
    savedDeploymentSelection: DeploymentSelection?,
    hadPersistedAccountSession: Boolean,
    selfHosted: Boolean,
    authState: AuthState
): OnboardingRoutingDecision {
    val deploymentSelection = resolveDeploymentSelection(
        onboardingComplete = onboardingComplete,
        savedDeploymentSelection = savedDeploymentSelection
    )
    val hostedSessionRequirement = resolveHostedSessionRequirement(
        deploymentSelection = deploymentSelection,
        hadPersistedAccountSession = hadPersistedAccountSession
    )
    val shouldSoftReturnToWelcome =
        onboardingComplete &&
            !selfHosted &&
            deploymentSelection == DeploymentSelection.HOSTED &&
            hadPersistedAccountSession &&
            !authState.satisfiesHostedSessionRequirement(HostedSessionRequirement.ACCOUNT_REQUIRED)

    return OnboardingRoutingDecision(
        deploymentSelection = deploymentSelection,
        hostedSessionRequirement = hostedSessionRequirement,
        shouldSoftReturnToWelcome = shouldSoftReturnToWelcome
    )
}
