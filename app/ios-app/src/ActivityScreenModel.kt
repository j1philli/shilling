package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.ActivityUiState
import finance.shilling.shared.presentation.ActivityViewModel
import kotlinx.coroutines.flow.StateFlow

/** Swift-facing Activity. Opening a transaction or import goes to the Compose screens for now. */
class ActivityScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<ActivityViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<ActivityUiState> = viewModel.state

    fun setRange(months: Int) = viewModel.setRange(months)
    fun setQuery(text: String) = viewModel.setQuery(text)
    fun openTransaction(postingId: String?) = NativeTabBridge.openTransaction(postingId)
    fun openImport() = NativeTabBridge.openImport()
}
