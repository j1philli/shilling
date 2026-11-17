package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import finance.shilling.shared.data.*
import finance.shilling.shared.data.sync.*
import kotlin.concurrent.Volatile
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.*

private val log = Logger.withTag("Store")

/**
 * Marker for incoming sync writes: skip P2P rebroadcast from Store5 updaters
 * so applying a remote change does not echo back to peers.
 */
internal class SuppressStoreBroadcast : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SuppressStoreBroadcast>
}

// --- Wrapper classes for type-safe Koin injection ---

@OptIn(ExperimentalStoreApi::class)
class AccountStore(delegate: MutableStore<AccountKey, List<Account>>) :
    MutableStore<AccountKey, List<Account>> by delegate

@OptIn(ExperimentalStoreApi::class)
class CategoryStore(delegate: MutableStore<CategoryKey, List<Category>>) :
    MutableStore<CategoryKey, List<Category>> by delegate

@OptIn(ExperimentalStoreApi::class)
class ScheduleStore(delegate: MutableStore<ScheduleKey, List<Schedule>>) :
    MutableStore<ScheduleKey, List<Schedule>> by delegate

@OptIn(ExperimentalStoreApi::class)
class ScheduleExceptionStore(delegate: MutableStore<ScheduleExceptionKey, List<ScheduleException>>) :
    MutableStore<ScheduleExceptionKey, List<ScheduleException>> by delegate

@OptIn(ExperimentalStoreApi::class)
class PostingStore(
    delegate: MutableStore<PostingKey, List<Posting>>,
    private val detailsStore: Store<PostingDetailsKey, List<PostingWithDetails>>,
    private val importCandidates: Store<ImportCandidateKey, List<ImportCandidate>>
) : MutableStore<PostingKey, List<Posting>> by delegate {
    internal fun watchDetails(key: PostingDetailsKey): Flow<List<PostingWithDetails>> =
        detailsStore.stream(StoreReadRequest.localOnly(key))
            .mapNotNull { (it as? StoreReadResponse.Data)?.value }
            .distinctUntilChanged()

    internal fun watchImportCandidates(key: ImportCandidateKey): Flow<List<ImportCandidate>> =
        importCandidates.stream(StoreReadRequest.localOnly(key))
            .mapNotNull { (it as? StoreReadResponse.Data)?.value }
}

@OptIn(ExperimentalStoreApi::class)
class ReceiptStore(
    delegate: MutableStore<ReceiptKey, List<Receipt>>,
    private val listStore: Store<Unit, List<ReceiptWithPosting>>,
    private val countsStore: Store<Unit, ReceiptCounts>
) : MutableStore<ReceiptKey, List<Receipt>> by delegate {
    internal fun watchWithPostings(): Flow<List<ReceiptWithPosting>> =
        listStore.stream(StoreReadRequest.localOnly(Unit))
            .mapNotNull { (it as? StoreReadResponse.Data)?.value }
            .distinctUntilChanged()

    internal fun watchCounts(): Flow<ReceiptCounts> =
        countsStore.stream(StoreReadRequest.localOnly(Unit))
            .mapNotNull { (it as? StoreReadResponse.Data)?.value }
            .distinctUntilChanged()
}

// --- Sync dependencies holder ---

data class SyncState(
    val peerSyncManager: PeerSyncManager?,
    val deviceId: String
)

class StoreSyncDeps(
    val db: ShillingDatabase,
    peerSyncManager: PeerSyncManager?,
    deviceId: String,
    val idGenerator: IdGenerator,
    val spaceId: String = LOCAL_SPACE_ID
) {
    val scope = FinanceSpaceScope(spaceId)
    val versions = SyncVersions(db, spaceId, deviceId)
    @Volatile var state = SyncState(peerSyncManager, deviceId)
}

/** Node id for stores without sync (tests, read-only space lookups). */
private const val LOCAL_NODE = "local"

private fun versionsFor(db: ShillingDatabase, sync: StoreSyncDeps?, spaceId: String) =
    sync?.versions ?: SyncVersions(db, spaceId, LOCAL_NODE)

/** Incoming sync writes carry [SuppressStoreBroadcast]; their versions are already merged. */
private suspend fun isLocalWrite() = coroutineContext[SuppressStoreBroadcast] == null

/** Writes rows and, for local edits, versions the field groups each write changed. */
private suspend fun <T> SyncVersions.writeVersioned(
    db: ShillingDatabase,
    items: List<T>,
    payload: (T) -> ChangePayload,
    previous: suspend (T) -> T?,
    write: suspend (T) -> Unit
) {
    val local = isLocalWrite()
    db.transaction {
        items.forEach { item ->
            val before = if (local) previous(item) else null
            write(item)
            if (local) {
                val changed = payload(item)
                recordLocalUpsert(changed.entityType, changed.entityId, changed.toFields(), before?.let { payload(it).toFields() })
            }
        }
    }
}

// --- Factory functions ---

@OptIn(ExperimentalStoreApi::class)
fun createAccountStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): AccountStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
    val versions = versionsFor(db, sync, spaceId)
    val store = MutableStoreBuilder.from<AccountKey, List<Account>, List<Account>, List<Account>>(
        // Store5 is the local source of truth. User data does not traverse the server.
        fetcher = Fetcher.of { _: AccountKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    AccountKey.All -> db.accountQueries.selectAll(space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is AccountKey.ById -> db.accountQueries.selectById(key.id, space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, accounts -> lease.write {
                versions.writeVersioned(db, accounts, ChangePayload::AccountPayload,
                    previous = { a -> db.accountQueries.selectById(a.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain() }
                ) { a -> db.accountQueries.upsert(a.id, a.name, a.balance, space_id = spaceId).await() }
            } },
            delete = { key -> lease.write {
                when (key) {
                    AccountKey.All -> db.accountQueries.selectAll(space_id = spaceId).awaitAsList().forEach { account ->
                        db.transaction {
                            db.scheduleQueries.nullifyAccountId(account.id, space_id = spaceId)
                            db.scheduleQueries.nullifyCounterAccountId(account.id, space_id = spaceId)
                            db.receiptQueries.detachByAccount(space_id = spaceId, account_id = account.id)
                            db.postingQueries.deleteByAccountId(account.id, space_id = spaceId)
                            db.scheduleExceptionQueries.nullifyAccountOverrides(account.id, space_id = spaceId)
                            db.scheduleExceptionQueries.nullifyCounterAccountOverrides(account.id, space_id = spaceId)
                            db.accountQueries.deleteById(account.id, space_id = spaceId)
                        }
                    }
                    is AccountKey.ById -> { val local = isLocalWrite(); db.transaction {
                        check(db.postingQueries.selectLinkedByAccount(spaceId, key.id).awaitAsList().isEmpty()) { "Delete linked space transfers before deleting this account." }
                        val previous = db.accountQueries.selectById(key.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain()
                        val postings = db.postingQueries.selectByAccountId(key.id, space_id = spaceId).awaitAsList().map { it.toDomain() }
                        db.scheduleQueries.nullifyAccountId(key.id, space_id = spaceId)
                        db.scheduleQueries.nullifyCounterAccountId(key.id, space_id = spaceId)
                        db.receiptQueries.detachByAccount(space_id = spaceId, account_id = key.id)
                        db.postingQueries.deleteByAccountId(key.id, space_id = spaceId)
                        db.scheduleExceptionQueries.nullifyAccountOverrides(key.id, space_id = spaceId)
                        db.scheduleExceptionQueries.nullifyCounterAccountOverrides(key.id, space_id = spaceId)
                        db.accountQueries.deleteById(key.id, space_id = spaceId)
                        if (local) versions.recordLocalDelete(EntityType.ACCOUNT, key.id, previous?.let { ChangePayload.AccountPayload(it).toFields() })
                        // Cascaded deletes are tombstoned too, so a peer's stale copy can't bring them back.
                        postings.forEach { versions.recordLocalDelete(EntityType.POSTING, it.id, ChangePayload.PostingPayload(it).toFields()) }
                    } }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.ACCOUNT) { account: Account -> ChangePayload.AccountPayload(account) },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    return AccountStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createCategoryStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): CategoryStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
    val versions = versionsFor(db, sync, spaceId)
    val store = MutableStoreBuilder.from<CategoryKey, List<Category>, List<Category>, List<Category>>(
        fetcher = Fetcher.of { _: CategoryKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    CategoryKey.All -> db.categoryQueries.selectAll(space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is CategoryKey.ById -> db.categoryQueries.selectById(key.id, space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, categories -> lease.write {
                versions.writeVersioned(db, categories, ChangePayload::CategoryPayload,
                    previous = { c -> db.categoryQueries.selectById(c.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain() }
                ) { c -> db.categoryQueries.upsert(c.id, c.name, c.color, space_id = spaceId).await() }
            } },
            delete = { key -> lease.write {
                when (key) {
                    CategoryKey.All -> db.categoryQueries.selectAll(space_id = spaceId).awaitAsList().forEach { category ->
                        db.transaction {
                            db.categoryQueries.nullifyCategoryInSchedules(category.id, space_id = spaceId)
                            db.categoryQueries.deleteById(category.id, space_id = spaceId)
                        }
                    }
                    is CategoryKey.ById -> { val local = isLocalWrite(); db.transaction {
                        val previous = db.categoryQueries.selectById(key.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain()
                        db.categoryQueries.nullifyCategoryInSchedules(key.id, space_id = spaceId)
                        db.categoryQueries.deleteById(key.id, space_id = spaceId)
                        if (local) versions.recordLocalDelete(EntityType.CATEGORY, key.id, previous?.let { ChangePayload.CategoryPayload(it).toFields() })
                    } }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.CATEGORY) { category: Category -> ChangePayload.CategoryPayload(category) },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    return CategoryStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createScheduleStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): ScheduleStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
    val versions = versionsFor(db, sync, spaceId)
    val store = MutableStoreBuilder.from<ScheduleKey, List<Schedule>, List<Schedule>, List<Schedule>>(
        fetcher = Fetcher.of { _: ScheduleKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    ScheduleKey.All -> db.scheduleQueries.selectAll(space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleKey.ById -> db.scheduleQueries.selectById(key.id, space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleKey.Intersecting -> db.scheduleQueries.selectIntersecting(
                        start_date = key.end.toEpochDays().toLong(),
                        end_date = key.start.toEpochDays().toLong(),
                        space_id = spaceId
                    ).asFlow().map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, schedules -> lease.write {
                versions.writeVersioned(db, schedules, ChangePayload::SchedulePayload,
                    previous = { s -> db.scheduleQueries.selectById(s.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain() }
                ) { s ->
                    db.scheduleQueries.upsert(
                        id = s.id, title = s.title, amount = s.amount,
                        type = s.type.name,
                        account_id = s.accountId.takeIf { it.isNotBlank() },
                        counter_account_id = s.counterAccountId?.takeIf { it.isNotBlank() },
                        category_id = s.categoryId?.takeIf { it.isNotBlank() },
                        start_date = s.startDate.toEpochDays().toLong(),
                        end_date = s.endDate?.toEpochDays()?.toLong(),
                        freq = s.freq.name,
                        interval_ = s.interval.toLong(),
                        by_day_mask = s.byDayMask?.toLong(),
                        by_month_day = s.byMonthDay?.toLong(),
                        nth_weekday = s.nthWeekday?.toLong(),
                        last_day_flag = if (s.lastDayFlag) 1L else 0L,
                        auto_pay = if (s.autoPay) 1L else 0L,
                        notes = s.notes,
                        space_id = spaceId
                    ).await()
                }
            } },
            delete = { key -> lease.write {
                when (key) {
                    ScheduleKey.All -> {
                        db.scheduleQueries.selectAll(space_id = spaceId).awaitAsList().forEach { schedule ->
                            db.transaction {
                                db.scheduleExceptionQueries.deleteByScheduleId(schedule.id, space_id = spaceId)
                                db.receiptQueries.detachBySchedule(space_id = spaceId, schedule_id = schedule.id)
                                db.postingQueries.deleteByScheduleId(schedule.id, space_id = spaceId)
                                db.scheduleQueries.deleteById(schedule.id, space_id = spaceId)
                            }
                        }
                    }
                    is ScheduleKey.ById -> {
                        val local = isLocalWrite()
                        db.transaction {
                            val previous = db.scheduleQueries.selectById(key.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain()
                            val exceptions = db.scheduleExceptionQueries.selectByScheduleId(key.id, space_id = spaceId).awaitAsList().map { it.toDomain() }
                            val postings = db.postingQueries.selectByScheduleId(key.id, space_id = spaceId).awaitAsList().map { it.toDomain() }
                            db.scheduleExceptionQueries.deleteByScheduleId(key.id, space_id = spaceId)
                            db.receiptQueries.detachBySchedule(space_id = spaceId, schedule_id = key.id)
                            db.postingQueries.deleteByScheduleId(key.id, space_id = spaceId)
                            db.scheduleQueries.deleteById(key.id, space_id = spaceId)
                            if (local) versions.recordLocalDelete(EntityType.SCHEDULE, key.id, previous?.let { ChangePayload.SchedulePayload(it).toFields() })
                            exceptions.forEach { e ->
                                val payload = ChangePayload.ScheduleExceptionPayload(e)
                                versions.recordLocalDelete(EntityType.SCHEDULE_EXCEPTION, payload.entityId, payload.toFields())
                            }
                            postings.forEach { versions.recordLocalDelete(EntityType.POSTING, it.id, ChangePayload.PostingPayload(it).toFields()) }
                        }
                    }
                    is ScheduleKey.Intersecting -> {
                        db.scheduleQueries.selectIntersecting(
                            start_date = key.end.toEpochDays().toLong(),
                            end_date = key.start.toEpochDays().toLong(),
                            space_id = spaceId
                        ).awaitAsList().forEach { schedule ->
                            db.transaction {
                                db.scheduleExceptionQueries.deleteByScheduleId(schedule.id, space_id = spaceId)
                                db.receiptQueries.detachBySchedule(space_id = spaceId, schedule_id = schedule.id)
                                db.postingQueries.deleteByScheduleId(schedule.id, space_id = spaceId)
                                db.scheduleQueries.deleteById(schedule.id, space_id = spaceId)
                            }
                        }
                    }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.SCHEDULE) { schedule: Schedule -> ChangePayload.SchedulePayload(schedule) },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    return ScheduleStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createScheduleExceptionStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): ScheduleExceptionStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
    val versions = versionsFor(db, sync, spaceId)
    val store = MutableStoreBuilder.from<ScheduleExceptionKey, List<ScheduleException>, List<ScheduleException>, List<ScheduleException>>(
        fetcher = Fetcher.of { _: ScheduleExceptionKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    ScheduleExceptionKey.All -> db.scheduleExceptionQueries.selectAll(space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleExceptionKey.ByScheduleId -> db.scheduleExceptionQueries.selectByScheduleId(key.scheduleId, space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleExceptionKey.ByScheduleIds -> db.scheduleExceptionQueries.selectByScheduleIds(key.scheduleIds, space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleExceptionKey.ByKey -> db.scheduleExceptionQueries.selectByKey(
                        schedule_id = key.scheduleId,
                        date = key.date.toEpochDays().toLong(),
                        space_id = spaceId
                    ).asFlow().map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, exceptions -> lease.write {
                versions.writeVersioned(db, exceptions, ChangePayload::ScheduleExceptionPayload,
                    previous = { e -> db.scheduleExceptionQueries.selectByKey(e.scheduleId, e.date.toEpochDays().toLong(), space_id = spaceId).awaitAsOneOrNull()?.toDomain() }
                ) { e ->
                    db.scheduleExceptionQueries.upsert(
                        schedule_id = e.scheduleId,
                        date = e.date.toEpochDays().toLong(),
                        skip = if (e.skip) 1L else 0L,
                        override_amount = e.overrideAmount,
                        override_account_id = e.overrideAccountId?.takeIf { it.isNotBlank() },
                        override_counter_account_id = e.overrideCounterAccountId?.takeIf { it.isNotBlank() },
                        space_id = spaceId
                    ).await()
                }
            } },
            delete = { key -> lease.write {
                when (key) {
                    ScheduleExceptionKey.All -> db.scheduleExceptionQueries.selectAll(space_id = spaceId).awaitAsList().forEach { exception ->
                        db.scheduleExceptionQueries.deleteByKey(exception.schedule_id, exception.date, space_id = spaceId)
                    }
                    is ScheduleExceptionKey.ByScheduleId -> db.scheduleExceptionQueries.deleteByScheduleId(key.scheduleId, space_id = spaceId)
                    is ScheduleExceptionKey.ByScheduleIds -> key.scheduleIds.forEach {
                        db.scheduleExceptionQueries.deleteByScheduleId(it, space_id = spaceId)
                    }
                    is ScheduleExceptionKey.ByKey -> { val local = isLocalWrite(); db.transaction {
                        val previous = db.scheduleExceptionQueries.selectByKey(key.scheduleId, key.date.toEpochDays().toLong(), space_id = spaceId).awaitAsOneOrNull()?.toDomain()
                        db.scheduleExceptionQueries.deleteByKey(
                            key.scheduleId,
                            key.date.toEpochDays().toLong(),
                            space_id = spaceId
                        )
                        if (local) versions.recordLocalDelete(
                            EntityType.SCHEDULE_EXCEPTION,
                            exceptionEntityId(key.scheduleId, key.date),
                            previous?.let { ChangePayload.ScheduleExceptionPayload(it).toFields() }
                        )
                    } }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.SCHEDULE_EXCEPTION) { exception: ScheduleException -> ChangePayload.ScheduleExceptionPayload(exception) },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    return ScheduleExceptionStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createPostingStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): PostingStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
    val versions = versionsFor(db, sync, spaceId)
    val store = MutableStoreBuilder.from<PostingKey, List<Posting>, List<Posting>, List<Posting>>(
        fetcher = Fetcher.of { _: PostingKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    PostingKey.All -> db.postingQueries.selectAll(space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is PostingKey.ById -> db.postingQueries.selectById(key.id, space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is PostingKey.Between -> db.postingQueries.selectBetween(
                        date = key.start.toEpochDays().toLong(),
                        date_ = key.end.toEpochDays().toLong(),
                        space_id = spaceId
                    ).asFlow().map { it.awaitAsList().map { r -> r.toDomain() } }
                    is PostingKey.Recent -> db.postingQueries.selectRecent(space_id = spaceId, value_ = key.limit.coerceAtLeast(0L))
                        .asFlow().map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, postings -> lease.write {
                versions.writeVersioned(db, postings, ChangePayload::PostingPayload,
                    previous = { p -> db.postingQueries.selectById(p.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain() }
                ) { p ->
                    db.postingQueries.upsert(
                        id = p.id,
                        schedule_id = p.scheduleId?.takeIf { it.isNotBlank() },
                        type = p.type.name,
                        account_id = p.accountId,
                        date = p.date.toEpochDays().toLong(),
                        amount = p.amount,
                        pair_id = p.pairId,
                        title = p.title,
                        category_id = p.categoryId,
                        space_id = spaceId
                    ).await()
                }
            } },
            delete = { key -> lease.write {
                when (key) {
                    PostingKey.All -> db.postingQueries.selectAll(space_id = spaceId).awaitAsList().forEach { posting ->
                        detachAndDeletePosting(db, spaceId, posting.id)
                    }
                    is PostingKey.ById -> {
                        val local = isLocalWrite()
                        db.transaction {
                            val previous = db.postingQueries.selectById(key.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain()
                            detachAndDeletePosting(db, spaceId, key.id)
                            if (local) versions.recordLocalDelete(EntityType.POSTING, key.id, previous?.let { ChangePayload.PostingPayload(it).toFields() })
                        }
                    }
                    is PostingKey.Between -> db.postingQueries.selectBetween(
                        date = key.start.toEpochDays().toLong(),
                        date_ = key.end.toEpochDays().toLong(),
                        space_id = spaceId
                    ).awaitAsList().forEach { posting ->
                        detachAndDeletePosting(db, spaceId, posting.id)
                    }
                    is PostingKey.Recent -> db.postingQueries.selectRecent(space_id = spaceId, value_ = key.limit.coerceAtLeast(0L))
                        .awaitAsList()
                        .forEach { posting -> detachAndDeletePosting(db, spaceId, posting.id) }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.POSTING) { posting: Posting -> ChangePayload.PostingPayload(posting) },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    val detailsStore = StoreBuilder.from<PostingDetailsKey, List<PostingWithDetails>, List<PostingWithDetails>>(
        fetcher = Fetcher.of { _: PostingDetailsKey -> error("Posting projections are local-only") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                val query = when (key) {
                    is PostingDetailsKey.ById -> db.postingQueries.selectByIdWithDetails(postingId = key.id, space_id = spaceId, mapper = ::postingWithDetails)
                    is PostingDetailsKey.Recent -> db.postingQueries.selectRecentActivity(
                        startDay = key.start.toEpochDays(), endDay = key.end.toEpochDays(), rowLimit = key.rowLimit, space_id = spaceId, mapper = ::postingWithDetails)
                    is PostingDetailsKey.TransferPartner -> db.postingQueries.selectTransferPartnerWithDetails(
                        pairId = key.pairId, postingId = key.postingId, startDay = key.start.toEpochDays(), endDay = key.end.toEpochDays(), space_id = spaceId, mapper = ::postingWithDetails)
                }
                query.asFlow().map { it.awaitAsList() }
            },
            writer = { _, _ -> error("Posting projections are read-only") }
        )
    ).disableCache().build()
    val importCandidates = StoreBuilder.from<ImportCandidateKey, List<ImportCandidate>, List<ImportCandidate>>(
        fetcher = Fetcher.of { _: ImportCandidateKey -> error("Import candidates are local-only") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                db.postingQueries.selectImportCandidates(
                    space_id = spaceId, accountId = key.accountId,
                    startDay = key.start.toEpochDays(), endDay = key.end.toEpochDays(),
                    mapper = ::ImportCandidate
                ).asFlow().map { it.awaitAsList() }
            },
            writer = { _, _ -> error("Import candidates are read-only") }
        )
    ).disableCache().build()
    return PostingStore(store, detailsStore, importCandidates)
}

@OptIn(ExperimentalStoreApi::class)
fun createReceiptStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): ReceiptStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
    val versions = versionsFor(db, sync, spaceId)
    val store = MutableStoreBuilder.from<ReceiptKey, List<Receipt>, List<Receipt>, List<Receipt>>(
        fetcher = Fetcher.of { _: ReceiptKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    ReceiptKey.All -> db.receiptQueries.selectAll(space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ReceiptKey.ById -> db.receiptQueries.selectById(key.id, space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ReceiptKey.ByPosting -> db.receiptQueries.selectByPostingId(key.postingId, space_id = spaceId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, receipts -> lease.write {
                versions.writeVersioned(db, receipts, ChangePayload::ReceiptPayload,
                    previous = { r -> db.receiptQueries.selectById(r.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain() }
                ) { r ->
                    db.receiptQueries.upsert(
                        id = r.id, posting_id = r.postingId, file_path = r.filePath,
                        original_name = r.originalName, added_at = r.addedAt,
                        receipt_date = r.receiptDate, amount = r.amount, notes = r.notes,
                        space_id = spaceId
                    ).await()
                }
            } },
            delete = { key -> lease.write {
                when (key) {
                    ReceiptKey.All -> db.receiptQueries.selectAll(space_id = spaceId).awaitAsList().forEach { receipt ->
                        db.receiptQueries.deleteById(receipt.id, space_id = spaceId)
                    }
                    is ReceiptKey.ById -> {
                        val local = isLocalWrite()
                        db.transaction {
                            val previous = db.receiptQueries.selectById(key.id, space_id = spaceId).awaitAsOneOrNull()?.toDomain()
                            db.receiptQueries.deleteById(key.id, space_id = spaceId)
                            if (local) versions.recordLocalDelete(EntityType.RECEIPT, key.id, previous?.let { ChangePayload.ReceiptPayload(it).toFields() })
                        }
                    }
                    is ReceiptKey.ByPosting -> db.receiptQueries.selectByPostingId(key.postingId, space_id = spaceId).awaitAsList().forEach { receipt ->
                        db.receiptQueries.deleteById(receipt.id, space_id = spaceId)
                    }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.RECEIPT) { receipt: Receipt -> ChangePayload.ReceiptPayload(receipt) },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    // The list needs only attached posting titles/dates. Read the existing indexed
    // join through Store5 instead of loading every posting and schedule into Kotlin.
    val listStore = StoreBuilder.from<Unit, List<ReceiptWithPosting>, List<ReceiptWithPosting>>(
        fetcher = Fetcher.of { _: Unit -> error("Receipt lists are local-only") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { _: Unit -> db.receiptQueries.selectAllWithPostings(space_id = spaceId).asFlow()
                .map { query -> query.awaitAsList().map { it.toDomain() } } },
            writer = { _, _ -> error("Receipt list projection is read-only") }
        )
    ).disableCache().build()
    val countsStore = StoreBuilder.from<Unit, ReceiptCounts, ReceiptCounts>(
        fetcher = Fetcher.of { _: Unit -> error("Receipt counts are local-only") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { _: Unit -> db.receiptQueries.selectCounts(space_id = spaceId) { total, unattached ->
                ReceiptCounts(total.toInt(), unattached?.toInt() ?: 0)
            }.asFlow().map { it.awaitAsOne() } },
            writer = { _, _ -> error("Receipt count projection is read-only") }
        )
    ).disableCache().build()
    return ReceiptStore(store, listStore, countsStore)
}

// --- Sync broadcast helper (for repository deletes) ---

/** Sends the entity's current sync state (a tombstone after a delete) to connected peers. */
suspend fun broadcastEntityState(sync: StoreSyncDeps?, entityType: EntityType, entityId: String) {
    val s = sync ?: run {
        log.w { "[STORE] broadcastEntityState: sync is null for $entityType/$entityId" }
        return
    }
    check(s.scope.active) { "This finance space was closed." }
    val state = s.state
    val peerSyncManager = state.peerSyncManager ?: run {
        log.d { "[STORE] Offline — $entityType/$entityId goes out with the next full-state snapshot" }
        return
    }
    val entityState = s.versions.load(entityType, entityId) ?: return
    val change = entityState.toChangeMessage(s.idGenerator.newId(), entityType, entityId, state.deviceId)
    log.i { "[STORE] broadcastEntityState: $entityType/${change.op}/$entityId via P2P" }
    try {
        peerSyncManager.broadcast(change)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        log.w { "[STORE] broadcastEntityState failed: ${e::class.simpleName}: ${e.message}" }
    }
}

// --- Helpers ---

private fun <T : Any> identityConverter(): Converter<T, T, T> =
    Converter.Builder<T, T, T>()
        .fromNetworkToLocal { it }
        .fromOutputToLocal { it }
        .build()

/**
 * Broadcasts each written entity's sync state. The source-of-truth writer already versioned
 * the edit, so offline edits need nothing here: the next full-state snapshot carries them.
 */
@OptIn(ExperimentalStoreApi::class)
private inline fun <Key : Any, reified Item : Any> createUpdater(
    sync: StoreSyncDeps?,
    entityType: EntityType,
    crossinline toPayload: (Item) -> ChangePayload
): Updater<Key, List<Item>, Unit> = Updater.by(
    post = { _, items ->
        log.i { "[STORE] Updater post: $entityType items=${items.size} sync=${sync != null}" }
        check(sync?.scope?.active != false) { "This finance space was closed." }
        // Incoming remote applies share the same stores; skip echo broadcast.
        if (coroutineContext[SuppressStoreBroadcast] != null) {
            return@by UpdaterResult.Success.Typed(Unit)
        }
        if (sync == null) {
            log.w { "[STORE] sync is null — no broadcast for $entityType" }
            return@by UpdaterResult.Success.Typed(Unit)
        }
        val state = sync.state
        val peerSyncManager = state.peerSyncManager ?: run {
            log.d { "[STORE] peerSyncManager is NULL — $entityType x${items.size} goes out with the next full-state snapshot" }
            return@by UpdaterResult.Success.Typed(Unit)
        }
        try {
            items.forEach { item ->
                val entityId = toPayload(item).entityId
                val entityState = sync.versions.load(entityType, entityId) ?: return@forEach
                val change = entityState.toChangeMessage(sync.idGenerator.newId(), entityType, entityId, state.deviceId)
                log.d { "[STORE] Broadcasting $entityType/$entityId via P2P" }
                try { peerSyncManager.broadcast(change) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { throw PeerBroadcastFailure(failure) }
            }
            UpdaterResult.Success.Typed(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.w { "[STORE] Broadcast failed: ${e::class.simpleName}: ${e.message}" }
            UpdaterResult.Error.Exception(e)
        }
    }
)

@OptIn(ExperimentalStoreApi::class)
private fun <Key : Any> createBookkeeper(
    db: ShillingDatabase,
    spaceId: String,
    keyMapper: (Key) -> Pair<String, String>
): Bookkeeper<Key> = Bookkeeper.by(
    getLastFailedSync = { key ->
        val (type, id) = keyMapper(key)
        db.bookkeepingQueries.selectByEntity(type, id, space_id = spaceId)
            .awaitAsOneOrNull()?.timestamp
    },
    setLastFailedSync = { key, timestamp ->
        val (type, id) = keyMapper(key)
        db.bookkeepingQueries.upsert(type, id, timestamp, space_id = spaceId).await()
        true
    },
    clear = { key ->
        val (type, id) = keyMapper(key)
        db.bookkeepingQueries.deleteByEntity(type, id, space_id = spaceId)
        true
    },
    clearAll = {
        db.bookkeepingQueries.deleteAll(space_id = spaceId)
        true
    }
)

private suspend fun detachAndDeletePosting(db: ShillingDatabase, spaceId: String, postingId: String) {
    db.transaction {
        db.receiptQueries.detachByPosting(space_id = spaceId, posting_id = postingId)
        db.postingQueries.deleteById(postingId, space_id = spaceId)
    }
}
