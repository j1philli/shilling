package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Upload
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.CsvColumnMapping
import finance.shilling.shared.data.CsvImporter
import finance.shilling.shared.data.CsvPreviewRow
import finance.shilling.shared.data.DateFormat
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.koin.compose.koinInject
import kotlin.math.abs

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

@Composable
fun ImportView(onClose: () -> Unit) {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val accounts by remember { accountRepo.watchAll() }.collectAsState(initial = emptyList())
    val categories by remember { categoryRepo.watchAll() }.collectAsState(initial = emptyList())

    var csvContent by remember { mutableStateOf<String?>(null) }
    var csvFileName by remember { mutableStateOf<String?>(null) }
    var headers by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasHeader by remember { mutableStateOf(true) }
    var dateCol by remember { mutableStateOf(0) }
    var descCol by remember { mutableStateOf(1) }
    var amountCol by remember { mutableStateOf(2) }
    var dateFormat by remember { mutableStateOf(DateFormat.ISO) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var defaultCategoryId by remember { mutableStateOf<String?>(null) }
    val excluded = remember { mutableStateMapOf<Int, Boolean>() }
    val categoryOverrides = remember { mutableStateMapOf<Int, String?>() }
    var duplicates by remember { mutableStateOf(emptySet<Int>()) }
    var confirmImport by remember { mutableStateOf(false) }

    LaunchedEffect(accounts) {
        if (accountId == null || accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id
    }

    val fileLauncher = rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("csv"))) { file ->
        if (file != null) {
            scope.launch {
                val content = file.readBytes().decodeToString()
                val parsedHeaders = CsvImporter.parseHeaders(content)
                csvContent = content
                csvFileName = file.name
                headers = parsedHeaders
                hasHeader = true
                dateCol = guessColumn(parsedHeaders, "date", "posted", fallback = 0)
                descCol = guessColumn(parsedHeaders, "description", "payee", "memo", "name", "details", fallback = 1)
                amountCol = guessColumn(parsedHeaders, "amount", "value", fallback = 2)
                dateFormat = guessDateFormat(content, dateCol, hasHeader = true)
                excluded.clear()
                categoryOverrides.clear()
            }
        }
    }

    val rows = remember(csvContent, dateCol, descCol, amountCol, dateFormat, hasHeader) {
        csvContent?.let { CsvImporter.parseAll(it, CsvColumnMapping(dateCol, descCol, amountCol, dateFormat), hasHeader) }
            .orEmpty()
    }

    // Flag rows that match an existing transaction in the target account (same date, amount, description).
    LaunchedEffect(rows, accountId) {
        val valid = rows.filter { it.isValid }
        val target = accountId
        if (valid.isEmpty() || target == null) {
            duplicates = emptySet()
            return@LaunchedEffect
        }
        val start = valid.minOf { it.parsedDate!! }
        val end = valid.maxOf { it.parsedDate!! }.plus(1, DateTimeUnit.DAY)
        val existing = postingRepo.getBetween(start, end).filter { it.accountId == target }
        duplicates = valid.filter { row ->
            existing.any { p ->
                p.date == row.parsedDate && abs(p.amount - abs(row.parsedAmount!!)) < 0.005 &&
                    p.title?.equals(row.parsedDescription, ignoreCase = true) == true
            }
        }.map { it.rowIndex }.toSet()
    }

    // Duplicates start unchecked; an explicit toggle always wins.
    fun isIncluded(row: CsvPreviewRow) =
        row.isValid && (excluded[row.rowIndex]?.not() ?: (row.rowIndex !in duplicates))
    val included = rows.filter { isIncluded(it) }
    val invalidCount = rows.count { !it.isValid }

    ScreenScaffold(
        title = "Import from CSV",
        subtitle = "Add transactions from a bank export",
        navIcon = ScreenNavIcon.BACK,
        onNavIcon = onClose,
        maxContentWidth = 840.dp
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "file") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { fileLauncher.launch() }) {
                        Icon(MaterialIcons.Filled.Upload, contentDescription = null)
                        Text(if (csvContent == null) "Choose CSV file" else "Choose a different file", modifier = Modifier.padding(start = Spacing.sm))
                    }
                    csvFileName?.let {
                        Text(
                            "$it · ${rows.size} ${if (rows.size == 1) "row" else "rows"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (csvContent == null) {
                item(key = "empty") {
                    EmptyState(
                        title = "No file selected",
                        message = "Export transactions from your bank's website as CSV, then choose the file here."
                    )
                }
                return@LazyColumn
            }
            if (headers.isEmpty()) {
                item(key = "unreadable") { EmptyState(title = "This file is empty or isn't a CSV") }
                return@LazyColumn
            }

            item(key = "columns") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ListSectionHeader("Columns")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Switch(checked = hasHeader, onCheckedChange = { hasHeader = it })
                        Text("First row contains column names", style = MaterialTheme.typography.bodyLarge)
                    }
                    val columnLabel: (Int) -> String = { index ->
                        if (hasHeader) headers.getOrNull(index)?.ifBlank { null } ?: "Column ${index + 1}"
                        else "Column ${index + 1} (e.g. ${headers.getOrNull(index).orEmpty()})"
                    }
                    DropdownField("Date", dateCol, headers.indices.toList(), columnLabel, { it?.let { c -> dateCol = c } })
                    DropdownField("Date format", dateFormat, DateFormat.entries, ::dateFormatLabel, { it?.let { f -> dateFormat = f } })
                    DropdownField("Description", descCol, headers.indices.toList(), columnLabel, { it?.let { c -> descCol = c } })
                    DropdownField(
                        "Amount", amountCol, headers.indices.toList(), columnLabel, { it?.let { c -> amountCol = c } },
                        supportingText = "Positive amounts import as income, negative as expenses."
                    )
                    ListSectionHeader("Import into")
                    DropdownField(
                        label = "Account",
                        selected = accounts.firstOrNull { it.id == accountId },
                        options = accounts,
                        optionLabel = { it.name },
                        onSelect = { accountId = it?.id },
                        enabled = accounts.isNotEmpty(),
                        isError = accounts.isEmpty(),
                        supportingText = if (accounts.isEmpty()) "Add an account before importing" else null
                    )
                    DropdownField(
                        label = "Default category",
                        selected = categories.firstOrNull { it.id == defaultCategoryId },
                        options = categories,
                        optionLabel = { it.name },
                        onSelect = { defaultCategoryId = it?.id },
                        noneOption = "Uncategorized",
                        supportingText = "You can change the category of individual rows below."
                    )
                    ListSectionHeader(
                        "Review",
                        trailing = buildList {
                            add("${included.size} selected")
                            if (duplicates.isNotEmpty()) add("${duplicates.size} already imported")
                            if (invalidCount > 0) add("$invalidCount unreadable")
                        }.joinToString(" · ")
                    )
                }
            }

            items(rows, key = { "row-${it.rowIndex}" }) { row ->
                ImportRowItem(
                    row = row,
                    included = isIncluded(row),
                    duplicate = row.rowIndex in duplicates,
                    categories = categories,
                    categoryId = if (row.rowIndex in categoryOverrides) categoryOverrides[row.rowIndex] else defaultCategoryId,
                    onIncludedChange = { excluded[row.rowIndex] = !it },
                    onCategoryChange = { categoryOverrides[row.rowIndex] = it }
                )
            }

            item(key = "import") {
                Button(
                    onClick = { confirmImport = true },
                    enabled = included.isNotEmpty() && accountId != null,
                    modifier = Modifier.padding(top = Spacing.lg)
                ) {
                    Text("Import ${included.size} ${if (included.size == 1) "transaction" else "transactions"}")
                }
            }
        }
    }

    if (confirmImport) {
        val accountName = accounts.firstOrNull { it.id == accountId }?.name ?: "this account"
        ConfirmDialog(
            title = "Import ${included.size} transactions?",
            message = "They'll be added to $accountName. You can edit or delete them later in Activity.",
            confirmLabel = "Import",
            destructive = false,
            onConfirm = {
                val target = accountId ?: return@ConfirmDialog
                val toImport = included
                scope.launch {
                    toImport.groupBy { row ->
                        if (row.rowIndex in categoryOverrides) categoryOverrides[row.rowIndex] else defaultCategoryId
                    }.forEach { (categoryId, group) ->
                        postingRepo.bulkImport(
                            items = group.map { Triple(it.parsedDescription!!, it.parsedAmount!!, it.parsedDate!!) },
                            accountId = target,
                            categoryId = categoryId
                        )
                    }
                    snackbar.show("Imported ${toImport.size} transactions into $accountName")
                    onClose()
                    csvContent = null
                    csvFileName = null
                    headers = emptyList()
                    excluded.clear()
                    categoryOverrides.clear()
                }
            },
            onDismiss = { confirmImport = false }
        )
    }
}

@Composable
private fun ImportRowItem(
    row: CsvPreviewRow,
    included: Boolean,
    duplicate: Boolean,
    categories: List<Category>,
    categoryId: String?,
    onIncludedChange: (Boolean) -> Unit,
    onCategoryChange: (String?) -> Unit
) {
    val amount = row.parsedAmount
    val supporting = when {
        !row.isValid -> buildList {
            if (row.parsedDate == null) add("date")
            if (row.parsedAmount == null) add("amount")
            if (row.parsedDescription == null) add("description")
        }.joinToString(prefix = "Can't read ", separator = ", ")
        duplicate -> "${formatDate(row.parsedDate!!)} · Already imported"
        else -> formatDate(row.parsedDate!!)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Checkbox(checked = included, onCheckedChange = onIncludedChange, enabled = row.isValid)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.parsedDescription?.ifBlank { null } ?: row.raw.joinToString(", "),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (included) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.isValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
        if (row.isValid) {
            CategoryMenuChip(categories = categories, categoryId = categoryId, onSelect = onCategoryChange)
        }
        if (amount != null) {
            AmountText(if (amount > 0) ScheduleType.INCOME else ScheduleType.EXPENSE, abs(amount))
        }
    }
}

@Composable
private fun CategoryMenuChip(categories: List<Category>, categoryId: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val category = categories.firstOrNull { it.id == categoryId }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(category?.name ?: "Uncategorized", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = category?.let { { ColorDot(colorFromHex(it.color), size = 10) } }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Uncategorized") }, onClick = { onSelect(null); open = false })
            categories.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name) },
                    leadingIcon = { ColorDot(colorFromHex(c.color)) },
                    onClick = { onSelect(c.id); open = false }
                )
            }
        }
    }
}
