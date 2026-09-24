package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.sync.ChangeMessage
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.ChangePayload
import finance.shilling.shared.data.sync.EntityType
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest

private const val LOCAL_CHANGE_LOG_HOUSEHOLD = "__local_sync__"

internal data class EntityChangeVersion(
    val changeId: String,
    val timestamp: Long
)

internal sealed interface ApplyIncomingChangeResult {
    data object Applied : ApplyIncomingChangeResult
    data class IgnoredStale(val current: EntityChangeVersion?) : ApplyIncomingChangeResult
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
    private val accountStore: AccountStore = createAccountStore(db),
    private val categoryStore: CategoryStore = createCategoryStore(db),
    private val scheduleStore: ScheduleStore = createScheduleStore(db),
    private val scheduleExceptionStore: ScheduleExceptionStore = createScheduleExceptionStore(db),
    private val postingStore: PostingStore = createPostingStore(db),
    private val receiptStore: ReceiptStore = createReceiptStore(db)
) {
    internal suspend fun buildFullStateSnapshot(deviceId: String): SyncFullStateSnapshot {
        val accounts = accountStore.readLocalSourceOfTruth(AccountKey.All)
        val categories = categoryStore.readLocalSourceOfTruth(CategoryKey.All)
        val schedules = scheduleStore.readLocalSourceOfTruth(ScheduleKey.All)
        val exceptions = scheduleExceptionStore.readLocalSourceOfTruth(ScheduleExceptionKey.All)
        val postings = postingStore.readLocalSourceOfTruth(PostingKey.All)
        val receipts = receiptStore.readLocalSourceOfTruth(ReceiptKey.All)

        val changes = buildList {
            accounts.forEach { account ->
                add(snapshotChange(deviceId, EntityType.ACCOUNT, account.id, ChangePayload.AccountPayload(account)))
            }
            categories.forEach { category ->
                add(snapshotChange(deviceId, EntityType.CATEGORY, category.id, ChangePayload.CategoryPayload(category)))
            }
            schedules.forEach { schedule ->
                add(snapshotChange(deviceId, EntityType.SCHEDULE, schedule.id, ChangePayload.SchedulePayload(schedule)))
            }
            exceptions.forEach { exception ->
                val entityId = "${exception.scheduleId}_${exception.date.toEpochDays()}"
                add(
                    snapshotChange(
                        deviceId,
                        EntityType.SCHEDULE_EXCEPTION,
                        entityId,
                        ChangePayload.ScheduleExceptionPayload(exception)
                    )
                )
            }
            postings.forEach { posting ->
                add(snapshotChange(deviceId, EntityType.POSTING, posting.id, ChangePayload.PostingPayload(posting)))
            }
            receipts.forEach { receipt ->
                add(snapshotChange(deviceId, EntityType.RECEIPT, receipt.id, ChangePayload.ReceiptPayload(receipt)))
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

    internal suspend fun applyIncomingChange(change: ChangeMessage): ApplyIncomingChangeResult =
        withContext(SuppressStoreBroadcast()) {
            val current = db.latestEntityVersion(change.entityType, change.entityId)
            if (change.isStaleComparedTo(current)) {
                return@withContext ApplyIncomingChangeResult.IgnoredStale(current)
            }

            when (change.entityType) {
                EntityType.ACCOUNT -> applyAccountChange(change)
                EntityType.CATEGORY -> applyCategoryChange(change)
                EntityType.SCHEDULE -> applyScheduleChange(change)
                EntityType.SCHEDULE_EXCEPTION -> applyScheduleExceptionChange(change)
                EntityType.POSTING -> applyPostingChange(change)
                EntityType.RECEIPT -> applyReceiptChange(change)
            }

            db.recordEntityChange(change)
            ApplyIncomingChangeResult.Applied
        }

    private suspend fun snapshotChange(
        deviceId: String,
        entityType: EntityType,
        entityId: String,
        payload: ChangePayload
    ): ChangeMessage =
        ChangeMessage(
            id = snapshotChangeId(entityType, entityId, deviceId),
            entityType = entityType,
            op = ChangeOp.UPSERT,
            entityId = entityId,
            timestamp = snapshotTimestamp(entityType, entityId),
            deviceId = deviceId,
            payload = payload
        )

    private suspend fun snapshotChangeId(
        entityType: EntityType,
        entityId: String,
        deviceId: String
    ): String =
        db.latestEntityVersion(entityType, entityId)?.changeId
            ?: "snapshot:$deviceId:${entityType.name}:$entityId"

    private suspend fun snapshotTimestamp(
        entityType: EntityType,
        entityId: String
    ): Long = db.latestEntityVersion(entityType, entityId)?.timestamp ?: 0L

    private suspend fun applyAccountChange(change: ChangeMessage) {
        when (change.op) {
            ChangeOp.UPSERT -> {
                val account = (change.payload as? ChangePayload.AccountPayload)?.account ?: return
                accountStore.write(
                    StoreWriteRequest.of<AccountKey, List<Account>, Unit>(
                        AccountKey.ById(account.id),
                        listOf(account)
                    )
                )
            }
            ChangeOp.DELETE -> accountStore.clear(AccountKey.ById(change.entityId))
        }
    }

    private suspend fun applyCategoryChange(change: ChangeMessage) {
        when (change.op) {
            ChangeOp.UPSERT -> {
                val category = (change.payload as? ChangePayload.CategoryPayload)?.category ?: return
                categoryStore.write(
                    StoreWriteRequest.of<CategoryKey, List<Category>, Unit>(
                        CategoryKey.ById(category.id),
                        listOf(category)
                    )
                )
            }
            ChangeOp.DELETE -> categoryStore.clear(CategoryKey.ById(change.entityId))
        }
    }

    private suspend fun applyScheduleChange(change: ChangeMessage) {
        when (change.op) {
            ChangeOp.UPSERT -> {
                val schedule = (change.payload as? ChangePayload.SchedulePayload)?.schedule ?: return
                scheduleStore.write(
                    StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(
                        ScheduleKey.ById(schedule.id),
                        listOf(schedule)
                    )
                )
            }
            ChangeOp.DELETE -> scheduleStore.clear(ScheduleKey.ById(change.entityId))
        }
    }

    private suspend fun applyScheduleExceptionChange(change: ChangeMessage) {
        when (change.op) {
            ChangeOp.UPSERT -> {
                val exception = (change.payload as? ChangePayload.ScheduleExceptionPayload)?.exception ?: return
                scheduleExceptionStore.write(
                    StoreWriteRequest.of<ScheduleExceptionKey, List<ScheduleException>, Unit>(
                        ScheduleExceptionKey.ByKey(exception.scheduleId, exception.date),
                        listOf(exception)
                    )
                )
            }
            ChangeOp.DELETE -> {
                val parts = change.entityId.split("_", limit = 2)
                if (parts.size != 2) return
                val epochDay = parts[1].toLongOrNull() ?: return
                scheduleExceptionStore.clear(
                    ScheduleExceptionKey.ByKey(
                        scheduleId = parts[0],
                        date = kotlinx.datetime.LocalDate.fromEpochDays(epochDay.toInt())
                    )
                )
            }
        }
    }

    private suspend fun applyPostingChange(change: ChangeMessage) {
        when (change.op) {
            ChangeOp.UPSERT -> {
                val posting = (change.payload as? ChangePayload.PostingPayload)?.posting ?: return
                postingStore.write(
                    StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(
                        PostingKey.ById(posting.id),
                        listOf(posting)
                    )
                )
            }
            ChangeOp.DELETE -> postingStore.clear(PostingKey.ById(change.entityId))
        }
    }

    private suspend fun applyReceiptChange(change: ChangeMessage) {
        when (change.op) {
            ChangeOp.UPSERT -> {
                val receipt = (change.payload as? ChangePayload.ReceiptPayload)?.receipt ?: return
                receiptStore.write(
                    StoreWriteRequest.of<ReceiptKey, List<Receipt>, Unit>(
                        ReceiptKey.ById(receipt.id),
                        listOf(receipt)
                    )
                )
            }
            ChangeOp.DELETE -> receiptStore.clear(ReceiptKey.ById(change.entityId))
        }
    }

}

internal suspend fun ShillingDatabase.latestEntityVersion(
    entityType: EntityType,
    entityId: String
): EntityChangeVersion? =
    changeLogQueries.selectLatestForEntity(
        household_id = LOCAL_CHANGE_LOG_HOUSEHOLD,
        entity_type = entityType.name,
        entity_id = entityId
    ).awaitAsOneOrNull()?.let { row ->
        EntityChangeVersion(
            changeId = row.change_id,
            timestamp = row.timestamp
        )
    }

internal suspend fun ShillingDatabase.recordEntityChange(change: ChangeMessage) {
    changeLogQueries.insert(
        household_id = LOCAL_CHANGE_LOG_HOUSEHOLD,
        change_id = change.id,
        entity_type = change.entityType.name,
        entity_id = change.entityId,
        op = change.op.name,
        timestamp = change.timestamp,
        payload_json = null
    )
}

/**
 * Clears Store5-internal sync metadata (change log + bookkeeping). These are not entity data —
 * they track sync state and retry state. Declared here in the `store` package because the
 * SourceOfTruth and Bookkeeper own these tables; no other layer may touch them directly.
 */
suspend fun ShillingDatabase.clearAllSyncMetadata() {
    bookkeepingQueries.deleteAll()
    changeLogQueries.deleteAll()
}

internal fun ChangeMessage.isStaleComparedTo(current: EntityChangeVersion?): Boolean =
    current != null && (
        timestamp < current.timestamp ||
            (timestamp == current.timestamp && id <= current.changeId)
        )
