package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.CredentialsCopy
import finance.shilling.shared.presentation.CredentialsOutcome
import finance.shilling.shared.presentation.SettingsUiState
import finance.shilling.shared.presentation.SettingsViewModel
import finance.shilling.shared.presentation.ThemeMode
import finance.shilling.shared.presentation.fullLabel
import finance.shilling.shared.session.HostedCredentialsMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.DayOfWeek

/** Swift-facing Settings. Week days and results are flattened to Swift-friendly types. */
class SettingsScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<SettingsViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<SettingsUiState> = viewModel.state

    /** Show developer tools without the About-tap unlock. */
    val developerToolsAlwaysOn: Boolean = isDebugBuild()

    val themeModes: List<ThemeMode> = viewModel.themeModes
    val currencyOptions: List<String> = viewModel.currencyOptions
    val weekDayLabels: List<String> = DayOfWeek.entries.map { it.fullLabel }

    fun weekStartIndex(state: SettingsUiState): Int = state.prefs.weekStart.ordinal

    fun setThemeMode(mode: ThemeMode) = viewModel.setThemeMode(mode)
    fun setCurrencySymbol(symbol: String) = viewModel.setCurrencySymbol(symbol)
    fun setWeekStart(index: Int) = viewModel.setWeekStart(DayOfWeek.entries[index])
    fun aboutTapped(): Boolean = viewModel.aboutTapped()
    fun retrySync() = viewModel.retrySync()
    fun saveServerUrl(url: String): String = viewModel.saveServerUrl(url)
    fun saveHouseholdId(id: String): String = viewModel.saveHouseholdId(id)

    @NativeCoroutines
    suspend fun submitCredentials(mode: HostedCredentialsMode, email: String, password: String): CredentialsOutcome {
        val trimmed = email.trim()
        return CredentialsCopy.outcome(viewModel.submitCredentials(mode, trimmed, password), trimmed)
    }

    @NativeCoroutines
    suspend fun refreshAccountStatus(): String = viewModel.refreshAccountStatus().fold(
        onSuccess = { "Account status checked." },
        onFailure = { it.message ?: "Could not check account status." }
    )

    @NativeCoroutines
    suspend fun setPassword(password: String): String = viewModel.setPassword(password).fold(
        onSuccess = { "Password set." },
        onFailure = { it.message ?: "Could not set password." }
    )

    @NativeCoroutines
    suspend fun sendSignInLink(email: String): String = viewModel.sendSignInLink(email).fold(
        onSuccess = { "Sign-in link sent. Open it on this device." },
        onFailure = { it.message ?: "Could not send a sign-in link." }
    )

    @NativeCoroutines
    suspend fun confirmAccountAction(): Unit = viewModel.confirmAccountAction()

    @NativeCoroutines
    suspend fun addSampleData(): String = viewModel.addSampleData()
}
