package finance.shilling.shared.presentation

import finance.shilling.shared.data.Category
import finance.shilling.shared.data.CsvPreviewRow
import finance.shilling.shared.data.ScheduleType
import kotlinx.datetime.LocalDate
import kotlin.math.abs

/** Retain the review values, not every raw CSV cell alongside the original file. */
internal data class ImportReviewRow(
    val rowIndex: Int,
    val title: String,
    val parsedDate: LocalDate?,
    val parsedDescription: String?,
    val parsedAmount: Double?,
    val isValid: Boolean
) {
    companion object {
        fun from(row: CsvPreviewRow) = ImportReviewRow(
            row.rowIndex,
            row.parsedDescription?.ifBlank { null } ?: row.raw.joinToString(", "),
            row.parsedDate, row.parsedDescription, row.parsedAmount, row.isValid
        )
    }
}

/** Used by the serialized state projection; keeps only its latest immutable snapshot. */
internal class ImportReviewRows {
    private var source: List<ImportReviewRow>? = null
    private var projected: List<ImportRowUi> = emptyList()
    private var duplicates: Set<Int> = emptySet()
    private var year: Int? = null
    private var currency: String? = null

    fun project(
        rows: List<ImportReviewRow>,
        duplicateRows: Set<Int>,
        categories: Map<String, Category>,
        categoryFor: (Int) -> String?,
        included: (ImportReviewRow) -> Boolean,
        referenceDate: LocalDate
    ): List<ImportRowUi> {
        val currencySymbol = DisplayPreferences.currencySymbol
        val reuse = rows === source && year == referenceDate.year && currency == currencySymbol
        // Dates recur across many transactions. Share their labels within this
        // projection, without a cache that accumulates across files or remaps.
        val dates = mutableMapOf<LocalDate, String>()
        var changed = !reuse
        val next = rows.mapIndexed { index, row ->
            val previous = if (reuse) projected[index] else null
            val category = categoryFor(row.rowIndex)?.let(categories::get)
            val selected = included(row)
            val duplicate = row.rowIndex in duplicateRows
            val result = if (previous != null && duplicate == (row.rowIndex in duplicates)) {
                if (previous.included == selected && previous.categoryId == category?.id &&
                    previous.categoryLabel == (category?.name ?: "Uncategorized") && previous.categoryColor == category?.color
                ) previous else previous.copy(
                    included = selected,
                    categoryId = category?.id,
                    categoryLabel = category?.name ?: "Uncategorized",
                    categoryColor = category?.color
                )
            } else {
                val amount = row.parsedAmount
                val type = if (amount != null && amount > 0) ScheduleType.INCOME else ScheduleType.EXPENSE
                val supporting = when {
                    !row.isValid -> buildList {
                        if (row.parsedDate == null) add("date")
                        if (amount == null) add("amount")
                        if (row.parsedDescription == null) add("description")
                    }.joinToString(prefix = "Can't read ", separator = ", ")
                    else -> {
                        val date = dates.getOrPut(row.parsedDate!!) { formatDate(row.parsedDate, referenceDate) }
                        if (duplicate) "$date · Already imported" else date
                    }
                }
                ImportRowUi(
                    row.rowIndex, row.title, supporting, row.isValid, selected,
                    category?.id, category?.name ?: "Uncategorized", category?.color,
                    amount?.let { formatSigned(type, abs(it)) }, type
                )
            }
            if (result !== previous) changed = true
            result
        }
        source = rows
        duplicates = duplicateRows
        year = referenceDate.year
        currency = currencySymbol
        if (changed) projected = next
        return projected
    }
}
