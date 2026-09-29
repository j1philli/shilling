package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.ReceiptWithPosting
import finance.shilling.shared.data.store.ReceiptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

enum class ReceiptFilter(val label: String) {
    ALL("All"), UNATTACHED("Not attached"), ATTACHED("Attached")
}

data class ReceiptRowUi(
    val id: String,
    val title: String,
    val supporting: String,
    /** Formatted amount, when the receipt has one. */
    val amount: String?
)

/** Shown instead of rows; [showAdd] offers "Add receipt" (nothing at all, not a filter miss). */
data class ReceiptsEmpty(val title: String, val message: String?, val showAdd: Boolean)

data class ReceiptsUiState(
    val filter: ReceiptFilter = ReceiptFilter.ALL,
    val filters: List<ReceiptFilter> = ReceiptFilter.entries,
    /** "3 not attached", or null when everything is attached. */
    val subtitle: String? = null,
    val rows: List<ReceiptRowUi> = emptyList(),
    val empty: ReceiptsEmpty? = null
)

class ReceiptsViewModel(receiptRepository: ReceiptRepository) : ViewModel() {
    private val filter = MutableStateFlow(ReceiptFilter.ALL)

    val state: StateFlow<ReceiptsUiState> = combine(receiptRepository.watchAll(), filter) { receipts, selected ->
        val filtered = when (selected) {
            ReceiptFilter.ALL -> receipts
            ReceiptFilter.UNATTACHED -> receipts.filter { it.receipt.postingId == null }
            ReceiptFilter.ATTACHED -> receipts.filter { it.receipt.postingId != null }
        }.sortedByDescending { it.receipt.addedAt }
        ReceiptsUiState(
            filter = selected,
            subtitle = receipts.count { it.receipt.postingId == null }.takeIf { it > 0 }?.let { "$it not attached" },
            rows = filtered.map { it.toRow() },
            empty = if (filtered.isNotEmpty()) null else when (selected) {
                ReceiptFilter.ALL -> ReceiptsEmpty(
                    "No receipts yet",
                    "Keep receipts here and attach them to transactions when they post.",
                    showAdd = true
                )
                ReceiptFilter.UNATTACHED -> ReceiptsEmpty("All receipts are attached", null, showAdd = false)
                ReceiptFilter.ATTACHED -> ReceiptsEmpty("No attached receipts", null, showAdd = false)
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptsUiState())

    fun setFilter(value: ReceiptFilter) {
        filter.value = value
    }
}

private fun ReceiptWithPosting.toRow(): ReceiptRowUi {
    val added = Instant.fromEpochMilliseconds(receipt.addedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date
    val supporting = buildList {
        add(receipt.receiptDate?.let { formatDate(LocalDate.fromEpochDays(it)) } ?: "Added ${formatDate(added)}")
        add(postingTitle?.let { "Attached to $it" } ?: "Not attached")
    }.joinToString(" · ")
    return ReceiptRowUi(
        id = receipt.id,
        title = receipt.originalName,
        supporting = supporting,
        amount = receipt.amount?.let(::formatCurrency)
    )
}
