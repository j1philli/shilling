package finance.shilling.shared.presentation
import finance.shilling.shared.data.analytics.ProductAnalytics
import finance.shilling.shared.data.analytics.ProductEvent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.CsvColumnMapping
import finance.shilling.shared.data.CsvImporter
import finance.shilling.shared.data.CsvPreviewRow
import finance.shilling.shared.data.DateFormat
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.math.abs

enum class ImportStage { NO_FILE, UNREADABLE, READY }

data class ColumnChoice(val index: Int, val label: String)

data class DateFormatChoice(val format: DateFormat, val label: String)

data class CategoryChoice(val id: String, val label: String, val color: String?)

/** One CSV row in the review list. */
data class ImportRowUi(
    val rowIndex: Int,
    val title: String,
    /** Date, "· Already imported", or what couldn't be read. */
    val supporting: String,
    val valid: Boolean,
    val included: Boolean,
    val categoryId: String?,
    val categoryLabel: String,
    val categoryColor: String?,
    /** Signed by [type]: positive amounts are income, negative expenses. */
    val amount: String?,
    val type: ScheduleType
)

data class ImportUiState(
    val stage: ImportStage = ImportStage.NO_FILE,
    /** "file.csv · 12 rows". */
    val fileLabel: String? = null,
    val hasHeader: Boolean = true,
    val columns: List<ColumnChoice> = emptyList(),
    val dateColumn: Int = 0,
    val descriptionColumn: Int = 1,
    val amountColumn: Int = 2,
    val dateFormat: DateFormat = DateFormat.ISO,
    val accounts: List<Choice> = emptyList(),
    val accountId: String? = null,
    val categories: List<CategoryChoice> = emptyList(),
    val defaultCategoryId: String? = null,
    /** "3 selected · 1 already imported · 1 unreadable". */
    val reviewSummary: String = "",
    val rows: List<ImportRowUi> = emptyList(),
    val importLabel: String = "Import",
    val importEnabled: Boolean = false,
    val confirm: ConfirmCopy? = null
) {
    val title: String get() = "Import from CSV"
    val subtitle: String get() = "Add transactions from a bank export"
    val chooseLabel: String get() = if (stage == ImportStage.NO_FILE) "Choose CSV file" else "Choose a different file"
    val emptyTitle: String get() = "No file selected"
    val emptyMessage: String get() = "Export transactions from your bank's website as CSV, then choose the file here."
    val unreadableTitle: String get() = "This file is empty or isn't a CSV"
    val headerToggleLabel: String get() = "First row contains column names"
    val amountHint: String get() = "Positive amounts import as income, negative as expenses."
    val accountHint: String? get() = if (accounts.isEmpty()) "Add an account before importing" else null
    val categoryHint: String get() = "You can change the category of individual rows below."
    val noCategoryLabel: String get() = "Uncategorized"
    val dateFormats: List<DateFormatChoice> get() = DateFormat.entries.map { DateFormatChoice(it, dateFormatLabel(it)) }
}

/** CSV import: pick a file, map its columns, review rows (duplicates unchecked), import. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModel(
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    private val postingRepository: PostingRepository,
    private val analytics: ProductAnalytics
) : ViewModel() {
    private data class Input(
        val content: String? = null,
        val fileName: String? = null,
        val headers: List<String> = emptyList(),
        val hasHeader: Boolean = true,
        val dateCol: Int = 0,
        val descCol: Int = 1,
        val amountCol: Int = 2,
        val dateFormat: DateFormat = DateFormat.ISO,
        val accountId: String? = null,
        val defaultCategoryId: String? = null,
        /** Explicit include/exclude toggles; otherwise duplicates start excluded. */
        val included: Map<Int, Boolean> = emptyMap(),
        /** Per-row categories; a present null key means "Uncategorized". */
        val categoryOverrides: Map<Int, String?> = emptyMap()
    ) {
        fun categoryFor(rowIndex: Int): String? =
            if (rowIndex in categoryOverrides) categoryOverrides[rowIndex] else defaultCategoryId
    }

    private val input = MutableStateFlow(Input())

    private val rows = input
        .map { i -> Triple(i.content, CsvColumnMapping(i.dateCol, i.descCol, i.amountCol, i.dateFormat), i.hasHeader) }
        .distinctUntilChanged()
        .map { (content, mapping, hasHeader) -> content?.let { CsvImporter.parseAll(it, mapping, hasHeader) }.orEmpty() }

    // Rows matching an existing transaction in the target account (same date, amount, description).
    private val duplicates = combine(rows, input.map { it.accountId }.distinctUntilChanged()) { r, a -> r to a }
        .mapLatest { (rows, target) ->
            val valid = rows.filter { it.isValid }
            if (valid.isEmpty() || target == null) return@mapLatest emptySet()
            val start = valid.minOf { it.parsedDate!! }
            val end = valid.maxOf { it.parsedDate!! }.plus(1, DateTimeUnit.DAY)
            val existing = postingRepository.getBetween(start, end).filter { it.accountId == target }
            valid.filter { row ->
                existing.any { p ->
                    p.date == row.parsedDate && abs(p.amount - abs(row.parsedAmount!!)) < 0.005 &&
                        p.title?.equals(row.parsedDescription, ignoreCase = true) == true
                }
            }.map { it.rowIndex }.toSet()
        }

    private val accounts = accountRepository.watchAll()
    private val categories = categoryRepository.watchAll()

    val state: StateFlow<ImportUiState> = combine(input, rows, duplicates, accounts, categories) { i, rows, dupes, accounts, categories ->
        val accountId = i.accountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
        // Repair the current value only if it hasn't changed since this snapshot.
        if (accountId != i.accountId) {
            input.update { current -> if (current.accountId == i.accountId) current.copy(accountId = accountId) else current }
        }
        val stage = when {
            i.content == null -> ImportStage.NO_FILE
            i.headers.isEmpty() -> ImportStage.UNREADABLE
            else -> ImportStage.READY
        }
        fun isIncluded(row: CsvPreviewRow) = row.isValid && (i.included[row.rowIndex] ?: (row.rowIndex !in dupes))
        val included = rows.count(::isIncluded)
        val invalid = rows.count { !it.isValid }
        val accountName = accounts.firstOrNull { it.id == accountId }?.name ?: "this account"
        val categoryById = categories.associateBy { it.id }
        ImportUiState(
            stage = stage,
            fileLabel = i.fileName?.let { "$it · ${rows.size} ${if (rows.size == 1) "row" else "rows"}" },
            hasHeader = i.hasHeader,
            columns = i.headers.indices.map { index ->
                val label = if (i.hasHeader) {
                    i.headers[index].ifBlank { null } ?: "Column ${index + 1}"
                } else {
                    "Column ${index + 1} (e.g. ${i.headers[index]})"
                }
                ColumnChoice(index, label)
            },
            dateColumn = i.dateCol,
            descriptionColumn = i.descCol,
            amountColumn = i.amountCol,
            dateFormat = i.dateFormat,
            accounts = accounts.map { Choice(it.id, it.name) },
            accountId = accountId,
            categories = categories.map { CategoryChoice(it.id, it.name, it.color) },
            defaultCategoryId = i.defaultCategoryId,
            reviewSummary = buildList {
                add("$included selected")
                if (dupes.isNotEmpty()) add("${dupes.size} already imported")
                if (invalid > 0) add("$invalid unreadable")
            }.joinToString(" · "),
            rows = rows.map { row ->
                val categoryId = i.categoryFor(row.rowIndex)
                val category = categoryId?.let(categoryById::get)
                val amount = row.parsedAmount
                val type = if (amount != null && amount > 0) ScheduleType.INCOME else ScheduleType.EXPENSE
                ImportRowUi(
                    rowIndex = row.rowIndex,
                    title = row.parsedDescription?.ifBlank { null } ?: row.raw.joinToString(", "),
                    supporting = when {
                        !row.isValid -> buildList {
                            if (row.parsedDate == null) add("date")
                            if (row.parsedAmount == null) add("amount")
                            if (row.parsedDescription == null) add("description")
                        }.joinToString(prefix = "Can't read ", separator = ", ")
                        row.rowIndex in dupes -> "${formatDate(row.parsedDate!!)} · Already imported"
                        else -> formatDate(row.parsedDate!!)
                    },
                    valid = row.isValid,
                    included = isIncluded(row),
                    categoryId = category?.id,
                    categoryLabel = category?.name ?: "Uncategorized",
                    categoryColor = category?.color,
                    amount = amount?.let { formatSigned(type, abs(it)) },
                    type = type
                )
            },
            importLabel = "Import $included ${if (included == 1) "transaction" else "transactions"}",
            importEnabled = included > 0 && accountId != null,
            confirm = ConfirmCopy(
                title = "Import $included transactions?",
                message = "They'll be added to $accountName. You can edit or delete them later in Activity.",
                confirmLabel = "Import",
                destructive = false
            )
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ImportUiState())

    /** Loads a CSV file and guesses its column roles and date format. */
    fun loadFile(fileName: String, bytes: ByteArray) {
        val content = bytes.decodeToString()
        val headers = CsvImporter.parseHeaders(content)
        val dateCol = guessColumn(headers, "date", "posted", fallback = 0)
        input.update {
            it.copy(
                content = content,
                fileName = fileName,
                headers = headers,
                hasHeader = true,
                dateCol = dateCol,
                descCol = guessColumn(headers, "description", "payee", "memo", "name", "details", fallback = 1),
                amountCol = guessColumn(headers, "amount", "value", fallback = 2),
                dateFormat = guessDateFormat(content, dateCol, hasHeader = true),
                included = emptyMap(),
                categoryOverrides = emptyMap()
            )
        }
    }

    fun setHasHeader(value: Boolean) = input.update { it.copy(hasHeader = value) }
    fun setDateColumn(index: Int) = input.update { it.copy(dateCol = index) }
    fun setDescriptionColumn(index: Int) = input.update { it.copy(descCol = index) }
    fun setAmountColumn(index: Int) = input.update { it.copy(amountCol = index) }
    fun setDateFormat(format: DateFormat) = input.update { it.copy(dateFormat = format) }
    fun setAccount(id: String?) = input.update { it.copy(accountId = id) }
    fun setDefaultCategory(id: String?) = input.update { it.copy(defaultCategoryId = id) }
    fun setIncluded(rowIndex: Int, included: Boolean) = input.update { it.copy(included = it.included + (rowIndex to included)) }
    fun setRowCategory(rowIndex: Int, categoryId: String?) =
        input.update { it.copy(categoryOverrides = it.categoryOverrides + (rowIndex to categoryId)) }

    /** Imports the selected rows and resets; returns the confirmation. */
    suspend fun import(): String? {
        val current = state.value
        val target = current.accountId ?: return null
        val i = input.value
        val selected = current.rows.filter { it.included }.map { it.rowIndex }.toSet()
        val content = i.content ?: return null
        val toImport = CsvImporter.parseAll(content, CsvColumnMapping(i.dateCol, i.descCol, i.amountCol, i.dateFormat), i.hasHeader)
            .filter { it.isValid && it.rowIndex in selected }
        if (toImport.isEmpty()) return null
        toImport.groupBy { i.categoryFor(it.rowIndex) }.forEach { (categoryId, group) ->
            postingRepository.bulkImport(
                items = group.map { Triple(it.parsedDescription!!, it.parsedAmount!!, it.parsedDate!!) },
                accountId = target,
                categoryId = categoryId
            )
        }
        val accountName = current.accounts.firstOrNull { it.id == target }?.label ?: "this account"
        input.value = Input(accountId = target)
        analytics.captureAsync(ProductEvent.CSV_IMPORT_COMPLETED)
        return "Imported ${toImport.size} transactions into $accountName"
    }
}

private fun dateFormatLabel(format: DateFormat): String = when (format) {
    DateFormat.ISO -> "2026-01-31 (YYYY-MM-DD)"
    DateFormat.US_SLASH -> "01/31/2026 (MM/DD/YYYY)"
    DateFormat.US_DASH -> "01-31-2026 (MM-DD-YYYY)"
    DateFormat.EU_SLASH -> "31/01/2026 (DD/MM/YYYY)"
    DateFormat.EU_DASH -> "31-01-2026 (DD-MM-YYYY)"
}

/** Best-effort guess of column roles from common bank export headers. */
private fun guessColumn(headers: List<String>, vararg names: String, fallback: Int): Int =
    headers.indexOfFirst { header -> names.any { header.trim().lowercase().contains(it) } }
        .takeIf { it >= 0 } ?: fallback.coerceAtMost((headers.size - 1).coerceAtLeast(0))

/** Picks the first date format that parses most rows of the date column. */
private fun guessDateFormat(content: String, dateCol: Int, hasHeader: Boolean): DateFormat =
    DateFormat.entries.maxBy { format ->
        CsvImporter.preview(content, CsvColumnMapping(dateCol, dateCol, dateCol, format), hasHeader, maxRows = 20)
            .count { it.parsedDate != null }
    }
