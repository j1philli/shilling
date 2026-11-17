package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.sync.ChangeMessage
import finance.shilling.shared.data.sync.ChangePayload
import finance.shilling.shared.data.sync.EntityType
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest


internal data class EntityChangeVersion(
    val changeId: String,
    val timestamp: Long
)

internal sealed interface ApplyIncomingChangeResult {
    /** The merge changed nothing here. */
    data object IgnoredStale : ApplyIncomingChangeResult
    /** The entity exists after the merge. */
    data object Applied : ApplyIncomingChangeResult
    /** The entity is deleted after the merge. */
    data object Deleted : ApplyIncomingChangeResult
}

internal data class SyncFullStateSnapshot(
    val changes: List<ChangeMessage>,
    val accountCount: Int,
    val categoryCount: Int,
    val scheduleCount: Int,
    val exceptionCount: Int,
    val postingCount: Int,
    val receiptCount: Int
)

@OptIn(ExperimentalStoreApi::class)
class SyncStoreFacade(
    private val db: ShillingDatabase,
    val spaceId: String = LOCAL_SPACE_ID,
    private val accountStore: AccountStore = createAccountStore(db, spaceId = spaceId),
    private val categoryStore: CategoryStore = createCategoryStore(db, spaceId = spaceId),
    private val scheduleStore: ScheduleStore = createScheduleStore(db, spaceId = spaceId),
    private val scheduleExceptionStore: ScheduleExceptionStore = createScheduleExceptionStore(db, spaceId = spaceId),
    private val postingStore: PostingStore = createPostingStore(db, spaceId = spaceId),
    private val receiptStore: ReceiptStore = createReceiptStore(db, spaceId = spaceId),
    /** Shared with the stores' writers so local edits and merges take turns per space. */
    private val scope: FinanceSpaceScope = FinanceSpaceScope(spaceId),
    private val versions: SyncVersions = SyncVersions(db, spaceId, "local")
) {
    /**
     * Every entity's sync state, including tombstones, so a reconnecting peer learns about
     * edits and deletes made while the two were apart.
     */
    internal suspend fun buildFullStateSnapshot(deviceId: String): SyncFullStateSnapshot {
        val accounts = accountStore.readLocalSourceOfTruth(AccountKey.All)
        val categories = categoryStore.readLocalSourceOfTruth(CategoryKey.All)
        val schedules = scheduleStore.readLocalSourceOfTruth(ScheduleKey.All)
        val exceptions = scheduleExceptionStore.readLocalSourceOfTruth(ScheduleExceptionKey.All)
        val postings = postingStore.readLocalSourceOfTruth(PostingKey.All)
        val receipts = receiptStore.readLocalSourceOfTruth(ReceiptKey.All)
        val states = versions.loadAll()

        val rows: List<ChangePayload> = buildList {
            accounts.forEach { add(ChangePayload.AccountPayload(it)) }
            categories.forEach { add(ChangePayload.CategoryPayload(it)) }
            schedules.forEach { add(ChangePayload.SchedulePayload(it)) }
            exceptions.forEach { add(ChangePayload.ScheduleExceptionPayload(it)) }
            postings.forEach { add(ChangePayload.PostingPayload(it)) }
            receipts.forEach { add(ChangePayload.ReceiptPayload(it)) }
        }
        val present = HashSet<Pair<EntityType, String>>()
        val changes = buildList {
            rows.forEach { payload ->
                val key = payload.entityType to payload.entityId
                present += key
                val values = payload.toFields()
                val state = states[key]?.copy(values = values) ?: versions.legacyState(key.first, key.second, values)
                add(state.toChangeMessage(state.snapshotId(key.first, key.second), key.first, key.second, deviceId))
            }
            // Deleted entities. Rows removed by a local cascade without a tombstone stay out:
            // their peers run the same cascade when the parent's delete arrives.
            states.forEach { (key, state) ->
                if (key !in present && !state.alive) {
                    add(state.toChangeMessage(state.snapshotId(key.first, key.second), key.first, key.second, deviceId))
                }
            }
        }

        return SyncFullStateSnapshot(
            changes = changes,
            accountCount = accounts.size,
            categoryCount = categories.size,
            scheduleCount = schedules.size,
            exceptionCount = exceptions.size,
            postingCount = postings.size,
            receiptCount = receipts.size
        )
    }

    internal suspend fun findReceiptsMissingFiles(hasFile: suspend (String) -> Boolean): Map<String, String> =
        buildMap {
            receiptStore.readLocalSourceOfTruth(ReceiptKey.All).forEach { receipt ->
                if (!hasFile(receipt.id)) {
                    put(receipt.id, receipt.originalName)
                }
            }
        }

    internal suspend fun receiptById(receiptId: String): Receipt? =
        receiptStore.readLocalSourceOfTruth(ReceiptKey.ById(receiptId)).firstOrNull()

    internal suspend fun receiptExists(receiptId: String): Boolean = receiptById(receiptId) != null

    /**
     * Merges a peer's change field group by field group, then writes the result through the
     * stores. Holding the space's write lock keeps a local edit from landing between the
     * merge and the write.
     */
    internal suspend fun applyIncomingChange(change: ChangeMessage): ApplyIncomingChangeResult =
        withContext(SuppressStoreBroadcast()) {
            val remote = change.toEntityState()
            scope.write {
                val type = change.entityType
                val row = currentRow(type, change.entityId)
                val merged = versions.merge(type, change.entityId, remote, row?.toFields())
                    ?: return@write ApplyIncomingChangeResult.IgnoredStale
                // Write first: if the entity can't be written (a delete blocked by linked
                // transfers), the merge isn't recorded and the next snapshot retries it.
                val result = if (merged.alive) {
                    val payload = type.payloadFrom(merged.values!!)
                    if (payload != row) write(payload)
                    ApplyIncomingChangeResult.Applied
                } else {
                    if (row != null) delete(type, change.entityId)
                    ApplyIncomingChangeResult.Deleted
                }
                versions.save(type, change.entityId, merged)
                result
            }
        }

    private suspend fun currentRow(type: EntityType, entityId: String): ChangePayload? = when (type) {
        EntityType.ACCOUNT -> accountStore.readLocalSourceOfTruth(AccountKey.ById(entityId)).firstOrNull()?.let(ChangePayload::AccountPayload)
        EntityType.CATEGORY -> categoryStore.readLocalSourceOfTruth(CategoryKey.ById(entityId)).firstOrNull()?.let(ChangePayload::CategoryPayload)
        EntityType.SCHEDULE -> scheduleStore.readLocalSourceOfTruth(ScheduleKey.ById(entityId)).firstOrNull()?.let(ChangePayload::SchedulePayload)
        EntityType.SCHEDULE_EXCEPTION -> parseExceptionEntityId(entityId)?.let { (scheduleId, date) ->
            scheduleExceptionStore.readLocalSourceOfTruth(ScheduleExceptionKey.ByKey(scheduleId, date)).firstOrNull()
        }?.let(ChangePayload::ScheduleExceptionPayload)
        EntityType.POSTING -> postingStore.readLocalSourceOfTruth(PostingKey.ById(entityId)).firstOrNull()?.let(ChangePayload::PostingPayload)
        EntityType.RECEIPT -> receiptStore.readLocalSourceOfTruth(ReceiptKey.ById(entityId)).firstOrNull()?.let(ChangePayload::ReceiptPayload)
    }

    private suspend fun write(payload: ChangePayload) {
        when (payload) {
            is ChangePayload.AccountPayload -> accountStore.writeLocally(
                StoreWriteRequest.of<AccountKey, List<Account>, Unit>(AccountKey.ById(payload.account.id), listOf(payload.account)))
            is ChangePayload.CategoryPayload -> categoryStore.writeLocally(
                StoreWriteRequest.of<CategoryKey, List<Category>, Unit>(CategoryKey.ById(payload.category.id), listOf(payload.category)))
            is ChangePayload.SchedulePayload -> scheduleStore.writeLocally(
                StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(ScheduleKey.ById(payload.schedule.id), listOf(payload.schedule)))
            is ChangePayload.ScheduleExceptionPayload -> scheduleExceptionStore.writeLocally(
                StoreWriteRequest.of<ScheduleExceptionKey, List<ScheduleException>, Unit>(
                    ScheduleExceptionKey.ByKey(payload.exception.scheduleId, payload.exception.date), listOf(payload.exception)))
            is ChangePayload.PostingPayload -> postingStore.writeLocally(
                StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.ById(payload.posting.id), listOf(payload.posting)))
            is ChangePayload.ReceiptPayload -> receiptStore.writeLocally(
                StoreWriteRequest.of<ReceiptKey, List<Receipt>, Unit>(ReceiptKey.ById(payload.receipt.id), listOf(payload.receipt)))
        }
    }

    private suspend fun delete(type: EntityType, entityId: String) {
        when (type) {
            EntityType.ACCOUNT -> accountStore.clear(AccountKey.ById(entityId))
            EntityType.CATEGORY -> categoryStore.clear(CategoryKey.ById(entityId))
            EntityType.SCHEDULE -> scheduleStore.clear(ScheduleKey.ById(entityId))
            EntityType.SCHEDULE_EXCEPTION -> parseExceptionEntityId(entityId)?.let { (scheduleId, date) ->
                scheduleExceptionStore.clear(ScheduleExceptionKey.ByKey(scheduleId, date))
            }
            EntityType.POSTING -> postingStore.clear(PostingKey.ById(entityId))
            EntityType.RECEIPT -> receiptStore.clear(ReceiptKey.ById(entityId))
        }
    }
}

internal suspend fun ShillingDatabase.latestEntityVersion(
    entityType: EntityType,
    entityId: String,
    spaceId: String = LOCAL_SPACE_ID
): EntityChangeVersion? =
    changeLogQueries.selectLatestForEntity(
        household_id = spaceId,
        entity_type = entityType.name,
        entity_id = entityId
    ).awaitAsOneOrNull()?.let { row ->
        EntityChangeVersion(
            changeId = row.change_id,
            timestamp = row.timestamp
        )
    }

/**
 * Clears Store5-internal sync metadata (change log, entity versions, bookkeeping). These are not entity data —
 * they track sync state and retry state. Declared here in the `store` package because the
 * SourceOfTruth and Bookkeeper own these tables; no other layer may touch them directly.
 */
suspend fun ShillingDatabase.clearAllSyncMetadata(spaceId: String = LOCAL_SPACE_ID) {
    bookkeepingQueries.deleteAll(spaceId)
    changeLogQueries.deleteForSpace(spaceId)
    entityStateQueries.deleteForSpace(spaceId)
}
