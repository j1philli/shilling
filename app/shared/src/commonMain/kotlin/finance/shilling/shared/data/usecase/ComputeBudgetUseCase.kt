package finance.shilling.shared.data.usecase

import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.ScheduleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

class ComputeBudgetUseCase(
    private val notifier: ChangeNotifier,
    private val categoryRepository: CategoryRepository,
    private val scheduleRepository: ScheduleRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchBudget(monthStart: LocalDate): Flow<BudgetSummary> =
        notifier.version.flatMapLatest {
            flow { emit(computeMonthlyBudget(monthStart)) }
        }

    suspend fun computeMonthlyBudget(monthStart: LocalDate): BudgetSummary {
        val monthEnd = monthStart.plus(1, DateTimeUnit.MONTH)
        val schedules = scheduleRepository.getIntersecting(monthStart, monthEnd)
        if (schedules.isEmpty()) return BudgetSummary.empty(monthStart, monthEnd)
        val categories = categoryRepository.getAll().associateBy { it.id }
        val scheduleIds = schedules.map { it.id }
        val exceptions = scheduleRepository.getExceptions(scheduleIds)
        val lines = mutableListOf<BudgetLine>()
        schedules.forEach { schedule ->
            val ex = exceptions[schedule.id]?.associateBy { it.date } ?: emptyMap()
            val occurrences = generateOccurrences(schedule, monthStart, monthEnd, ex, emptySet())
            if (occurrences.isEmpty()) return@forEach
            val totalAmount = occurrences.sumOf { it.amount }
            val occurrencesCount = occurrences.size
            lines += BudgetLine(
                scheduleId = schedule.id,
                title = schedule.title,
                type = schedule.type,
                accountId = schedule.accountId,
                counterAccountId = schedule.counterAccountId,
                category = schedule.categoryId?.let { categories[it] },
                occurrences = occurrencesCount,
                amountPerOccurrence = schedule.amount,
                totalAmount = totalAmount
            )
        }

        val categoryTotals = lines.groupBy { it.category?.id }
            .map { (_, group) ->
                val category = group.first().category
                val total = group.sumOf { line ->
                    when (line.type) {
                        ScheduleType.INCOME -> line.totalAmount
                        ScheduleType.EXPENSE -> -line.totalAmount
                        ScheduleType.TRANSFER -> line.totalAmount
                    }
                }
                CategoryTotal(category = category, total = total)
            }
            .sortedBy { it.category?.name ?: "" }

        val netChange = lines.sumOf { line ->
            when (line.type) {
                ScheduleType.INCOME -> line.totalAmount
                ScheduleType.EXPENSE -> -line.totalAmount
                ScheduleType.TRANSFER -> 0.0
            }
        }

        val sortedLines = lines.sortedWith(
            compareBy<BudgetLine> { lineOrder(it.type) }
                .thenBy { it.title }
        )

        return BudgetSummary(
            monthStart = monthStart,
            monthEnd = monthEnd,
            lines = sortedLines,
            categoryTotals = categoryTotals,
            netChange = netChange
        )
    }

    private fun lineOrder(type: ScheduleType): Int = when (type) {
        ScheduleType.EXPENSE -> 0
        ScheduleType.TRANSFER -> 1
        ScheduleType.INCOME -> 2
    }
}
