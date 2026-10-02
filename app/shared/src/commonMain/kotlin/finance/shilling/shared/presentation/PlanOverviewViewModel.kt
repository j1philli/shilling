package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTxWithAccount
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.abs

enum class PlanGrouping(val label: String) { BY_DAY("By day"), BY_CATEGORY("By category") }

/** One occurrence row (By day). */
data class OccurrenceUi(
    val key: String,
    val title: String,
    val supporting: String,
    val type: ScheduleType,
    val amount: String,
    val categoryColor: String?,
    val posted: Boolean,
    /** Posting to open when [posted]. */
    val postingId: String?,
    /** A posted scheduled occurrence can be marked not paid again. */
    val canUnmark: Boolean,
    /** "Mark paid" / "Mark received" / "Mark moved". */
    val markLabel: String,
    /** "Paid" / "Received" / "Moved". */
    val postedLabel: String,
    val unmarkLabel: String
)

data class PlanDayUi(val header: String, val rows: List<OccurrenceUi>)

/** One schedule's (or one-off's) occurrences in the period (By category). */
data class PlanLineUi(
    val key: String,
    val title: String,
    val supporting: String,
    val type: ScheduleType,
    val amount: String,
    /** Opens the schedule; otherwise [postingId] opens the one-off transaction. */
    val isSchedule: Boolean,
    val postingId: String?
)

data class PlanCategoryUi(
    val key: String,
    val name: String,
    val color: String?,
    val countLabel: String,
    val totalType: ScheduleType,
    val total: String,
    val expanded: Boolean,
    val lines: List<PlanLineUi>
)

data class PlanSummaryUi(
    val income: String,
    val expenses: String,
    val netLabel: String,
    val net: String,
    val netPositive: Boolean
)

data class PlanOverviewUiState(
    val period: PlanPeriod = PlanPeriod.WEEK,
    val periods: List<PlanPeriod> = PlanPeriod.entries,
    val grouping: PlanGrouping = PlanGrouping.BY_DAY,
    val groupings: List<PlanGrouping> = PlanGrouping.entries,
    val rangeLabel: String = "",
    val previousLabel: String = "",
    val nextLabel: String = "",
    /** "This week" / "This month" when away from the current period. */
    val resetLabel: String? = null,
    /** Start of the shown range (date picker default). */
    val rangeStartEpochDay: Long = 0,
    /** "3 to record" / "Everything recorded" / null when empty. */
    val subtitle: String? = null,
    val summary: PlanSummaryUi = PlanSummaryUi("", "", "Surplus", "", true),
    val days: List<PlanDayUi> = emptyList(),
    val categories: List<PlanCategoryUi> = emptyList(),
    val emptyTitle: String? = null,
    val emptyMessage: String = "Bills, paychecks and transfers from your schedules appear here."
)

/** Copy + default for the "Change amount" dialog. */
data class ChangeAmountPrompt(val title: String, val message: String, val initialText: String)

@OptIn(ExperimentalCoroutinesApi::class)
class PlanOverviewViewModel(
    private val windowUseCase: ComputeWindowUseCase,
    private val actions: OccurrenceActions
) : ViewModel() {
    private val today = today()
    private val period = MutableStateFlow(PlanPeriod.WEEK)
    private val anchor = MutableStateFlow(today)
    private val grouping = MutableStateFlow(PlanGrouping.BY_DAY)
    private val expanded = MutableStateFlow(emptySet<String>())
    // Projection runs on Default; actions can read the latest immutable snapshot on Main.
    private val itemsByKey = MutableStateFlow<Map<String, ScheduledTxWithAccount>>(emptyMap())

    private data class Window(val period: PlanPeriod, val range: Pair<LocalDate, LocalDate>, val current: Pair<LocalDate, LocalDate>)

    private val window = combine(
        period,
        anchor,
        DisplayPreferences.state.map { it.weekStart }.distinctUntilChanged()
    ) { p, a, weekStart ->
        fun rangeFor(date: LocalDate) = when (p) {
            PlanPeriod.WEEK -> windowUseCase.computeWindow(date, weekStart)
            PlanPeriod.MONTH -> date.startOfMonth().let { it to it.plus(1, DateTimeUnit.MONTH) }
        }
        Window(p, rangeFor(a), rangeFor(today))
    }.distinctUntilChanged()

    val state: StateFlow<PlanOverviewUiState> = window.flatMapLatest { w ->
        combine(windowUseCase.watchWindow(w.range.first, w.range.second), grouping, expanded) { items, g, open ->
            itemsByKey.value = items.associateBy(::occurrenceKey)
            buildState(w, items, g, open)
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanOverviewUiState())

    fun setPeriod(value: PlanPeriod) {
        period.value = value
    }

    fun setGrouping(value: PlanGrouping) {
        grouping.value = value
    }

    /** Previous (-1) or next (+1) week/month. */
    fun step(direction: Int) {
        anchor.value = when (period.value) {
            PlanPeriod.WEEK -> anchor.value.plus(7 * direction, DateTimeUnit.DAY)
            PlanPeriod.MONTH -> anchor.value.startOfMonth().plus(direction, DateTimeUnit.MONTH)
        }
    }

    fun resetToCurrent() {
        anchor.value = today
    }

    fun jumpTo(epochDay: Long) {
        anchor.value = LocalDate.fromEpochDays(epochDay)
    }

    fun toggleCategory(key: String) {
        expanded.value = if (key in expanded.value) expanded.value - key else expanded.value + key
    }

    suspend fun markPosted(key: String): Undoable? = itemsByKey.value[key]?.let { actions.markPosted(it) }
    suspend fun unmark(key: String): Undoable? = itemsByKey.value[key]?.let { actions.unmarkPosted(it) }
    suspend fun skip(key: String): Undoable? = itemsByKey.value[key]?.let { actions.skip(it) }

    fun changeAmountPrompt(key: String): ChangeAmountPrompt? = itemsByKey.value[key]?.let { item ->
        ChangeAmountPrompt(
            title = "Change amount",
            message = "Applies only to ${item.tx.title} on ${formatDate(item.tx.date)}. The schedule is unchanged.",
            initialText = formatAmountInput(item.tx.amount)
        )
    }

    /** Null when [text] isn't a valid amount (or the item is gone). */
    suspend fun changeAmount(key: String, text: String): Undoable? {
        val item = itemsByKey.value[key] ?: return null
        val amount = parseAmountInput(text) ?: return null
        return actions.changeAmount(item, amount)
    }

    private fun buildState(
        w: Window,
        items: List<ScheduledTxWithAccount>,
        g: PlanGrouping,
        open: Set<String>
    ): PlanOverviewUiState {
        val label = w.period.label.lowercase()
        val toRecord = items.count { !it.posted }
        val income = items.filter { it.tx.type == ScheduleType.INCOME }.sumOf { it.tx.amount }
        val expenses = items.filter { it.tx.type == ScheduleType.EXPENSE }.sumOf { it.tx.amount }
        val net = income - expenses
        return PlanOverviewUiState(
            period = w.period,
            grouping = g,
            rangeLabel = when (w.period) {
                PlanPeriod.WEEK -> formatDateRange(w.range.first, w.range.second.minus(1, DateTimeUnit.DAY))
                PlanPeriod.MONTH -> formatMonthYear(w.range.first)
            },
            previousLabel = "Previous $label",
            nextLabel = "Next $label",
            resetLabel = if (w.range != w.current) "This $label" else null,
            rangeStartEpochDay = w.range.first.toEpochDays(),
            subtitle = when {
                items.isEmpty() -> null
                toRecord == 0 -> "Everything recorded"
                else -> "$toRecord to record"
            },
            summary = PlanSummaryUi(
                income = formatCurrency(income),
                expenses = formatCurrency(expenses),
                netLabel = if (net >= 0) "Surplus" else "Deficit",
                net = formatCurrency(abs(net)),
                netPositive = net >= 0
            ),
            days = if (g == PlanGrouping.BY_DAY) items.groupBy { it.tx.date }.entries.sortedBy { it.key }.map { (date, dayItems) ->
                PlanDayUi(formatDayHeader(date, today), dayItems.map { it.toOccurrenceUi() })
            } else emptyList(),
            categories = if (g == PlanGrouping.BY_CATEGORY) groupByCategory(items, open, today) else emptyList(),
            emptyTitle = if (items.isEmpty()) "Nothing scheduled this $label" else null
        )
    }
}

private fun occurrenceKey(item: ScheduledTxWithAccount): String =
    "${item.tx.scheduleId}-${item.tx.date}-${item.postingId}"

private fun ScheduledTxWithAccount.toOccurrenceUi(): OccurrenceUi {
    val accountLabel = account?.name ?: "No account"
    val supporting = buildList {
        add(if (tx.type == ScheduleType.TRANSFER) "$accountLabel → ${counterAccount?.name ?: "No account"}" else accountLabel)
        category?.let { add(it.name) }
        if (posted) add(tx.type.postedLabel) else if (tx.autoPay) add("Auto-pay")
    }.joinToString(" · ")
    return OccurrenceUi(
        key = occurrenceKey(this),
        title = tx.title,
        supporting = supporting,
        type = tx.type,
        amount = formatSigned(tx.type, tx.amount),
        categoryColor = category?.color,
        posted = posted,
        postingId = postingId,
        canUnmark = posted && isScheduledOccurrence,
        markLabel = tx.type.markActionLabel,
        postedLabel = tx.type.postedLabel,
        unmarkLabel = "Mark not ${tx.type.postedLabel.lowercase()}"
    )
}

private fun groupByCategory(items: List<ScheduledTxWithAccount>, open: Set<String>, today: LocalDate): List<PlanCategoryUi> =
    items.groupBy { it.category?.id }.map { (id, group) ->
        val key = id ?: "uncategorized"
        val schedules = group.groupBy { it.tx.scheduleId }
        val lines = if (key in open) schedules.map { (scheduleId, occurrences) ->
            val first = occurrences.first()
            val count = occurrences.size
            val total = occurrences.sumOf { it.tx.amount }
            val sameAmount = occurrences.map { it.tx.amount }.distinct().size == 1
            val recorded = occurrences.count { it.posted }
            val supporting = buildList {
                add(
                    when {
                        count == 1 -> formatDate(first.tx.date, today)
                        sameAmount -> "$count × ${formatCurrency(first.tx.amount)}"
                        else -> "$count times"
                    }
                )
                first.account?.name?.let { add(it) }
                if (recorded > 0) {
                    add(
                        if (recorded == count) first.tx.type.postedLabel
                        else "$recorded of $count ${first.tx.type.postedLabel.lowercase()}"
                    )
                }
            }.joinToString(" · ")
            PlanLineUi(
                key = scheduleId,
                title = first.tx.title,
                supporting = supporting,
                type = first.tx.type,
                amount = formatSigned(first.tx.type, total),
                isSchedule = first.isScheduledOccurrence,
                postingId = first.postingId
            ) to first.tx.type.ordinal
        }.sortedWith(compareBy<Pair<PlanLineUi, Int>> { it.second }.thenBy { it.first.title.lowercase() }).map { it.first }
        else emptyList()
        val income = group.filter { it.tx.type == ScheduleType.INCOME }.sumOf { it.tx.amount }
        val expense = group.filter { it.tx.type == ScheduleType.EXPENSE }.sumOf { it.tx.amount }
        val onlyTransfers = group.all { it.tx.type == ScheduleType.TRANSFER }
        val net = income - expense
        val totalType = when {
            onlyTransfers -> ScheduleType.TRANSFER
            net >= 0 -> ScheduleType.INCOME
            else -> ScheduleType.EXPENSE
        }
        val total = if (onlyTransfers) group.sumOf { it.tx.amount } else abs(net)
        val category = group.first().category
        PlanCategoryUi(
            key = key,
            name = category?.name ?: "Uncategorized",
            color = category?.color,
            countLabel = "${schedules.size} ${if (schedules.size == 1) "item" else "items"}",
            totalType = totalType,
            total = formatSigned(totalType, total),
            expanded = key in open,
            lines = lines
        )
    }.sortedWith(compareBy<PlanCategoryUi> { it.key == "uncategorized" }.thenBy { it.name.lowercase() })
