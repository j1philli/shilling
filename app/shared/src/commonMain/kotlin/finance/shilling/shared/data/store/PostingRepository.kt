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
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
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
        // Keep the Store5 read and large projection off a native UI collector.
        }.flowOn(Dispatchers.Default)

    fun watchById(id: String): Flow<PostingWithDetails?> =
        store.watchDetails(PostingDetailsKey.ById(id)).map { it.firstOrNull() }.flowOn(Dispatchers.Default)

    fun watchPostingsBetween(start: LocalDate, end: LocalDate): Flow<List<Posting>> =
        store.watchCached(PostingKey.Between(start, end)).flowOn(Dispatchers.Default)

    /**
     * Candidates for [limit] merged activity rows, plus their first matching transfer partners.
     * At most 4 * limit rows cross the Store5 boundary. Callers still merge and take(limit).
     * Partners are constrained to the same date window, as in watchBetween.
     */
    fun watchRecentBetween(start: LocalDate, end: LocalDate, limit: Int): Flow<List<PostingWithDetails>> {
        require(limit >= 0)
        if (limit == 0) return flowOf(emptyList())
        return store.watchDetails(PostingDetailsKey.Recent(start, end, limit.toLong() * 2))
            .flowOn(Dispatchers.Default)
    }

    suspend fun getById(id: String): Posting? =
        store.readLocalSourceOfTruth(PostingKey.ById(id)).firstOrNull()

    /**
     * The other leg of a transfer, if [posting] is one. Scheduled transfer legs share an
     * id prefix (`_dr` / `_cr`); ad-hoc transfer legs share a pairId.
     */
    suspend fun getTransferPartner(posting: Posting): Posting? = withContext(Dispatchers.Default) {
        val partnerId = when {
            posting.id.endsWith("_dr") -> posting.id.removeSuffix("_dr") + "_cr"
            posting.id.endsWith("_cr") -> posting.id.removeSuffix("_cr") + "_dr"
            else -> null
        }
        if (partnerId != null) return@withContext getById(partnerId)
        if (posting.scheduleId != null) return@withContext null
        val pairId = posting.pairId ?: return@withContext null
        store.watchDetails(PostingDetailsKey.TransferPartner(
            pairId, posting.id, posting.date, posting.date.plus(1, DateTimeUnit.DAY)
        )).first().firstOrNull()?.posting
    }

    /** Record an ad-hoc transfer as a linked debit/credit pair. */
    suspend fun recordAdHocTransfer(
        title: String,
        amount: Double,
        fromAccountId: String,
        toAccountId: String,
        categoryId: String?,
        date: LocalDate
    ) {
        val pairId = idGenerator.newId()
        val base = Posting(
            id = "", scheduleId = null, type = ScheduleType.EXPENSE,
            accountId = fromAccountId, date = date, amount = amount,
            pairId = pairId, title = title,
            categoryId = categoryId?.takeIf { it.isNotBlank() }
        )
        record(base.copy(id = idGenerator.newId()))
        record(base.copy(id = idGenerator.newId(), type = ScheduleType.INCOME, accountId = toAccountId))
    }

    suspend fun getBetween(start: LocalDate, end: LocalDate): List<Posting> =
        store.readLocalSourceOfTruth(PostingKey.Between(start, end))

    /** Only the selected account's duplicate-check fields cross the Store5 boundary. */
    suspend fun getImportCandidates(accountId: String, start: LocalDate, end: LocalDate): List<ImportCandidate> =
        store.watchImportCandidates(ImportCandidateKey(accountId, start, end)).first()

    suspend fun loadRecentPostings(limit: Int = 50): List<PostingWithDetails> {
        val postings = store.readLocalSourceOfTruth(PostingKey.Recent(limit.toLong()))
        val accounts = accountStore.readLocalSourceOfTruth(AccountKey.All)
        val categories = categoryStore.readLocalSourceOfTruth(CategoryKey.All)
        val schedules = scheduleStore.readLocalSourceOfTruth(ScheduleKey.All)
        return postings.toPostingDetails(accounts, categories, schedules)
    }

    suspend fun record(posting: Posting) {
        require(posting.pairId?.startsWith(SPACE_TRANSFER_PREFIX) != true && getById(posting.id)?.pairId?.startsWith(SPACE_TRANSFER_PREFIX) != true) { "Edit a linked space transfer from Finance spaces in Settings." }
        store.writeLocally(
            StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(
                PostingKey.ById(posting.id),
                listOf(posting)
            )
        )
        notifier.notifyChanged()
    }

    suspend fun delete(id: String) {
        require(getById(id)?.pairId?.startsWith(SPACE_TRANSFER_PREFIX) != true) { "Delete both sides from Finance spaces in Settings." }
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
        store.writeLocally(
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
        // Bound transaction size without retaining the full import as Posting objects.
        // Web/desktop durably exports the whole SQLite DB at each commit, including
        // the updater's version-metadata commit. Larger batches avoid a snapshot storm.
        // Updaters still emit one P2P change per row after the database transaction.
        try {
            items.asSequence().chunked(1000).forEach { batch ->
                val postings = batch.map { (title, amount, date) ->
                    Posting(
                        id = idGenerator.newId(), scheduleId = null,
                        type = if (amount > 0) ScheduleType.INCOME else ScheduleType.EXPENSE,
                        accountId = accountId, date = date, amount = kotlin.math.abs(amount),
                        pairId = null, title = title,
                        categoryId = categoryId?.takeIf { it.isNotBlank() }
                    )
                }
                store.writeLocally(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.All, postings))
                yield()
            }

        } finally {
            // A cancelled import can already have committed earlier batches.
            notifier.notifyChanged()
        }
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
