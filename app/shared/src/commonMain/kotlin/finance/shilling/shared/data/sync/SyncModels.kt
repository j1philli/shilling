package finance.shilling.shared.data.sync

import finance.shilling.shared.data.*
import kotlinx.serialization.Serializable

@Serializable
enum class EntityType { ACCOUNT, CATEGORY, SCHEDULE, SCHEDULE_EXCEPTION, POSTING, RECEIPT }

@Serializable
enum class ChangeOp { UPSERT, DELETE }

@Serializable
data class ChangeMessage(
    val id: String,
    val entityType: EntityType,
    val op: ChangeOp,
    val entityId: String,
    val timestamp: Long,
    val deviceId: String,
    val payload: ChangePayload? = null
)

@Serializable
sealed class ChangePayload {
    @Serializable
    data class AccountPayload(val account: Account) : ChangePayload()
    @Serializable
    data class CategoryPayload(val category: Category) : ChangePayload()
    @Serializable
    data class SchedulePayload(val schedule: Schedule) : ChangePayload()
    @Serializable
    data class ScheduleExceptionPayload(val exception: ScheduleException) : ChangePayload()
    @Serializable
    data class PostingPayload(val posting: Posting) : ChangePayload()
    @Serializable
    data class ReceiptPayload(val receipt: Receipt) : ChangePayload()
}
