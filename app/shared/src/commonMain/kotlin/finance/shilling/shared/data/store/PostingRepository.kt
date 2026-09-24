package finance.shilling.shared.data.store

import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTx
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.EntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest

@OptIn(ExperimentalStoreApi::class)
class PostingRepository(
    private val idGenerator: IdGenerator,
    private val notifier: ChangeNotifier,
    private val store: PostingStore,
    private val accountStore: AccountStore,
    private val categoryStore: CategoryStore,
    private val scheduleStore: ScheduleStore,
    private val sync: StoreSyncDeps? = null
) {
    fun watchBetween(start: LocalDate, end: LocalDate): Flow<List<PostingWithDetails>> =
        combine(
            store.watchCached(PostingKey.Between(start, end)),
            accountStore.watchCached(AccountKey.All),
            categoryStore.watchCached(CategoryKey.All),
            scheduleStore.watchCached(ScheduleKey.All)
        ) { postings, accounts, categories, schedules ->
            postings.toPostingDetails(accounts, categories, schedules)
                .sortedByDescending { it.posting.date }
        }

    suspend fun getBetween(start: LocalDate, end: LocalDate): List<Posting> =
        store.readLocalSourceOfTruth(PostingKey.Between(start, end))

    suspend fun loadRecentPostings(limit: Int = 50): List<PostingWithDetails> {
        val postings = store.readLocalSourceOfTruth(PostingKey.Recent(limit.toLong()))
        val accounts = accountStore.readLocalSourceOfTruth(AccountKey.All)
        val categories = categoryStore.readLocalSourceOfTruth(CategoryKey.All)
        val schedules = scheduleStore.readLocalSourceOfTruth(ScheduleKey.All)
        return postings.toPostingDetails(accounts, categories, schedules)
    }

    suspend fun record(posting: Posting) {
        store.write(
            StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(
                PostingKey.ById(posting.id),
                listOf(posting)
            )
        )
        notifier.notifyChanged()
    }

    suspend fun delete(id: String) {
        store.clear(PostingKey.ById(id))
        broadcastChange(sync, EntityType.POSTING, ChangeOp.DELETE, id)
        notifier.notifyChanged()
    }

    /**
     * Wipe every posting through Store5's SourceOfTruth delete handler.
     * Local-only: does not emit per-entity DELETE broadcasts.
     */
    suspend fun clearAll() {
        store.clear(PostingKey.All)
        notifier.notifyChanged()
    }

    suspend fun recordAdHoc(
        title: String,
        amount: Double,
        type: ScheduleType,
        accountId: String,
        categoryId: String?,
        date: LocalDate
    ) {
        val id = idGenerator.newId()
        val posting = Posting(
            id = id, scheduleId = null, type = type,
            accountId = accountId, date = date, amount = amount,
            pairId = null, title = title,
            categoryId = categoryId?.takeIf { it.isNotBlank() }
        )
        store.write(
            StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(
                PostingKey.ById(id),
                listOf(posting)
            )
        )
        notifier.notifyChanged()
    }

    suspend fun bulkImport(
        items: List<Triple<String, Double, LocalDate>>,
        accountId: String,
        categoryId: String?
    ) {
        val postings = items.map { (title, amount, date) ->
            val id = idGenerator.newId()
            val type = if (amount > 0) ScheduleType.INCOME else ScheduleType.EXPENSE
            val catId = categoryId?.takeIf { it.isNotBlank() }
            Posting(
                id = id,
                scheduleId = null,
                type = type,
                accountId = accountId,
                date = date,
                amount = kotlin.math.abs(amount),
                pairId = null,
                title = title,
                categoryId = catId
            )
        }
        postings.forEach { posting ->
            store.write(
                StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(
                    PostingKey.ById(posting.id),
                    listOf(posting)
                )
            )
        }
        notifier.notifyChanged()
    }

    suspend fun recordFromOccurrence(
        tx: ScheduledTx,
        amountOverride: Double? = null,
        accountOverride: String? = null,
        counterAccountOverride: String? = null
    ) {
        val amount = amountOverride ?: tx.amount
        val account = accountOverride ?: tx.accountId
        val pairId = tx.pairId ?: "${tx.scheduleId}_${tx.date}"
        val postingIdBase = "${tx.scheduleId}_${tx.date}"
        if (tx.type == ScheduleType.TRANSFER) {
            val destination = counterAccountOverride ?: tx.counterAccountId
            require(!destination.isNullOrBlank()) { "Transfer occurrence missing destination account" }
            val debit = Posting(
                id = "${postingIdBase}_dr",
                scheduleId = tx.scheduleId,
                type = ScheduleType.EXPENSE,
                accountId = account,
                date = tx.date,
                amount = amount,
                pairId = pairId
            )
            val credit = Posting(
                id = "${postingIdBase}_cr",
                scheduleId = tx.scheduleId,
                type = ScheduleType.INCOME,
                accountId = destination,
                date = tx.date,
                amount = amount,
                pairId = pairId
            )
            record(debit)
            record(credit)
        } else {
            record(
                Posting(
                    id = postingIdBase,
                    scheduleId = tx.scheduleId,
                    type = tx.type,
                    accountId = account,
                    date = tx.date,
                    amount = amount,
                    pairId = pairId
                )
            )
        }
    }

    private fun List<Posting>.toPostingDetails(
        accounts: List<Account>,
        categories: List<Category>,
        schedules: List<Schedule>
    ): List<PostingWithDetails> {
        val accountsById = accounts.associateBy { it.id }
        val categoriesById = categories.associateBy { it.id }
        val schedulesById = schedules.associateBy { it.id }

        return map { posting ->
            val schedule = posting.scheduleId?.let(schedulesById::get)
            val category = posting.categoryId?.let(categoriesById::get)
                ?: schedule?.categoryId?.let(categoriesById::get)
            PostingWithDetails(
                posting = posting,
                title = posting.title ?: schedule?.title ?: "Transaction",
                accountName = accountsById[posting.accountId]?.name,
                categoryName = category?.name,
                categoryColor = category?.color
            )
        }
    }
}
