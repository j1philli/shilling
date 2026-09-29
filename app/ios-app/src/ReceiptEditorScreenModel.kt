package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.presentation.AttachPickerUiState
import finance.shilling.shared.presentation.ReceiptEditorUiState
import finance.shilling.shared.presentation.ReceiptEditorViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.mp.KoinPlatform
import platform.Foundation.NSData

/** Swift-facing receipt editor; [receiptId] null adds one. */
class ReceiptEditorScreenModel(receiptId: String?) : IosViewModelHost() {
    private val viewModel = viewModel<ReceiptEditorViewModel>(receiptId)
    private val fileStore = KoinPlatform.getKoin().get<ReceiptFileStore>()

    @NativeCoroutinesState
    val state: StateFlow<ReceiptEditorUiState> = viewModel.state

    @NativeCoroutinesState
    val picker: StateFlow<AttachPickerUiState> = viewModel.picker

    fun setName(value: String) = viewModel.setName(value)
    fun setReceiptDate(epochDay: Long) = viewModel.setReceiptDate(epochDay)
    fun clearReceiptDate() = viewModel.setReceiptDate(null)
    fun setAmountText(value: String) = viewModel.setAmountText(value)
    fun setNotes(value: String) = viewModel.setNotes(value)
    fun setPickerQuery(value: String) = viewModel.setPickerQuery(value)
    fun setFile(fileName: String, data: NSData) = viewModel.setFile(fileName, data.toByteArray())

    @NativeCoroutines
    suspend fun attach(postingId: String): String? = viewModel.attach(postingId)

    @NativeCoroutines
    suspend fun detach(): UndoHandle? = viewModel.detach()?.let(::UndoHandle)

    @NativeCoroutines
    suspend fun save(): String? = viewModel.save()

    @NativeCoroutines
    suspend fun delete(): String? = viewModel.delete()

    /** A temporary copy of the receipt's file, named after the original, for Quick Look. */
    fun previewPath(): String? {
        val receipt = state.value.receipt ?: return null
        return (fileStore as? IosReceiptFileStore)?.previewPath(receipt.id, receipt.originalName)
    }
}
