package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import finance.shilling.shared.data.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface ReceiptFileStoreFactory { fun create(spaceId: String, legacyFiles: Boolean): ReceiptFileStore }

/** Entity stores, keys, bookkeeping, file storage and sync versions all share one immutable space. */
class FinanceSpaceGraph(
    db: ShillingDatabase,
    val id: String,
    deviceId: String,
    idGenerator: IdGenerator,
    val notifier: ChangeNotifier,
    val files: ReceiptFileStore
) {
    val sync = StoreSyncDeps(db, null, deviceId, idGenerator, id)
    val scope = sync.scope
    val accountsStore = createAccountStore(db, sync)
    val categoriesStore = createCategoryStore(db, sync)
    val schedulesStore = createScheduleStore(db, sync)
    val exceptionsStore = createScheduleExceptionStore(db, sync)
    val postingsStore = createPostingStore(db, sync)
    val receiptsStore = createReceiptStore(db, sync)
    val accounts = AccountRepository(notifier, accountsStore, sync)
    val categories = CategoryRepository(notifier, categoriesStore, sync)
    val schedules = ScheduleRepository(notifier, schedulesStore, exceptionsStore, sync)
    val postings = PostingRepository(idGenerator, notifier, postingsStore, accountsStore, categoriesStore, schedulesStore, sync)
    val receipts = ReceiptRepository(notifier, receiptsStore, sync)
    val facade = SyncStoreFacade(db, id, accountsStore, categoriesStore, schedulesStore, exceptionsStore, postingsStore, receiptsStore)
}

/** Swaps the entire graph; callers stop WebRTC before activating another space. */
class FinanceSpaceGraphs(
    private val db: ShillingDatabase,
    private val settings: com.russhwolf.settings.Settings,
    private val deviceIdentity: finance.shilling.shared.data.auth.DeviceIdentity,
    private val ids: IdGenerator,
    private val notifier: ChangeNotifier,
    private val fileFactory: ReceiptFileStoreFactory
) {
    private val transitions = Mutex()
    private fun graph(id: String, legacyFiles: Boolean) = FinanceSpaceGraph(db, id, deviceIdentity.deviceId, ids, notifier, fileFactory.create(id, legacyFiles))
    private val _active = MutableStateFlow(graph(settings.getStringOrNull("active_local_space") ?: LOCAL_SPACE_ID, settings.getStringOrNull("legacy_file_space") == settings.getStringOrNull("active_local_space")))
    val active: StateFlow<FinanceSpaceGraph> = _active
    val current: FinanceSpaceGraph get() = _active.value

    suspend fun activate(id: String, name: String = "Home", kind: String = "home"): FinanceSpaceGraph = transitions.withLock {
        require(id.isNotBlank())
        if (current.id == id) return@withLock current
        val old = current
        old.sync.state = old.sync.state.copy(peerSyncManager = null)
        old.scope.retire()
        try {
            val legacy = db.spaceQueries.selectById(LOCAL_SPACE_ID).awaitAsOneOrNull()
            if (old.id == LOCAL_SPACE_ID && legacy != null && db.spaceQueries.selectById(id).awaitAsOneOrNull() == null) {
                db.transaction {
                    db.accountQueries.claimLegacy(id)
                    db.categoryQueries.claimLegacy(id)
                    db.scheduleQueries.claimLegacy(id)
                    db.scheduleExceptionQueries.claimLegacy(id)
                    db.postingQueries.claimLegacy(id)
                    db.receiptQueries.claimLegacy(id)
                    db.receiptFileQueries.claimLegacy(id)
                    db.bookkeepingQueries.claimLegacy(id)
                    db.changeLogQueries.claimLegacy(id)
                    db.spaceQueries.claimLegacy(id, name, kind)
                }
                settings.putString("legacy_file_space", id)
            } else {
                db.spaceQueries.upsert(id, name, kind, 0).await()
            }
            settings.putString("active_local_space", id)
            val next = graph(id, settings.getStringOrNull("legacy_file_space") == id)
            _active.value = next
            next
        } catch (error: Throwable) {
            _active.value = graph(old.id, settings.getStringOrNull("legacy_file_space") == old.id || old.id == LOCAL_SPACE_ID)
            throw error
        }
    }

    internal suspend fun <T> writeAcrossSpaces(spaceIds: Set<String>, action: suspend (FinanceSpaceGraph) -> T): T = transitions.withLock {
        check(current.id in spaceIds) { "Open one of the transfer's spaces first." }
        current.scope.write { action(current) }
    }

    suspend fun accountsInSpace(id: String): List<Account> = createAccountStore(db, spaceId = id).readLocalSourceOfTruth(AccountKey.All)

    suspend fun wipeAll() = transitions.withLock {
        current.sync.state = current.sync.state.copy(peerSyncManager = null)
        current.scope.retire()
        for (row in db.spaceQueries.selectAll().awaitAsList()) {
            val space = graph(row.space_id, row.legacy_files == 1L)
            space.files.clearAll()
            space.receipts.clearAll()
            space.postings.clearAll()
            space.schedules.clearAll()
            space.categories.clearAll()
            space.accounts.clearAll()
            db.clearAllSyncMetadata(row.space_id)
            db.spaceQueries.deleteById(row.space_id)
        }
        resetOnboardingState(settings)
        settings.remove("active_local_space")
        settings.remove("legacy_file_space")
        db.spaceQueries.upsert(LOCAL_SPACE_ID, "Home", "home", 1).await()
        _active.value = graph(LOCAL_SPACE_ID, true)
    }
}
