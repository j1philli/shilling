package finance.shilling.shared.data.store

import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptWithPosting
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.EntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest

@OptIn(ExperimentalStoreApi::class)
class ReceiptRepository(
    private val notifier: ChangeNotifier,
    private val store: ReceiptStore,
    private val postingStore: PostingStore,
    private val scheduleStore: ScheduleStore,
    private val sync: StoreSyncDeps? = null
) {
    fun watchAll(): Flow<List<ReceiptWithPosting>> =
        combine(
            store.watchCached(ReceiptKey.All),
            postingStore.watchCached(PostingKey.All),
            scheduleStore.watchCached(ScheduleKey.All)
        ) { receipts, postings, schedules ->
            receipts.toReceiptWithPosting(postings, schedules)
        }

    fun watchByPosting(postingId: String): Flow<List<Receipt>> = store.watchCached(ReceiptKey.ByPosting(postingId))

    suspend fun save(receipt: Receipt) {
        store.write(StoreWriteRequest.of<ReceiptKey, List<Receipt>, Unit>(ReceiptKey.ById(receipt.id), listOf(receipt)))
        notifier.notifyChanged()
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
        store.write(
            StoreWriteRequest.of<ReceiptKey, List<Receipt>, Unit>(
                ReceiptKey.ById(receiptId),
                listOf(receipt)
            )
        )
    }

    private fun List<Receipt>.toReceiptWithPosting(
        postings: List<finance.shilling.shared.data.Posting>,
        schedules: List<Schedule>
    ): List<ReceiptWithPosting> {
        val postingsById = postings.associateBy { it.id }
        val schedulesById = schedules.associateBy { it.id }

        return map { receipt ->
            val posting = receipt.postingId?.let(postingsById::get)
            val schedule = posting?.scheduleId?.let(schedulesById::get)
            ReceiptWithPosting(
                receipt = receipt,
                postingTitle = posting?.title ?: schedule?.title,
                postingDate = posting?.date?.let(LocalDate::toString)
            )
        }
    }
}
