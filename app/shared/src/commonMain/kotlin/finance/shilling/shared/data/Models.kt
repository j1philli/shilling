package finance.shilling.shared.data

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleType { EXPENSE, INCOME, TRANSFER }

@Serializable
data class Category(
    val id: String,
    val name: String,
    val color: String? = null
)

@Serializable
enum class Frequency {
    ONCE,
    DAILY,
    WEEKLY,
    BI_WEEKLY,
    MONTHLY_BY_DAY,
    MONTHLY_BY_NTH_WEEKDAY,
    SEMI_MONTHLY,
    YEARLY
}

@Serializable
data class Account(
    val id: String,
    val name: String,
    val balance: Double
)

@Serializable
data class Schedule(
    val id: String,
    val title: String,
    val amount: Double,
    val type: ScheduleType,
    val accountId: String,
    val counterAccountId: String? = null,
    val categoryId: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val freq: Frequency,
    val interval: Int = 1,
    val byDayMask: Int? = null,
    val byMonthDay: Int? = null,
    val nthWeekday: Int? = null,
    val lastDayFlag: Boolean = false,
    val autoPay: Boolean = false,
    val notes: String? = null
)

@Serializable
data class ScheduleException(
    val scheduleId: String,
    val date: LocalDate,
    val skip: Boolean = false,
    val overrideAmount: Double? = null,
    val overrideAccountId: String? = null,
    val overrideCounterAccountId: String? = null
)

@Serializable
data class Posting(
    val id: String,
    val scheduleId: String?,
    val type: ScheduleType,
    val accountId: String,
    val date: LocalDate,
    val amount: Double,
    val pairId: String? = null,
    val title: String? = null,
    val categoryId: String? = null
)

@Serializable
data class ScheduledTx(
    val scheduleId: String,
    val title: String,
    val date: LocalDate,
    val amount: Double,
    val type: ScheduleType,
    val accountId: String,
    val counterAccountId: String? = null,
    val categoryId: String? = null,
    val autoPay: Boolean = false,
    val pairId: String? = null
)

@Serializable
data class ScheduledTxWithAccount(
    val tx: ScheduledTx,
    val account: Account?,
    val counterAccount: Account?,
    val category: Category?,
    val posted: Boolean
)

@Serializable
data class PostingWithDetails(
    val posting: Posting,
    val title: String,
    val accountName: String?,
    val categoryName: String?,
    val categoryColor: String?
)

@Serializable
data class Receipt(
    val id: String,
    val postingId: String? = null,
    val filePath: String,
    val originalName: String,
    val addedAt: Long,
    val receiptDate: Long? = null,
    val amount: Double? = null,
    val notes: String? = null
)

@Serializable
data class ReceiptWithPosting(
    val receipt: Receipt,
    val postingTitle: String?,
    val postingDate: String?
)

@Serializable
data class CashPoint(
    val date: LocalDate,
    val balance: Double
)

@Serializable
data class BudgetLine(
    val scheduleId: String,
    val title: String,
    val type: ScheduleType,
    val accountId: String,
    val counterAccountId: String? = null,
    val category: Category?,
    val occurrences: Int,
    val amountPerOccurrence: Double,
    val totalAmount: Double
)

@Serializable
data class CategoryTotal(
    val category: Category?,
    val total: Double
)

@Serializable
data class BudgetSummary(
    val monthStart: LocalDate,
    val monthEnd: LocalDate,
    val lines: List<BudgetLine>,
    val categoryTotals: List<CategoryTotal>,
    val netChange: Double
) {
    companion object {
        fun empty(monthStart: LocalDate, monthEnd: LocalDate) = BudgetSummary(
            monthStart = monthStart,
            monthEnd = monthEnd,
            lines = emptyList(),
            categoryTotals = emptyList(),
            netChange = 0.0
        )
    }
}
