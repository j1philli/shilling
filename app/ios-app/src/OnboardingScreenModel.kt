package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.CredentialsCopy
import finance.shilling.shared.presentation.CredentialsOutcome
import finance.shilling.shared.presentation.OnboardingRoute
import finance.shilling.shared.presentation.OnboardingUiState
import finance.shilling.shared.presentation.OnboardingViewModel
import finance.shilling.shared.session.HostedCredentialsMode
import finance.shilling.shared.session.SessionPhase
import finance.shilling.shared.session.SessionState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.mp.KoinPlatform

/** Which top-level UI the app shows. */
enum class AppPhase { ONBOARDING, STARTING, READY }

/** Swift-facing app session phase (process-wide, like the session itself). */
object AppRoot {
    @NativeCoroutinesState
    val phase: StateFlow<AppPhase> = KoinPlatform.getKoin().get<SessionState>().phase
        .map { it.toAppPhase() }
        .stateIn(MainScope(), SharingStarted.Eagerly, KoinPlatform.getKoin().get<SessionState>().phase.value.toAppPhase())

    private fun SessionPhase.toAppPhase(): AppPhase = when (this) {
        is SessionPhase.Onboarding -> AppPhase.ONBOARDING
        SessionPhase.Starting -> AppPhase.STARTING
        is SessionPhase.Ready -> AppPhase.READY
    }
}

/** Swift-facing Welcome flow. */
class OnboardingScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<OnboardingViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<OnboardingUiState> = viewModel.state

    fun open(route: OnboardingRoute) = viewModel.open(route)
    fun back() = viewModel.back()
    fun getStarted() = viewModel.getStarted()
    fun confirmDestructive() = viewModel.confirmDestructive()
    fun dismissDestructive() = viewModel.dismissDestructive()
    fun setSelfHostedUrl(value: String) = viewModel.setSelfHostedUrl(value)
    fun continueSelfHosted() = viewModel.continueSelfHosted()

    @NativeCoroutines
    suspend fun submitCredentials(mode: HostedCredentialsMode, email: String, password: String): CredentialsOutcome {
        val trimmed = email.trim()
        return CredentialsCopy.outcome(viewModel.submitCredentials(mode, trimmed, password), trimmed)
    }
}
