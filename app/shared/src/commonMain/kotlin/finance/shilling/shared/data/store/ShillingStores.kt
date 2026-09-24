package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import finance.shilling.shared.data.*
import finance.shilling.shared.data.sync.*
import kotlin.concurrent.Volatile
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.flow.map
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
class PostingStore(delegate: MutableStore<PostingKey, List<Posting>>) :
    MutableStore<PostingKey, List<Posting>> by delegate

@OptIn(ExperimentalStoreApi::class)
class ReceiptStore(delegate: MutableStore<ReceiptKey, List<Receipt>>) :
    MutableStore<ReceiptKey, List<Receipt>> by delegate

// --- Sync dependencies holder ---

data class SyncState(
    val peerSyncManager: PeerSyncManager?,
    val deviceId: String
)

class StoreSyncDeps(
    val db: ShillingDatabase,
    peerSyncManager: PeerSyncManager?,
    deviceId: String,
    val idGenerator: IdGenerator
) {
    @Volatile var state = SyncState(peerSyncManager, deviceId)
}

// --- Factory functions ---

@OptIn(ExperimentalStoreApi::class)
fun createAccountStore(db: ShillingDatabase, sync: StoreSyncDeps? = null): AccountStore {
    val store = MutableStoreBuilder.from<AccountKey, List<Account>, List<Account>, List<Account>>(
        // Store5 is the local source of truth. User data does not traverse the server.
        fetcher = Fetcher.of { _: AccountKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    AccountKey.All -> db.accountQueries.selectAll().asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is AccountKey.ById -> db.accountQueries.selectById(key.id).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, accounts ->
                accounts.forEach { a -> db.accountQueries.upsert(a.id, a.name, a.balance) }
            },
            delete = { key ->
                when (key) {
                    AccountKey.All -> db.accountQueries.selectAll().awaitAsList().forEach { account ->
                        db.transaction {
                            db.scheduleQueries.nullifyAccountId(account.id)
                            db.scheduleQueries.nullifyCounterAccountId(account.id)
                            db.postingQueries.deleteByAccountId(account.id)
                            db.scheduleExceptionQueries.nullifyAccountOverrides(account.id)
                            db.scheduleExceptionQueries.nullifyCounterAccountOverrides(account.id)
                            db.accountQueries.deleteById(account.id)
                        }
                    }
                    is AccountKey.ById -> db.transaction {
                        db.scheduleQueries.nullifyAccountId(key.id)
                        db.scheduleQueries.nullifyCounterAccountId(key.id)
                        db.postingQueries.deleteByAccountId(key.id)
                        db.scheduleExceptionQueries.nullifyAccountOverrides(key.id)
                        db.scheduleExceptionQueries.nullifyCounterAccountOverrides(key.id)
                        db.accountQueries.deleteById(key.id)
                    }
                }
            }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.ACCOUNT) { account: Account ->
            ChangePayload.AccountPayload(account) to account.id
        },
        bookkeeper = createBookkeeper(db) { it.toBookkeepingKey() }
    )
    return AccountStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createCategoryStore(db: ShillingDatabase, sync: StoreSyncDeps? = null): CategoryStore {
    val store = MutableStoreBuilder.from<CategoryKey, List<Category>, List<Category>, List<Category>>(
        fetcher = Fetcher.of { _: CategoryKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    CategoryKey.All -> db.categoryQueries.selectAll().asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is CategoryKey.ById -> db.categoryQueries.selectAll().asFlow()
                        .map { it.awaitAsList().filter { r -> r.id == key.id }.map { r -> r.toDomain() } }
                }
            },
            writer = { _, categories ->
                categories.forEach { c -> db.categoryQueries.upsert(c.id, c.name, c.color) }
            },
            delete = { key ->
                when (key) {
                    CategoryKey.All -> db.categoryQueries.selectAll().awaitAsList().forEach { category ->
                        db.transaction {
                            db.categoryQueries.nullifyCategoryInSchedules(category.id)
                            db.categoryQueries.deleteById(category.id)
                        }
                    }
                    is CategoryKey.ById -> db.transaction {
                        db.categoryQueries.nullifyCategoryInSchedules(key.id)
                        db.categoryQueries.deleteById(key.id)
                    }
                }
            }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.CATEGORY) { category: Category ->
            ChangePayload.CategoryPayload(category) to category.id
        },
        bookkeeper = createBookkeeper(db) { it.toBookkeepingKey() }
    )
    return CategoryStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createScheduleStore(db: ShillingDatabase, sync: StoreSyncDeps? = null): ScheduleStore {
    val store = MutableStoreBuilder.from<ScheduleKey, List<Schedule>, List<Schedule>, List<Schedule>>(
        fetcher = Fetcher.of { _: ScheduleKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    ScheduleKey.All -> db.scheduleQueries.selectAll().asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleKey.ById -> db.scheduleQueries.selectById(key.id).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleKey.Intersecting -> db.scheduleQueries.selectIntersecting(
                        start_date = key.end.toEpochDays().toLong(),
                        end_date = key.start.toEpochDays().toLong()
                    ).asFlow().map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, schedules ->
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
                        notes = s.notes
                    )
                }
            },
            delete = { key ->
                when (key) {
                    ScheduleKey.All -> {
                        db.scheduleQueries.selectAll().awaitAsList().forEach { schedule ->
                            db.transaction {
                                db.scheduleExceptionQueries.deleteByScheduleId(schedule.id)
                                db.postingQueries.deleteByScheduleId(schedule.id)
                                db.scheduleQueries.deleteById(schedule.id)
                            }
                        }
                    }
                    is ScheduleKey.ById -> {
                        db.transaction {
                            db.scheduleExceptionQueries.deleteByScheduleId(key.id)
                            db.postingQueries.deleteByScheduleId(key.id)
                            db.scheduleQueries.deleteById(key.id)
                        }
                    }
                    is ScheduleKey.Intersecting -> {
                        db.scheduleQueries.selectIntersecting(
                            start_date = key.end.toEpochDays().toLong(),
                            end_date = key.start.toEpochDays().toLong()
                        ).awaitAsList().forEach { schedule ->
                            db.transaction {
                                db.scheduleExceptionQueries.deleteByScheduleId(schedule.id)
                                db.postingQueries.deleteByScheduleId(schedule.id)
                                db.scheduleQueries.deleteById(schedule.id)
                            }
                        }
                    }
                }
            }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.SCHEDULE) { schedule: Schedule ->
            ChangePayload.SchedulePayload(schedule) to schedule.id
        },
        bookkeeper = createBookkeeper(db) { it.toBookkeepingKey() }
    )
    return ScheduleStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createScheduleExceptionStore(db: ShillingDatabase, sync: StoreSyncDeps? = null): ScheduleExceptionStore {
    val store = MutableStoreBuilder.from<ScheduleExceptionKey, List<ScheduleException>, List<ScheduleException>, List<ScheduleException>>(
        fetcher = Fetcher.of { _: ScheduleExceptionKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    ScheduleExceptionKey.All -> db.scheduleExceptionQueries.selectAll().asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleExceptionKey.ByScheduleId -> db.scheduleExceptionQueries.selectByScheduleId(key.scheduleId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ScheduleExceptionKey.ByKey -> db.scheduleExceptionQueries.selectByKey(
                        schedule_id = key.scheduleId,
                        date = key.date.toEpochDays().toLong()
                    ).asFlow().map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, exceptions ->
                exceptions.forEach { e ->
                    db.scheduleExceptionQueries.upsert(
                        schedule_id = e.scheduleId,
                        date = e.date.toEpochDays().toLong(),
                        skip = if (e.skip) 1L else 0L,
                        override_amount = e.overrideAmount,
                        override_account_id = e.overrideAccountId?.takeIf { it.isNotBlank() },
                        override_counter_account_id = e.overrideCounterAccountId?.takeIf { it.isNotBlank() }
                    )
                }
            },
            delete = { key ->
                when (key) {
                    ScheduleExceptionKey.All -> db.scheduleExceptionQueries.selectAll().awaitAsList().forEach { exception ->
                        db.scheduleExceptionQueries.deleteByKey(exception.schedule_id, exception.date)
                    }
                    is ScheduleExceptionKey.ByScheduleId -> db.scheduleExceptionQueries.deleteByScheduleId(key.scheduleId)
                    is ScheduleExceptionKey.ByKey -> db.scheduleExceptionQueries.deleteByKey(
                        key.scheduleId,
                        key.date.toEpochDays().toLong()
                    )
                }
            }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.SCHEDULE_EXCEPTION) { exception: ScheduleException ->
            ChangePayload.ScheduleExceptionPayload(exception) to "${exception.scheduleId}_${exception.date.toEpochDays()}"
        },
        bookkeeper = createBookkeeper(db) { it.toBookkeepingKey() }
    )
    return ScheduleExceptionStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createPostingStore(db: ShillingDatabase, sync: StoreSyncDeps? = null): PostingStore {
    val store = MutableStoreBuilder.from<PostingKey, List<Posting>, List<Posting>, List<Posting>>(
        fetcher = Fetcher.of { _: PostingKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    PostingKey.All -> db.postingQueries.selectAll().asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is PostingKey.ById -> db.postingQueries.selectById(key.id).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is PostingKey.Between -> db.postingQueries.selectBetween(
                        date = key.start.toEpochDays().toLong(),
                        date_ = key.end.toEpochDays().toLong()
                    ).asFlow().map { it.awaitAsList().map { r -> r.toDomain() } }
                    is PostingKey.Recent -> db.postingQueries.selectBetween(
                        date = 0L,
                        date_ = Long.MAX_VALUE
                    ).asFlow().map { q ->
                        q.awaitAsList().map { r -> r.toDomain() }
                            .sortedByDescending { it.date }
                            .take(key.limit.toInt())
                    }
                }
            },
            writer = { _, postings ->
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
                        category_id = p.categoryId
                    )
                }
            },
            delete = { key ->
                when (key) {
                    PostingKey.All -> db.postingQueries.selectAll().awaitAsList().forEach { posting ->
                        db.postingQueries.deleteById(posting.id)
                    }
                    is PostingKey.ById -> db.postingQueries.deleteById(key.id)
                    is PostingKey.Between -> db.postingQueries.selectBetween(
                        date = key.start.toEpochDays().toLong(),
                        date_ = key.end.toEpochDays().toLong()
                    ).awaitAsList().forEach { posting ->
                        db.postingQueries.deleteById(posting.id)
                    }
                    is PostingKey.Recent -> db.postingQueries.selectBetween(
                        date = 0L,
                        date_ = Long.MAX_VALUE
                    ).awaitAsList()
                        .map { it.toDomain() }
                        .sortedByDescending { it.date }
                        .take(key.limit.toInt())
                        .forEach { posting -> db.postingQueries.deleteById(posting.id) }
                }
            }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.POSTING) { posting: Posting ->
            ChangePayload.PostingPayload(posting) to posting.id
        },
        bookkeeper = createBookkeeper(db) { it.toBookkeepingKey() }
    )
    return PostingStore(store)
}

@OptIn(ExperimentalStoreApi::class)
fun createReceiptStore(db: ShillingDatabase, sync: StoreSyncDeps? = null): ReceiptStore {
    val store = MutableStoreBuilder.from<ReceiptKey, List<Receipt>, List<Receipt>, List<Receipt>>(
        fetcher = Fetcher.of { _: ReceiptKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key ->
                when (key) {
                    ReceiptKey.All -> db.receiptQueries.selectAll().asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ReceiptKey.ById -> db.receiptQueries.selectById(key.id).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                    is ReceiptKey.ByPosting -> db.receiptQueries.selectByPostingId(key.postingId).asFlow()
                        .map { it.awaitAsList().map { r -> r.toDomain() } }
                }
            },
            writer = { _, receipts ->
                receipts.forEach { r ->
                    db.receiptQueries.upsert(
                        id = r.id, posting_id = r.postingId, file_path = r.filePath,
                        original_name = r.originalName, added_at = r.addedAt,
                        receipt_date = r.receiptDate, amount = r.amount, notes = r.notes
                    )
                }
            },
            delete = { key ->
                when (key) {
                    ReceiptKey.All -> db.receiptQueries.selectAll().awaitAsList().forEach { receipt ->
                        db.receiptQueries.deleteById(receipt.id)
                    }
                    is ReceiptKey.ById -> db.receiptQueries.deleteById(key.id)
                    is ReceiptKey.ByPosting -> db.receiptQueries.selectByPostingId(key.postingId).awaitAsList().forEach { receipt ->
                        db.receiptQueries.deleteById(receipt.id)
                    }
                }
            }
        ),
        converter = identityConverter()
    ).disableCache().build(
        updater = createUpdater(sync, EntityType.RECEIPT) { receipt: Receipt ->
            ChangePayload.ReceiptPayload(receipt) to receipt.id
        },
        bookkeeper = createBookkeeper(db) { it.toBookkeepingKey() }
    )
    return ReceiptStore(store)
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
    s.db.recordEntityChange(change)
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
            items.forEach { item ->
                val (payload, entityId) = toPayload(item)
                val change = ChangeMessage(
                    id = sync.idGenerator.newId(),
                    entityType = entityType,
                    op = ChangeOp.UPSERT,
                    entityId = entityId,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                    deviceId = state.deviceId,
                    payload = payload
                )
                // Always record locally for LWW, even when no peer is connected yet.
                sync.db.recordEntityChange(change)
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
    keyMapper: (Key) -> Pair<String, String>
): Bookkeeper<Key> = Bookkeeper.by(
    getLastFailedSync = { key ->
        val (type, id) = keyMapper(key)
        db.bookkeepingQueries.selectByEntity(type, id)
            .awaitAsOneOrNull()?.timestamp
    },
    setLastFailedSync = { key, timestamp ->
        val (type, id) = keyMapper(key)
        db.bookkeepingQueries.upsert(type, id, timestamp)
        true
    },
    clear = { key ->
        val (type, id) = keyMapper(key)
        db.bookkeepingQueries.deleteByEntity(type, id)
        true
    },
    clearAll = {
        db.bookkeepingQueries.deleteAll()
        true
    }
)
