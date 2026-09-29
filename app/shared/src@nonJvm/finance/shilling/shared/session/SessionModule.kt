package finance.shilling.shared.session

import finance.shilling.shared.data.HouseholdIdCallback
import finance.shilling.shared.data.LocalDataWiper
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
            syncDeps = get(),
            localDataWiper = get(),
            webRtcPlatform = get(),
            fileStore = get(),
            notifier = get(),
            syncStoreFacade = get(),
            config = getOrNull() ?: AppSessionConfig()
        )
    }
    single { get<AppSession>().hostedBootstrapState }
    single { HostedBootstrapRetryCallback(get<AppSession>()::retryBootstrap) }
    single {
        val session = get<AppSession>()
        val wiper = get<LocalDataWiper>()
        // Full reset: wipe local data through Store5 repositories, then back to Welcome.
        ResetOnboardingCallback {
            wiper.wipe()
            session.resetOnboarding()
        }
    }
    // Soft-return to Welcome while keeping local data for a matching re-login.
    single { RestartHostedLoginCallback(get<AppSession>()::restartHostedLogin) }
    single { ServerUrlCallback(get<AppSession>()::changeServerUrl) }
    single { HouseholdIdCallback(get<AppSession>()::changeHouseholdId) }
}
