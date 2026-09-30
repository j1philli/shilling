package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Search
import finance.shilling.shared.presentation.EditorLoad
import finance.shilling.shared.presentation.ReceiptEditorViewModel
import finance.shilling.shared.presentation.ReceiptsViewModel
import finance.shilling.shared.presentation.label
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ReceiptsScreen(
    onOpenReceipt: (String?) -> Unit,
    viewModel: ReceiptsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    ListDetailLayout(
        selectedKey = selectedKey,
        onDismissDetail = { selectedKey = null },
        list = { twoPane ->
            val open: (String?) -> Unit = { id ->
                if (twoPane) selectedKey = id ?: NEW_ITEM_KEY else onOpenReceipt(id)
            }
            ScreenScaffold(
                title = "Receipts",
                subtitle = state.subtitle,
                actions = { AddButton("Add", onClick = { open(null) }) }
            ) { padding ->
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    item(key = "filters") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            state.filters.forEach { f ->
                                FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) })
                            }
                        }
                    }
                    val empty = state.empty
                    if (empty != null) {
                        item(key = "empty") {
                            if (empty.showAdd) {
                                EmptyState(
                                    title = empty.title,
                                    message = empty.message,
                                    actionLabel = "Add receipt",
                                    onAction = { open(null) }
                                )
                            } else {
                                EmptyState(title = empty.title)
                            }
                        }
                    } else {
                        items(state.rows, key = { it.id }) { row ->
                            EntityListItem(
                                title = row.title,
                                supporting = row.supporting,
                                trailing = row.amount?.let { amount ->
                                    { Text(amount, style = MaterialTheme.typography.bodyLarge) }
                                },
                                selected = twoPane && selectedKey == row.id,
                                onClick = { open(row.id) }
                            )
                        }
                    }
                }
            }
        },
        detail = { key ->
            ReceiptEditor(
                receiptId = key.takeUnless { it == NEW_ITEM_KEY },
                navIcon = ScreenNavIcon.CLOSE,
                onClose = { selectedKey = null },
                onSaved = { selectedKey = null }
            )
        },
        emptyDetail = { EmptyState(title = "No receipt selected", message = "Choose a receipt to view or attach it.") }
    )
}

@Composable
fun ReceiptEditor(
    receiptId: String?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    initialFile: PlatformFile? = null,
    onInitialFileConsumed: () -> Unit = {}
) {
    val viewModel = koinViewModel<ReceiptEditorViewModel>(key = "receipt-${receiptId ?: "new"}") { parametersOf(receiptId) }
    val state by viewModel.state.collectAsState()
    val snackbar = LocalSnackbarController.current
    val pickers = LocalReceiptPickers.current
    val scope = rememberCoroutineScope()
    val openReceipt = rememberReceiptOpener()
    var showAttach by remember { mutableStateOf(false) }
    val f = state.fields

    val handleFile: (PlatformFile?) -> Unit = { file ->
        if (file != null) scope.launch { viewModel.setFile(file.name, file.readBytes()) }
    }
    LaunchedEffect(initialFile) {
        if (initialFile != null) {
            handleFile(initialFile)
            onInitialFileConsumed()
        }
    }
    val fileLauncher = rememberFilePickerLauncher(type = FileKitType.File(), onResult = handleFile)

    when (state.load) {
        EditorLoad.LOADING -> EditorPlaceholder("Receipt", navIcon, onClose, loading = true, missingMessage = "")
        EditorLoad.MISSING -> EditorPlaceholder("Receipt", navIcon, onClose, loading = false, missingMessage = state.missingMessage)
        EditorLoad.READY -> EditorScaffold(
            title = state.title,
            subtitle = state.subtitle,
            navIcon = navIcon,
            onClose = onClose,
            saveEnabled = state.saveEnabled,
            onSave = {
                scope.launch {
                    viewModel.save()?.let {
                        snackbar.show(it)
                        onSaved()
                    }
                }
            },
            delete = state.deleteConfirm?.let { copy ->
                DeleteConfirmation(
                    title = copy.title,
                    message = copy.message,
                    confirmLabel = copy.confirmLabel,
                    onConfirm = {
                        scope.launch {
                            val message = viewModel.delete()
                            onSaved()
                            message?.let(snackbar::show)
                        }
                    }
                )
            }
        ) {
            if (state.isNew) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    pickers.camera?.invoke(handleFile)
                    pickers.photo?.invoke(handleFile)
                    OutlinedButton(onClick = { fileLauncher.launch() }) { Text("Choose file") }
                }
                Text(
                    state.fileLabel ?: state.noFileLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.fileLabel != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextInputField(value = f.name, onValueChange = viewModel::setName, label = "Name")
            } else {
                state.receipt?.let { receipt ->
                    OutlinedButton(onClick = { openReceipt(receipt) }) { Text("Open receipt") }
                }
            }
            DateField(
                value = f.receiptDateEpochDay?.let { LocalDate.fromEpochDays(it) },
                onValueChange = { viewModel.setReceiptDate(it.toEpochDays()) },
                label = "Receipt date",
                placeholder = "Optional",
                onClear = { viewModel.setReceiptDate(null) }
            )
            AmountField(value = f.amountText, onValueChange = viewModel::setAmountText, supportingText = "Optional")
            TextInputField(value = f.notes, onValueChange = viewModel::setNotes, label = "Notes", singleLine = false)
            ListSectionHeader("Transaction")
            Text(
                state.attachmentLabel ?: state.notAttachedLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.attachmentLabel != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { showAttach = true }) { Text(state.attachLabel) }
                if (state.attachmentLabel != null) {
                    TextButton(onClick = { scope.launch { snackbar.showUndoable(viewModel.detach()) } }) { Text("Detach") }
                }
            }
        }
    }

    if (showAttach) {
        AttachTransactionDialog(
            viewModel = viewModel,
            onSelect = { postingId ->
                showAttach = false
                scope.launch { viewModel.attach(postingId)?.let(snackbar::show) }
            },
            onDismiss = {
                showAttach = false
                viewModel.setPickerQuery("")
            }
        )
    }
}

/** Searchable picker over recent transactions; amount matches float to the top. */
@Composable
private fun AttachTransactionDialog(
    viewModel: ReceiptEditorViewModel,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val picker by viewModel.picker.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(picker.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = picker.query,
                    onValueChange = viewModel::setPickerQuery,
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(MaterialIcons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (picker.candidates.isEmpty()) {
                    Text(
                        picker.emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(picker.candidates, key = { it.postingId }) { candidate ->
                            EntityListItem(
                                title = candidate.title,
                                supporting = candidate.supporting,
                                trailing = {
                                    Text(candidate.amount, color = amountColor(candidate.type), style = MaterialTheme.typography.bodyLarge)
                                },
                                onClick = { onSelect(candidate.postingId) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
