package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.presentation.TransactionEditorUiState
import finance.shilling.shared.presentation.TransactionEditorViewModel
import finance.shilling.shared.presentation.Undoable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.StateFlow
import org.koin.mp.KoinPlatform
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * An undoable result for Swift. Unlike a screen model's `undoLast`, it outlives the screen that
 * produced it, so an editor can close and still offer Undo in its caller's toast.
 */
class UndoHandle(private val undoable: Undoable) {
    val message: String get() = undoable.message

    @NativeCoroutines
    suspend fun undo(): Unit = undoable.undo()
}

/** Swift-facing transaction editor; [postingId] null creates one. */
class TransactionEditorScreenModel(postingId: String?) : IosViewModelHost() {
    private val viewModel = viewModel<TransactionEditorViewModel>(postingId)
    private val fileStore = KoinPlatform.getKoin().get<finance.shilling.shared.data.store.FinanceSpaceGraphs>().current.files

    @NativeCoroutinesState
    val state: StateFlow<TransactionEditorUiState> = viewModel.state

    fun setType(value: ScheduleType) = viewModel.setType(value)
    fun setTitle(value: String) = viewModel.setTitle(value)
    fun setAmountText(value: String) = viewModel.setAmountText(value)
    fun setDate(epochDay: Long) = viewModel.setDate(epochDay)
    fun setAccount(id: String?) = viewModel.setAccount(id)
    fun setToAccount(id: String?) = viewModel.setToAccount(id)
    fun setCategory(id: String?) = viewModel.setCategory(id)

    @NativeCoroutines
    suspend fun save(): String? = viewModel.save()

    @NativeCoroutines
    suspend fun delete(): UndoHandle? = viewModel.delete()?.let(::UndoHandle)

    @NativeCoroutines
    suspend fun attachReceipt(fileName: String, data: NSData): String? =
        viewModel.attachReceipt(fileName, data.toByteArray())

    @NativeCoroutines
    suspend fun detachReceipt(receiptId: String): UndoHandle? = viewModel.detachReceipt(receiptId)?.let(::UndoHandle)

    /** A temporary copy of the receipt's file, named after the original, for Quick Look. */
    fun previewPath(receiptId: String): String? {
        val receipt = state.value.receipts.firstOrNull { it.id == receiptId } ?: return null
        return (fileStore as? IosReceiptFileStore)?.previewPath(receiptId, receipt.name)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
