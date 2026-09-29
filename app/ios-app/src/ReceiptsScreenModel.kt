package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.ReceiptFilter
import finance.shilling.shared.presentation.ReceiptsUiState
import finance.shilling.shared.presentation.ReceiptsViewModel
import kotlinx.coroutines.flow.StateFlow

/** Swift-facing Receipts. Opening or adding a receipt goes to the Compose editor for now. */
class ReceiptsScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<ReceiptsViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<ReceiptsUiState> = viewModel.state

    fun setFilter(filter: ReceiptFilter) = viewModel.setFilter(filter)
    fun openReceipt(receiptId: String?) = NativeTabBridge.openReceipt(receiptId)
}
