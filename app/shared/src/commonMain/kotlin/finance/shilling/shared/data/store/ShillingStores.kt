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
import kotlin.time.Clock
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
    private val detailsStore: Store<PostingDetailsKey, List<PostingWithDetails>>
) : MutableStore<PostingKey, List<Posting>> by delegate {
    internal fun watchDetails(key: PostingDetailsKey): Flow<List<PostingWithDetails>> =
        detailsStore.stream(StoreReadRequest.localOnly(key))
            .mapNotNull { (it as? StoreReadResponse.Data)?.value }
            .distinctUntilChanged()
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
    @Volatile var state = SyncState(peerSyncManager, deviceId)
}

// --- Factory functions ---

@OptIn(ExperimentalStoreApi::class)
fun createAccountStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): AccountStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
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
                accounts.forEach { a -> db.accountQueries.upsert(a.id, a.name, a.balance, space_id = spaceId).await() }
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
                    is AccountKey.ById -> db.transaction {
                        check(db.postingQueries.selectLinkedByAccount(spaceId, key.id).awaitAsList().isEmpty()) { "Delete linked space transfers before deleting this account." }
                        db.scheduleQueries.nullifyAccountId(key.id, space_id = spaceId)
                        db.scheduleQueries.nullifyCounterAccountId(key.id, space_id = spaceId)
                        db.receiptQueries.detachByAccount(space_id = spaceId, account_id = key.id)
                        db.postingQueries.deleteByAccountId(key.id, space_id = spaceId)
                        db.scheduleExceptionQueries.nullifyAccountOverrides(key.id, space_id = spaceId)
                        db.scheduleExceptionQueries.nullifyCounterAccountOverrides(key.id, space_id = spaceId)
                        db.accountQueries.deleteById(key.id, space_id = spaceId)
                    }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.ACCOUNT) { account: Account ->
            ChangePayload.AccountPayload(account) to account.id
        },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    return AccountStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createCategoryStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): CategoryStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
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
                categories.forEach { c -> db.categoryQueries.upsert(c.id, c.name, c.color, space_id = spaceId).await() }
            } },
            delete = { key -> lease.write {
                when (key) {
                    CategoryKey.All -> db.categoryQueries.selectAll(space_id = spaceId).awaitAsList().forEach { category ->
                        db.transaction {
                            db.categoryQueries.nullifyCategoryInSchedules(category.id, space_id = spaceId)
                            db.categoryQueries.deleteById(category.id, space_id = spaceId)
                        }
                    }
                    is CategoryKey.ById -> db.transaction {
                        db.categoryQueries.nullifyCategoryInSchedules(key.id, space_id = spaceId)
                        db.categoryQueries.deleteById(key.id, space_id = spaceId)
                    }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.CATEGORY) { category: Category ->
            ChangePayload.CategoryPayload(category) to category.id
        },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    return CategoryStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createScheduleStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): ScheduleStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
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
                schedules.forEach { s ->
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
                        db.transaction {
                            db.scheduleExceptionQueries.deleteByScheduleId(key.id, space_id = spaceId)
                            db.receiptQueries.detachBySchedule(space_id = spaceId, schedule_id = key.id)
                            db.postingQueries.deleteByScheduleId(key.id, space_id = spaceId)
                            db.scheduleQueries.deleteById(key.id, space_id = spaceId)
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
        updater = createUpdater(sync, EntityType.SCHEDULE) { schedule: Schedule ->
            ChangePayload.SchedulePayload(schedule) to schedule.id
        },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    return ScheduleStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createScheduleExceptionStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): ScheduleExceptionStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
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
                exceptions.forEach { e ->
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
                    is ScheduleExceptionKey.ByKey -> db.scheduleExceptionQueries.deleteByKey(
                        key.scheduleId,
                        key.date.toEpochDays().toLong(),
                        space_id = spaceId
                    )
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.SCHEDULE_EXCEPTION) { exception: ScheduleException ->
            ChangePayload.ScheduleExceptionPayload(exception) to "${exception.scheduleId}_${exception.date.toEpochDays()}"
        },
        bookkeeper = createBookkeeper(db, spaceId) { it.toBookkeepingKey() }
    )
    return ScheduleExceptionStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createPostingStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): PostingStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
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
                    is PostingKey.Recent -> db.postingQueries.selectRecent(key.limit.coerceAtLeast(0L), space_id = spaceId)
                        .asFlow().map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, postings -> lease.write {
                db.transaction {
                postings.forEach { p ->
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
                }
            } },
            delete = { key -> lease.write {
                when (key) {
                    PostingKey.All -> db.postingQueries.selectAll(space_id = spaceId).awaitAsList().forEach { posting ->
                        detachAndDeletePosting(db, spaceId, posting.id)
                    }
                    is PostingKey.ById -> detachAndDeletePosting(db, spaceId, key.id)
                    is PostingKey.Between -> db.postingQueries.selectBetween(
                        date = key.start.toEpochDays().toLong(),
                        date_ = key.end.toEpochDays().toLong(),
                        space_id = spaceId
                    ).awaitAsList().forEach { posting ->
                        detachAndDeletePosting(db, spaceId, posting.id)
                    }
                    is PostingKey.Recent -> db.postingQueries.selectRecent(key.limit.coerceAtLeast(0L), space_id = spaceId)
                        .awaitAsList()
                        .forEach { posting -> detachAndDeletePosting(db, spaceId, posting.id) }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.POSTING) { posting: Posting ->
            ChangePayload.PostingPayload(posting) to posting.id
        },
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
    return PostingStore(store, detailsStore)
}

@OptIn(ExperimentalStoreApi::class)
fun createReceiptStore(db: ShillingDatabase, sync: StoreSyncDeps? = null, spaceId: String = sync?.spaceId ?: LOCAL_SPACE_ID): ReceiptStore {
    val lease = sync?.scope ?: FinanceSpaceScope(spaceId)
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
                receipts.forEach { r ->
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
                    is ReceiptKey.ById -> db.receiptQueries.deleteById(key.id, space_id = spaceId)
                    is ReceiptKey.ByPosting -> db.receiptQueries.selectByPostingId(key.postingId, space_id = spaceId).awaitAsList().forEach { receipt ->
                        db.receiptQueries.deleteById(receipt.id, space_id = spaceId)
                    }
                }
            } }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.RECEIPT) { receipt: Receipt ->
            ChangePayload.ReceiptPayload(receipt) to receipt.id
        },
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

// --- Sync broadcast helper (for repository delete/special operations) ---

suspend fun broadcastChange(
    sync: StoreSyncDeps?,
    entityType: EntityType,
    op: ChangeOp,
    entityId: String,
    payload: ChangePayload? = null
) {
    val s = sync ?: run {
        log.w { "[STORE] broadcastChange: sync is null for $entityType/$op/$entityId" }
        return
    }
    check(s.scope.active) { "This finance space was closed." }
    val state = s.state
    val change = ChangeMessage(
        id = s.idGenerator.newId(),
        entityType = entityType,
        op = op,
        entityId = entityId,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        deviceId = state.deviceId,
        payload = payload
    )
    s.db.recordEntityChange(change, s.spaceId)
    log.i { "[STORE] broadcastChange: $entityType/$op/$entityId via P2P (hasPeerManager=${state.peerSyncManager != null})" }
    try {
        state.peerSyncManager?.broadcast(change)
            ?: log.w { "[STORE] peerSyncManager is NULL in broadcastChange!" }
    } catch (e: Exception) {
        log.w { "[STORE] broadcastChange failed: ${e::class.simpleName}: ${e.message}" }
    }
}

// --- Helpers ---

private fun <T : Any> identityConverter(): Converter<T, T, T> =
    Converter.Builder<T, T, T>()
        .fromNetworkToLocal { it }
        .fromOutputToLocal { it }
        .build()

@OptIn(ExperimentalStoreApi::class)
private inline fun <Key : Any, reified Item : Any> createUpdater(
    sync: StoreSyncDeps?,
    entityType: EntityType,
    crossinline toPayload: (Item) -> Pair<ChangePayload, String>
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
        val peerSyncManager = state.peerSyncManager
        try {
            val changes = items.map { item ->
                val (payload, entityId) = toPayload(item)
                ChangeMessage(
                    id = sync.idGenerator.newId(),
                    entityType = entityType,
                    op = ChangeOp.UPSERT,
                    entityId = entityId,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                    deviceId = state.deviceId,
                    payload = payload
                )
            }
            // Commit version metadata together; never hold a database transaction
            // while waiting for a WebRTC peer.
            sync.db.transaction {
                changes.forEach { sync.db.recordEntityChange(it, sync.spaceId) }
            }
            changes.forEach { change ->
                val entityId = change.entityId
                if (peerSyncManager == null) {
                    log.w { "[STORE] peerSyncManager is NULL — $entityType/$entityId not broadcast (full-state backfill covers catch-up)" }
                } else {
                    log.i { "[STORE] Broadcasting $entityType/$entityId via P2P" }
                    peerSyncManager.broadcast(change)
                }
            }
            UpdaterResult.Success.Typed(Unit)
        } catch (e: Exception) {
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
