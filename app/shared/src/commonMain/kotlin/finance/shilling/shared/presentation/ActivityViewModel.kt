package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.PostingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** A selectable history range. */
data class ActivityRange(val months: Int, val label: String)

/** One row; transfers collapse their debit/credit legs into one entry. */
data class ActivityRowUi(
    /** Posting id to open (the debit leg for transfers). */
    val id: String,
    val title: String,
    val supporting: String,
    val type: ScheduleType,
    val amount: String,
    val categoryColor: String?
)

data class ActivitySection(val header: String, val rows: List<ActivityRowUi>)

/** Shown instead of rows; [showActions] offers Add / Import (no data at all, not a search miss). */
data class ActivityEmpty(val title: String, val message: String, val showActions: Boolean)

data class ActivityUiState(
    val ranges: List<ActivityRange> = ACTIVITY_RANGES,
    val selectedRange: ActivityRange = ACTIVITY_RANGES.first(),
    val query: String = "",
    val sections: List<ActivitySection> = emptyList(),
    val empty: ActivityEmpty? = null
)

val ACTIVITY_RANGES = listOf(
    ActivityRange(1, "1M"),
    ActivityRange(3, "3M"),
    ActivityRange(6, "6M"),
    ActivityRange(12, "1Y")
)

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(postingRepository: PostingRepository) : ViewModel() {
    private val today = today()
    private val range = MutableStateFlow(ACTIVITY_RANGES.first())
    private val query = MutableStateFlow("")

    private val postings = range.flatMapLatest { selected ->
        // Include items recorded ahead of their date (e.g. a bill marked paid early).
        postingRepository.watchBetween(
            today.minus(selected.months, DateTimeUnit.MONTH),
            today.plus(60, DateTimeUnit.DAY)
        )
    }

    val state: StateFlow<ActivityUiState> = combine(postings, range, query) { items, selected, q ->
        val rows = mergeTransferLegs(items)
        val needle = q.trim().lowercase()
        val filtered = if (needle.isEmpty()) rows else rows.filter { row ->
            listOfNotNull(row.item.title, row.item.accountName, row.item.categoryName, row.toAccountName)
                .any { it.lowercase().contains(needle) }
        }
        ActivityUiState(
            selectedRange = selected,
            query = q,
            sections = filtered.groupBy { it.item.posting.date }.entries
                .sortedByDescending { it.key }
                .map { (date, dayRows) -> ActivitySection(formatDayHeader(date, today), dayRows.map { it.toUi() }) },
            empty = when {
                filtered.isNotEmpty() -> null
                needle.isNotEmpty() -> ActivityEmpty("No matches", "Nothing matches \"${q.trim()}\" in this period.", showActions = false)
                else -> ActivityEmpty(
                    "No transactions yet",
                    "Record items from Plan, add one-off transactions, or import a bank CSV.",
                    showActions = true
                )
            }
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun setRange(months: Int) {
        range.value = ACTIVITY_RANGES.firstOrNull { it.months == months } ?: return
    }

    fun setQuery(text: String) {
        query.value = text
    }
}

internal data class MergedRow(val item: PostingWithDetails, val type: ScheduleType, val toAccountName: String?)

internal fun MergedRow.toUi(): ActivityRowUi {
    val account = item.accountName ?: "No account"
    val supporting = buildList {
        add(if (type == ScheduleType.TRANSFER) "$account → ${toAccountName ?: "No account"}" else account)
        item.categoryName?.let { add(it) }
        if (item.posting.scheduleId == null) add("One-off")
    }.joinToString(" · ")
    return ActivityRowUi(
        id = item.posting.id,
        title = item.title,
        supporting = supporting,
        type = type,
        amount = formatSigned(type, item.posting.amount),
        categoryColor = item.categoryColor
    )
}

/** Collapses each transfer's debit and credit legs into one row (from the debit's side). */
internal fun mergeTransferLegs(postings: List<PostingWithDetails>): List<MergedRow> {
    val byId = postings.associateBy { it.posting.id }
    // Ad-hoc transfers share pairId, without the scheduled _dr/_cr id suffix.
    // Index once instead of searching the full history for every transfer.
    val adHocByPair = postings.asSequence()
        .filter { it.posting.scheduleId == null && it.posting.pairId != null }
        .groupBy { it.posting.pairId }
    val consumed = mutableSetOf<String>()
    return postings.mapNotNull { item ->
        val p = item.posting
        if (p.id in consumed) return@mapNotNull null
        val partner = when {
            p.id.endsWith("_dr") -> byId[p.id.removeSuffix("_dr") + "_cr"]
            p.id.endsWith("_cr") -> byId[p.id.removeSuffix("_cr") + "_dr"]
            p.scheduleId == null && p.pairId != null ->
                adHocByPair[p.pairId]?.firstOrNull { it.posting.id != p.id }
            else -> null
        }
        if (partner == null) return@mapNotNull MergedRow(item, p.type, null)
        consumed += partner.posting.id
        val (debit, credit) = if (p.type == ScheduleType.EXPENSE) item to partner else partner to item
        MergedRow(debit, ScheduleType.TRANSFER, credit.accountName)
    }
}
