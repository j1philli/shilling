package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.CsvColumnMapping
import finance.shilling.shared.data.CsvImporter
import finance.shilling.shared.data.CsvPreviewRow
import finance.shilling.shared.data.DateFormat
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun ImportView() {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val scope = rememberCoroutineScope()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())

    var csvContent by remember { mutableStateOf<String?>(null) }
    var csvFileName by remember { mutableStateOf<String?>(null) }
    var headers by remember { mutableStateOf<List<String>>(emptyList()) }
    var dateCol by remember { mutableStateOf(0) }
    var descCol by remember { mutableStateOf(1) }
    var amountCol by remember { mutableStateOf(2) }
    var dateFormat by remember { mutableStateOf(DateFormat.ISO) }
    var hasHeader by remember { mutableStateOf(true) }
    var selectedAccountId by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var selectedCategoryId by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<CsvPreviewRow>>(emptyList()) }
    var importResult by remember { mutableStateOf<String?>(null) }

    val fileLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("csv"))
    ) { file ->
        if (file != null) {
            scope.launch {
                csvContent = file.readBytes().decodeToString()
                csvFileName = file.name
                headers = CsvImporter.parseHeaders(csvContent!!)
                preview = emptyList()
                importResult = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader("Import CSV", "Import transactions from a bank CSV export.")

        // Step 1: Pick file
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                fileLauncher.launch()
            }) {
                Icon(MaterialIcons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Choose CSV file")
            }
            csvFileName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }

        if (csvContent != null && headers.isNotEmpty()) {
            ShillingDivider()
            ShillingCard {
                Text("Column Mapping", style = MaterialTheme.typography.titleMedium)
                Text("Headers: ${headers.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)

                // Column selectors
                ColumnSelector("Date column", headers, dateCol) { dateCol = it }
                ColumnSelector("Description column", headers, descCol) { descCol = it }
                ColumnSelector("Amount column", headers, amountCol) { amountCol = it }

                // Date format
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Date format", style = MaterialTheme.typography.labelMedium)
                    ButtonDropdown(
                        label = dateFormatLabel(dateFormat),
                        options = DateFormat.values().toList(),
                        onSelect = { dateFormat = it },
                        itemLabel = { dateFormatLabel(it) }
                    )
                }

                // Account selector
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Import to account", style = MaterialTheme.typography.labelMedium)
                    ButtonDropdown(
                        label = accounts.firstOrNull { it.id == selectedAccountId }?.name ?: "Select account",
                        options = accounts,
                        onSelect = { selectedAccountId = it.id },
                        itemLabel = { it.name },
                        enabled = accounts.isNotEmpty()
                    )
                }

                // Category selector
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Category", style = MaterialTheme.typography.labelMedium)
                    ButtonDropdown(
                        label = categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "Uncategorized",
                        options = categories,
                        onSelect = { selectedCategoryId = it.id },
                        itemLabel = { it.name },
                        leadingOption = "Uncategorized" to { selectedCategoryId = "" }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val mapping = CsvColumnMapping(dateCol, descCol, amountCol, dateFormat)
                        preview = CsvImporter.preview(csvContent!!, mapping, hasHeader)
                    }) { Text("Preview") }
                    Button(
                        onClick = {
                            val mapping = CsvColumnMapping(dateCol, descCol, amountCol, dateFormat)
                            val all = CsvImporter.parseAll(csvContent!!, mapping, hasHeader)
                            val valid = all.filter { it.isValid }
                            if (valid.isEmpty()) {
                                importResult = "No valid rows to import."
                                return@Button
                            }
                            val items = valid.map { row ->
                                Triple(row.parsedDescription!!, row.parsedAmount!!, row.parsedDate!!)
                            }
                            scope.launch {
                                postingRepo.bulkImport(
                                    items = items,
                                    accountId = selectedAccountId,
                                    categoryId = selectedCategoryId.takeIf { it.isNotBlank() }
                                )
                                importResult = "Imported ${valid.size} transactions (${all.size - valid.size} skipped)."
                                preview = emptyList()
                            }
                        },
                        enabled = selectedAccountId.isNotBlank()
                    ) { Text("Import all") }
                }

                importResult?.let {
                    Text(it, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }

            // Preview table
            if (preview.isNotEmpty()) {
                ShillingDivider()
                ShillingCard {
                    Text("Preview (first ${preview.size} rows)", style = MaterialTheme.typography.titleMedium)
                    preview.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.parsedDescription ?: "(no description)",
                                    fontWeight = if (row.isValid) FontWeight.Normal else FontWeight.Light
                                )
                                Text(
                                    "${row.parsedDate ?: "invalid date"} • ${row.parsedAmount?.let { formatCurrency(it) } ?: "invalid amount"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.isValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                                )
                            }
                            if (!row.isValid) {
                                Text("skip", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        ShillingDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnSelector(
    label: String,
    headers: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        ButtonDropdown(
            label = headers.getOrElse(selected) { "Column $selected" },
            options = headers.indices.toList(),
            onSelect = onSelect,
            itemLabel = { "$it: ${headers[it]}" }
        )
    }
}

private fun dateFormatLabel(format: DateFormat): String = when (format) {
    DateFormat.ISO -> "YYYY-MM-DD"
    DateFormat.US_SLASH -> "MM/DD/YYYY"
    DateFormat.US_DASH -> "MM-DD-YYYY"
    DateFormat.EU_SLASH -> "DD/MM/YYYY"
    DateFormat.EU_DASH -> "DD-MM-YYYY"
}
