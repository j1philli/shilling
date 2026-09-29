package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.ActivityUiState
import finance.shilling.shared.presentation.ActivityViewModel
import kotlinx.coroutines.flow.StateFlow

/** Swift-facing Activity. Import still opens the Compose screen. */
class ActivityScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<ActivityViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<ActivityUiState> = viewModel.state

    fun setRange(months: Int) = viewModel.setRange(months)
    fun setQuery(text: String) = viewModel.setQuery(text)
    fun openImport() = NativeTabBridge.openImport()
}
