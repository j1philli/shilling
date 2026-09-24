package finance.shilling.shared.data.store

import kotlinx.datetime.LocalDate

// --- Account ---

sealed class AccountKey {
    data object All : AccountKey()
    data class ById(val id: String) : AccountKey()
}

fun AccountKey.toBookkeepingKey(): Pair<String, String> = when (this) {
    AccountKey.All -> "account" to "ALL"
    is AccountKey.ById -> "account" to id
}

// --- Category ---

sealed class CategoryKey {
    data object All : CategoryKey()
    data class ById(val id: String) : CategoryKey()
}

fun CategoryKey.toBookkeepingKey(): Pair<String, String> = when (this) {
    CategoryKey.All -> "category" to "ALL"
    is CategoryKey.ById -> "category" to id
}

// --- Schedule ---

sealed class ScheduleKey {
    data object All : ScheduleKey()
    data class ById(val id: String) : ScheduleKey()
    data class Intersecting(val start: LocalDate, val end: LocalDate) : ScheduleKey()
}

fun ScheduleKey.toBookkeepingKey(): Pair<String, String> = when (this) {
    ScheduleKey.All -> "schedule" to "ALL"
    is ScheduleKey.ById -> "schedule" to id
    is ScheduleKey.Intersecting -> "schedule" to "range_${start}_${end}"
}

// --- Schedule Exception ---

sealed class ScheduleExceptionKey {
    data object All : ScheduleExceptionKey()
    data class ByScheduleId(val scheduleId: String) : ScheduleExceptionKey()
    data class ByKey(val scheduleId: String, val date: LocalDate) : ScheduleExceptionKey()
}

fun ScheduleExceptionKey.toBookkeepingKey(): Pair<String, String> = when (this) {
    ScheduleExceptionKey.All -> "schedule_exception" to "ALL"
    is ScheduleExceptionKey.ByScheduleId -> "schedule_exception" to "schedule_${scheduleId}"
    is ScheduleExceptionKey.ByKey -> "schedule_exception" to "${scheduleId}_${date}"
}

// --- Posting ---

sealed class PostingKey {
    data object All : PostingKey()
    data class ById(val id: String) : PostingKey()
    data class Between(val start: LocalDate, val end: LocalDate) : PostingKey()
    data class Recent(val limit: Long) : PostingKey()
}

fun PostingKey.toBookkeepingKey(): Pair<String, String> = when (this) {
    PostingKey.All -> "posting" to "ALL"
    is PostingKey.ById -> "posting" to id
    is PostingKey.Between -> "posting" to "between_${start}_${end}"
    is PostingKey.Recent -> "posting" to "recent_${limit}"
}

// --- Receipt ---

sealed class ReceiptKey {
    data object All : ReceiptKey()
    data class ById(val id: String) : ReceiptKey()
    data class ByPosting(val postingId: String) : ReceiptKey()
}

fun ReceiptKey.toBookkeepingKey(): Pair<String, String> = when (this) {
    ReceiptKey.All -> "receipt" to "ALL"
    is ReceiptKey.ById -> "receipt" to id
    is ReceiptKey.ByPosting -> "receipt" to postingId
}
