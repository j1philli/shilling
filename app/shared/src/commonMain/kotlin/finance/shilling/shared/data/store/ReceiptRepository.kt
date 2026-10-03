package finance.shilling.shared.data.store

import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import finance.shilling.shared.data.ReceiptWithPosting
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.EntityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest

data class ReceiptCounts(val total: Int, val unattached: Int)

@OptIn(ExperimentalStoreApi::class)
class ReceiptRepository(
    private val notifier: ChangeNotifier,
    private val store: ReceiptStore,
    private val sync: StoreSyncDeps? = null
) {
    fun watchAll(): Flow<List<ReceiptWithPosting>> =
        store.watchWithPostings().flowOn(Dispatchers.Default)

    fun watchCounts(): Flow<ReceiptCounts> = store.watchCounts().flowOn(Dispatchers.Default)

    fun watchByPosting(postingId: String): Flow<List<Receipt>> = store.watchCached(ReceiptKey.ByPosting(postingId)).flowOn(Dispatchers.Default)

    suspend fun save(receipt: Receipt) {
        store.writeLocally(StoreWriteRequest.of<ReceiptKey, List<Receipt>, Unit>(ReceiptKey.ById(receipt.id), listOf(receipt)))
        notifier.notifyChanged()
    }

    /** Metadata must exist before SQL-backed bytes can satisfy their scoped foreign key. */
    suspend fun saveWithFile(receipt: Receipt, files: ReceiptFileStore, bytes: ByteArray) {
        check(store.readLocalSourceOfTruth(ReceiptKey.ById(receipt.id)).isEmpty()) { "Receipt already exists" }
        try {
            save(receipt)
            files.store(receipt.id, receipt.originalName, bytes)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { files.delete(receipt.id) }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { delete(receipt.id) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    suspend fun delete(receiptId: String) {
        store.clear(ReceiptKey.ById(receiptId))
        broadcastChange(sync, EntityType.RECEIPT, ChangeOp.DELETE, receiptId)
        notifier.notifyChanged()
    }

    /**
     * Wipe every receipt through Store5's SourceOfTruth delete handler.
     * Local-only: does not emit per-entity DELETE broadcasts.
     */
    suspend fun clearAll() {
        store.clear(ReceiptKey.All)
        notifier.notifyChanged()
    }

    suspend fun attach(receiptId: String, postingId: String) {
        writeUpdatedReceipt(receiptId) { copy(postingId = postingId) }
        notifier.notifyChanged()
    }

    suspend fun detach(receiptId: String) {
        writeUpdatedReceipt(receiptId) { copy(postingId = null) }
        notifier.notifyChanged()
    }

    suspend fun updateMetadata(receiptId: String, notes: String?, receiptDate: Long?, amount: Double?) {
        writeUpdatedReceipt(receiptId) {
            copy(notes = notes, receiptDate = receiptDate, amount = amount)
        }
        notifier.notifyChanged()
    }

    private suspend fun writeUpdatedReceipt(receiptId: String, update: Receipt.() -> Receipt) {
        val receipt = store.readLocalSourceOfTruth(ReceiptKey.ById(receiptId)).firstOrNull()?.update() ?: return
        store.writeLocally(
            StoreWriteRequest.of<ReceiptKey, List<Receipt>, Unit>(
                ReceiptKey.ById(receiptId),
                listOf(receipt)
            )
        )
    }

}
