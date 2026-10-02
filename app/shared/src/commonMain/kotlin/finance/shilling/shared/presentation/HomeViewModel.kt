package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import finance.shilling.shared.data.BudgetSummary
import finance.shilling.shared.data.CategoryTotal
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTxWithAccount
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock

/** Where a Home tile leads; each UI maps it to its own navigation. */
enum class HomeDestination { ACCOUNTS, WEEK, MONTH, ACTIVITY, SCHEDULES, RECEIPTS, CATEGORIES }

/** Everything Home shows, including its copy, so every UI says the same thing. */
data class HomeUiState(
    val today: LocalDate,
    val greeting: String,
    val totalBalance: Double = 0.0,
    val accountCount: Int = 0,
    val upcoming: List<ScheduledTxWithAccount> = emptyList(),
    val budget: BudgetSummary = BudgetSummary.empty(today.startOfMonth(), today.startOfMonth().plus(1, DateTimeUnit.MONTH)),
    /** Latest few transactions, transfer legs merged (as on Activity). */
    val recent: List<ActivityRowUi> = emptyList(),
    val scheduleBreakdown: Map<ScheduleType, Int> = emptyMap(),
    val receiptCount: Int = 0,
    val unattachedReceipts: Int = 0,
    val categoryCount: Int = 0,
    val topCategories: List<CategoryTotal> = emptyList()
) {
    val dateLabel: String get() = "${today.dayOfWeek.fullLabel}, ${today.month.fullLabel} ${today.day}, ${today.year}"

    val balanceValue: String get() = formatCurrency(totalBalance)
    val balanceCaption: String
        get() = if (accountCount == 0) "Add an account to get started"
        else "$accountCount account${if (accountCount == 1) "" else "s"}"

    val unpostedCount: Int get() = upcoming.count { !it.posted }
    val weeklyValue: String get() = if (upcoming.isEmpty()) "Clear" else "${upcoming.size} due"
    val weeklyCaption: String
        get() = when {
            upcoming.isEmpty() -> "Nothing due this week"
            unpostedCount == 0 -> "All recorded"
            unpostedCount == 1 -> "1 to record"
            else -> "$unpostedCount to record"
        }

    val monthNet: Double get() = budget.netChange
    val monthValue: String get() = formatCurrency(budget.netChange)
    val monthCaption: String
        get() {
            val count = budget.lines.size
            if (count == 0) return "Nothing scheduled this month"
            val net = if (budget.netChange >= 0) "Surplus" else "Deficit"
            return "$net · $count scheduled ${if (count == 1) "item" else "items"}"
        }

    val activityValue: String get() = if (recent.isEmpty()) "No activity yet" else "Recent activity"
    val activityCaption: String? get() = if (recent.isEmpty()) "Transactions appear here once recorded" else null

    val scheduleCount: Int get() = scheduleBreakdown.values.sum()
    val scheduleValue: String get() = if (scheduleCount == 0) "None yet" else "$scheduleCount active"
    val scheduleCaption: String
        get() {
            if (scheduleBreakdown.isEmpty()) return "Set up bills, paychecks and transfers"
            return ScheduleType.entries.mapNotNull { type ->
                scheduleBreakdown[type]?.let { count -> "$count ${type.pluralLabel.lowercase()}" }
            }.joinToString(" · ")
        }

    val receiptsValue: String
        get() = when {
            receiptCount == 0 -> "None yet"
            unattachedReceipts == 0 -> "All attached"
            else -> "$unattachedReceipts to attach"
        }
    val receiptsCaption: String
        get() = when {
            receiptCount == 0 -> "Keep receipts with your transactions"
            unattachedReceipts == 0 -> "Nothing waiting"
            else -> "Link them to transactions"
        }

    val categoriesValue: String get() = if (categoryCount == 0) "None yet" else "$categoryCount total"
    val categoriesCaption: String
        get() = if (topCategories.isEmpty()) "Label schedules and transactions" else "Largest this month"
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    postingRepository: PostingRepository,
    receiptRepository: ReceiptRepository,
    scheduleRepository: ScheduleRepository,
    windowUseCase: ComputeWindowUseCase,
    budgetUseCase: ComputeBudgetUseCase
) : ViewModel() {
    private val today = today()
    private val initial = HomeUiState(today = today, greeting = greetingFor(currentHour()))

    private val upcoming = DisplayPreferences.state
        .map { it.weekStart }
        .distinctUntilChanged()
        .flatMapLatest { windowUseCase.watchUpcomingWindow(it) }

    private val lists = combine(
        accountRepository.watchAll(),
        categoryRepository.watchAll(),
        scheduleRepository.watchAll(),
        receiptRepository.watchCounts(),
        postingRepository.watchRecentBetween(today.minus(1, DateTimeUnit.YEAR), today.plus(1, DateTimeUnit.DAY), limit = 3)
    ) { accounts, categories, schedules, receipts, recent ->
        initial.copy(
            totalBalance = accounts.sumOf { it.balance },
            accountCount = accounts.size,
            categoryCount = categories.size,
            scheduleBreakdown = schedules.groupingBy { it.type }.eachCount(),
            receiptCount = receipts.total,
            unattachedReceipts = receipts.unattached,
            recent = mergeTransferLegs(recent).take(3).map { it.toUi() }
        )
    }

    val state: StateFlow<HomeUiState> = combine(
        lists,
        upcoming,
        budgetUseCase.watchBudget(today.startOfMonth())
    ) { base, upcoming, budget ->
        base.copy(
            upcoming = upcoming,
            budget = budget,
            topCategories = budget.categoryTotals
                .filter { it.total != 0.0 }
                .sortedByDescending { abs(it.total) }
                .take(3)
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}

private fun currentHour(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

fun greetingFor(hour: Int): String = when (hour) {
    in 0..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}
