package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Link_off
import com.composables.icons.materialicons.filled.Open_in_new
import finance.shilling.shared.presentation.AttachedReceiptUi
import finance.shilling.shared.presentation.EditorLoad
import finance.shilling.shared.presentation.TransactionEditorViewModel
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

/** Create (postingId == null) or view/edit a recorded transaction. */
@Composable
fun TransactionEditor(
    postingId: String?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val viewModel = koinViewModel<TransactionEditorViewModel>(key = "transaction-${postingId ?: "new"}") { parametersOf(postingId) }
    val state by viewModel.state.collectAsState()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val fields = state.fields
    when (state.load) {
        EditorLoad.LOADING -> EditorPlaceholder("Transaction", navIcon, onClose, loading = true, missingMessage = "")
        EditorLoad.MISSING -> EditorPlaceholder("Transaction", navIcon, onClose, loading = false, missingMessage = state.missingMessage)
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
                            val result = viewModel.delete()
                            onSaved()
                            snackbar.showUndoable(result)
                        }
                    }
                )
            }
        ) {
            if (state.typeEditable) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    state.types.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = fields.type == option,
                            onClick = { viewModel.setType(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, state.types.size)
                        ) { Text(option.label) }
                    }
                }
            }
            TextInputField(value = fields.title, onValueChange = viewModel::setTitle, label = "Description")
            AmountField(value = fields.amountText, onValueChange = viewModel::setAmountText)
            DateField(
                value = LocalDate.fromEpochDays(fields.dateEpochDay),
                onValueChange = { viewModel.setDate(it.toEpochDays()) },
                label = "Date",
                supportingText = state.dateHint
            )
            DropdownField(
                label = state.accountLabel,
                selected = state.accounts.firstOrNull { it.id == fields.accountId },
                options = state.accounts,
                optionLabel = { it.label },
                onSelect = { viewModel.setAccount(it?.id) },
                enabled = state.accounts.isNotEmpty(),
                supportingText = state.accountHint
            )
            if (state.isTransfer) {
                DropdownField(
                    label = "To account",
                    selected = state.accounts.firstOrNull { it.id == fields.toAccountId },
                    options = state.toAccountChoices,
                    optionLabel = { it.label },
                    onSelect = { viewModel.setToAccount(it?.id) },
                    enabled = state.toAccountHint == null,
                    supportingText = state.toAccountHint
                )
            }
            DropdownField(
                label = "Category",
                selected = state.categories.firstOrNull { it.id == fields.categoryId },
                options = state.categories,
                optionLabel = { it.label },
                onSelect = { viewModel.setCategory(it?.id) },
                noneOption = state.categoryNoneLabel
            )
            if (state.showsReceipts) {
                ListSectionHeader("Receipts")
                ReceiptAttachments(state.receipts, viewModel)
            }
        }
    }
}

/** Receipts attached to a posting, with open / detach and capture actions. */
@Composable
private fun ReceiptAttachments(receipts: List<AttachedReceiptUi>, viewModel: TransactionEditorViewModel) {
    val snackbar = LocalSnackbarController.current
    val pickers = LocalReceiptPickers.current
    val scope = rememberCoroutineScope()
    val openReceipt = rememberReceiptOpener()

    val attachFile: (PlatformFile?) -> Unit = { file ->
        if (file != null) {
            scope.launch { viewModel.attachReceipt(file.name, file.readBytes())?.let(snackbar::show) }
        }
    }
    val fileLauncher = rememberFilePickerLauncher(type = FileKitType.File(), onResult = attachFile)

    if (receipts.isEmpty()) {
        Text(
            "No receipts attached.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    receipts.forEach { receipt ->
        EntityListItem(
            title = receipt.name,
            supporting = receipt.addedLabel,
            onClick = { openReceipt(receipt.receipt) },
            trailing = {
                androidx.compose.foundation.layout.Row {
                    TooltipIconButton(onClick = { openReceipt(receipt.receipt) }, tooltip = "Open") {
                        Icon(MaterialIcons.Filled.Open_in_new, contentDescription = "Open ${receipt.name}")
                    }
                    TooltipIconButton(
                        onClick = { scope.launch { snackbar.showUndoable(viewModel.detachReceipt(receipt.id)) } },
                        tooltip = "Detach"
                    ) {
                        Icon(MaterialIcons.Filled.Link_off, contentDescription = "Detach ${receipt.name}")
                    }
                }
            }
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        pickers.camera?.invoke(attachFile)
        pickers.photo?.invoke(attachFile)
        OutlinedButton(onClick = { fileLauncher.launch() }) { Text("Attach file") }
    }
}
