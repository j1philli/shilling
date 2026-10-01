package finance.shilling.shared.session

import finance.shilling.shared.data.HouseholdIdCallback
import finance.shilling.shared.data.CloudRelayCallback
import finance.shilling.shared.data.ResetOnboardingCallback
import finance.shilling.shared.data.RestartHostedLoginCallback
import finance.shilling.shared.data.ServerUrlCallback
import finance.shilling.shared.data.auth.HostedBootstrapRetryCallback
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * [AppSession] and the Settings hooks that drive it. Apps pass this to `initKoin` with their
 * platform module (it needs `WebRtcPlatform`, so it lives in the non-JVM sources).
 */
val sessionModule: Module = module {
    single {
        AppSession(
            settings = get(),
            idGenerator = get(),
            httpClient = get(),
            deviceIdentity = get(),
            graphs = get(),
            webRtcPlatform = get(),
            notifier = get(),
            peerConnectionStatus = get(),
            config = getOrNull() ?: AppSessionConfig()
        )
    }
    single<SessionState> { get<AppSession>() }
    single<OnboardingActions> { get<AppSession>() }
    single { get<AppSession>().hostedBootstrapState }
    single { HostedBootstrapRetryCallback(get<AppSession>()::retryBootstrap) }
    // Full reset: stop sync, end the auth session, wipe local data, back to Welcome.
    single { ResetOnboardingCallback(get<AppSession>()::startOver) }
    // Soft-return to Welcome while keeping local data for a matching re-login.
    single { RestartHostedLoginCallback(get<AppSession>()::restartHostedLogin) }
    single { ServerUrlCallback(get<AppSession>()::changeServerUrl) }
    single { HouseholdIdCallback(get<AppSession>()::changeHouseholdId) }
    single { finance.shilling.shared.presentation.SpaceSelectionCallback(get<AppSession>()::applyHostedSpaces) }
    single { CloudRelayCallback(get<AppSession>()::changeCloudRelay) }
}
