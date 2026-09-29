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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Upload
import finance.shilling.shared.presentation.CategoryChoice
import finance.shilling.shared.presentation.ImportRowUi
import finance.shilling.shared.presentation.ImportStage
import finance.shilling.shared.presentation.ImportViewModel
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ImportView(onClose: () -> Unit, viewModel: ImportViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    var confirmImport by remember { mutableStateOf(false) }

    val fileLauncher = rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("csv"))) { file ->
        if (file != null) scope.launch { viewModel.loadFile(file.name, file.readBytes()) }
    }

    ScreenScaffold(
        title = state.title,
        subtitle = state.subtitle,
        navIcon = ScreenNavIcon.BACK,
        onNavIcon = onClose,
        maxContentWidth = 840.dp
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "file") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { fileLauncher.launch() }) {
                        Icon(MaterialIcons.Filled.Upload, contentDescription = null)
                        Text(state.chooseLabel, modifier = Modifier.padding(start = Spacing.sm))
                    }
                    state.fileLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when (state.stage) {
                ImportStage.NO_FILE -> {
                    item(key = "empty") { EmptyState(title = state.emptyTitle, message = state.emptyMessage) }
                    return@LazyColumn
                }
                ImportStage.UNREADABLE -> {
                    item(key = "unreadable") { EmptyState(title = state.unreadableTitle) }
                    return@LazyColumn
                }
                ImportStage.READY -> Unit
            }

            item(key = "columns") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ListSectionHeader("Columns")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Switch(checked = state.hasHeader, onCheckedChange = viewModel::setHasHeader)
                        Text(state.headerToggleLabel, style = MaterialTheme.typography.bodyLarge)
                    }
                    DropdownField(
                        "Date", state.columns.firstOrNull { it.index == state.dateColumn }, state.columns, { it.label },
                        { it?.let { c -> viewModel.setDateColumn(c.index) } }
                    )
                    DropdownField(
                        "Date format", state.dateFormats.firstOrNull { it.format == state.dateFormat }, state.dateFormats, { it.label },
                        { it?.let { f -> viewModel.setDateFormat(f.format) } }
                    )
                    DropdownField(
                        "Description", state.columns.firstOrNull { it.index == state.descriptionColumn }, state.columns, { it.label },
                        { it?.let { c -> viewModel.setDescriptionColumn(c.index) } }
                    )
                    DropdownField(
                        "Amount", state.columns.firstOrNull { it.index == state.amountColumn }, state.columns, { it.label },
                        { it?.let { c -> viewModel.setAmountColumn(c.index) } },
                        supportingText = state.amountHint
                    )
                    ListSectionHeader("Import into")
                    DropdownField(
                        label = "Account",
                        selected = state.accounts.firstOrNull { it.id == state.accountId },
                        options = state.accounts,
                        optionLabel = { it.label },
                        onSelect = { viewModel.setAccount(it?.id) },
                        enabled = state.accounts.isNotEmpty(),
                        isError = state.accounts.isEmpty(),
                        supportingText = state.accountHint
                    )
                    DropdownField(
                        label = "Default category",
                        selected = state.categories.firstOrNull { it.id == state.defaultCategoryId },
                        options = state.categories,
                        optionLabel = { it.label },
                        onSelect = { viewModel.setDefaultCategory(it?.id) },
                        noneOption = state.noCategoryLabel,
                        supportingText = state.categoryHint
                    )
                    ListSectionHeader("Review", trailing = state.reviewSummary)
                }
            }

            items(state.rows, key = { "row-${it.rowIndex}" }) { row ->
                ImportRowItem(
                    row = row,
                    categories = state.categories,
                    noCategoryLabel = state.noCategoryLabel,
                    onIncludedChange = { viewModel.setIncluded(row.rowIndex, it) },
                    onCategoryChange = { viewModel.setRowCategory(row.rowIndex, it) }
                )
            }

            item(key = "import") {
                Button(
                    onClick = { confirmImport = true },
                    enabled = state.importEnabled,
                    modifier = Modifier.padding(top = Spacing.lg)
                ) {
                    Text(state.importLabel)
                }
            }
        }
    }

    val confirm = state.confirm
    if (confirmImport && confirm != null) {
        ConfirmDialog(
            title = confirm.title,
            message = confirm.message,
            confirmLabel = confirm.confirmLabel,
            destructive = confirm.destructive,
            onConfirm = {
                scope.launch {
                    viewModel.import()?.let {
                        snackbar.show(it)
                        onClose()
                    }
                }
            },
            onDismiss = { confirmImport = false }
        )
    }
}

@Composable
private fun ImportRowItem(
    row: ImportRowUi,
    categories: List<CategoryChoice>,
    noCategoryLabel: String,
    onIncludedChange: (Boolean) -> Unit,
    onCategoryChange: (String?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Checkbox(checked = row.included, onCheckedChange = onIncludedChange, enabled = row.valid)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (row.included) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                row.supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
        if (row.valid) {
            CategoryMenuChip(row, categories, noCategoryLabel, onCategoryChange)
        }
        row.amount?.let { Text(it, color = amountColor(row.type), style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun CategoryMenuChip(
    row: ImportRowUi,
    categories: List<CategoryChoice>,
    noCategoryLabel: String,
    onSelect: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(row.categoryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = row.categoryId?.let { { ColorDot(colorFromHex(row.categoryColor), size = 10) } }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(noCategoryLabel) }, onClick = { onSelect(null); open = false })
            categories.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label) },
                    leadingIcon = { ColorDot(colorFromHex(c.color)) },
                    onClick = { onSelect(c.id); open = false }
                )
            }
        }
    }
}
