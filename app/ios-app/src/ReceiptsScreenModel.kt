package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.ReceiptFilter
import finance.shilling.shared.presentation.ReceiptsUiState
import finance.shilling.shared.presentation.ReceiptsViewModel
import kotlinx.coroutines.flow.StateFlow

/** Swift-facing Receipts. */
class ReceiptsScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<ReceiptsViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<ReceiptsUiState> = viewModel.state

    fun setFilter(filter: ReceiptFilter) = viewModel.setFilter(filter)
}
