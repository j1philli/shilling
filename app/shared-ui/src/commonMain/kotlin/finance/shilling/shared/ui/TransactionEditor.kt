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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Link_off
import com.composables.icons.materialicons.filled.Open_in_new
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject
import kotlin.time.Clock
import finance.shilling.shared.presentation.formatTimestamp
import finance.shilling.shared.presentation.label
import finance.shilling.shared.presentation.parseAmountInput
import finance.shilling.shared.presentation.postedLabel
import finance.shilling.shared.presentation.today
import finance.shilling.shared.presentation.formatAmountInput

/** Create (postingId == null) or view/edit a recorded transaction. */
@Composable
fun TransactionEditor(
    postingId: String?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    if (postingId == null) {
        TransactionForm(existing = null, partner = null, navIcon = navIcon, onClose = onClose, onSaved = onSaved)
        return
    }
    val postingRepo = koinInject<PostingRepository>()
    val loadable = rememberLoadable(postingId) { postingRepo.watchById(postingId) }
    when (loadable) {
        Loadable.Loading -> EditorPlaceholder("Transaction", navIcon, onClose, loading = true, missingMessage = "")
        is Loadable.Ready -> {
            val details = loadable.value
            if (details == null) {
                EditorPlaceholder(
                    "Transaction", navIcon, onClose, loading = false,
                    missingMessage = "This transaction was deleted."
                )
            } else {
                var partner by remember(postingId) { mutableStateOf<Posting?>(null) }
                var partnerLoaded by remember(postingId) { mutableStateOf(false) }
                LaunchedEffect(details.posting) {
                    partner = postingRepo.getTransferPartner(details.posting)
                    partnerLoaded = true
                }
                if (!partnerLoaded) {
                    EditorPlaceholder("Transaction", navIcon, onClose, loading = true, missingMessage = "")
                } else {
                    TransactionForm(
                        existing = details,
                        partner = partner,
                        navIcon = navIcon,
                        onClose = onClose,
                        onSaved = onSaved
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionForm(
    existing: PostingWithDetails?,
    partner: Posting?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val postingRepo = koinInject<PostingRepository>()
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val accounts by remember { accountRepo.watchAll() }.collectAsState(initial = emptyList())
    val categories by remember { categoryRepo.watchAll() }.collectAsState(initial = emptyList())

    val posting = existing?.posting
    // For transfers, edit from the debit (outgoing) leg's point of view.
    val debit = when {
        partner == null -> posting
        posting?.type == ScheduleType.EXPENSE -> posting
        else -> partner
    }
    val credit = if (partner == null) null else if (debit === posting) partner else posting
    val isScheduled = posting?.scheduleId != null

    var type by rememberSaveable {
        mutableStateOf(if (partner != null) ScheduleType.TRANSFER else posting?.type ?: ScheduleType.EXPENSE)
    }
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var amountText by rememberSaveable { mutableStateOf(posting?.amount?.let(::formatAmountInput).orEmpty()) }
    var dateEpochDay by rememberSaveable { mutableLongStateOf((posting?.date ?: today()).toEpochDays()) }
    var accountId by rememberSaveable { mutableStateOf(debit?.accountId) }
    var toAccountId by rememberSaveable { mutableStateOf(credit?.accountId) }
    var categoryId by rememberSaveable { mutableStateOf(posting?.categoryId) }

    LaunchedEffect(accounts) {
        if (accountId == null || accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id
    }
    val date = LocalDate.fromEpochDays(dateEpochDay)
    val amount = parseAmountInput(amountText)
    val isTransfer = type == ScheduleType.TRANSFER
    val transferValid = !isTransfer || (toAccountId != null && toAccountId != accountId)
    val canSave = title.isNotBlank() && amount != null && amount > 0 && accountId != null && transferValid

    fun save() {
        val amountValue = amount ?: return
        val from = accountId ?: return
        scope.launch {
            if (posting == null) {
                if (isTransfer) {
                    postingRepo.recordAdHocTransfer(title.trim(), amountValue, from, toAccountId!!, categoryId, date)
                } else {
                    postingRepo.recordAdHoc(title.trim(), amountValue, type, from, categoryId, date)
                }
                snackbar.show("Transaction added")
            } else {
                // Keep a scheduled posting's title linked to its schedule unless it was edited.
                val newTitle = title.trim().takeUnless { isScheduled && it == existing.title }
                    ?: posting.title
                val updatedDebit = debit!!.copy(
                    title = newTitle, amount = amountValue, date = date, accountId = from, categoryId = categoryId
                )
                postingRepo.record(updatedDebit)
                credit?.let {
                    postingRepo.record(
                        it.copy(
                            title = newTitle, amount = amountValue, date = date,
                            accountId = toAccountId ?: it.accountId, categoryId = categoryId
                        )
                    )
                }
                snackbar.show("Transaction updated")
            }
            onSaved()
        }
    }

    EditorScaffold(
        title = if (posting == null) "New transaction" else existing.title,
        subtitle = posting?.let {
            if (isScheduled) "Recorded from a schedule" else "One-off ${type.label.lowercase()}"
        },
        navIcon = navIcon,
        onClose = onClose,
        saveEnabled = canSave,
        onSave = ::save,
        delete = posting?.let { p ->
            DeleteConfirmation(
                title = "Delete transaction?",
                message = if (isScheduled) {
                    "The occurrence will show as not ${type.postedLabel.lowercase()} in Plan again."
                } else {
                    "Attached receipts are kept and become unattached."
                },
                onConfirm = {
                    val removed = listOfNotNull(p, partner)
                    scope.launch {
                        removed.forEach { postingRepo.delete(it.id) }
                        onSaved()
                        snackbar.showUndo("Transaction deleted") { removed.forEach { postingRepo.record(it) } }
                    }
                }
            )
        }
    ) {
        if (posting == null) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ScheduleType.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = type == option,
                        onClick = { type = option },
                        shape = SegmentedButtonDefaults.itemShape(index, ScheduleType.entries.size)
                    ) { Text(option.label) }
                }
            }
        }
        TextInputField(value = title, onValueChange = { title = it }, label = "Description")
        AmountField(value = amountText, onValueChange = { amountText = it })
        DateField(
            value = date,
            onValueChange = { dateEpochDay = it.toEpochDays() },
            label = "Date",
            supportingText = if (isScheduled) "Changing the date may show the original occurrence as due again." else null
        )
        DropdownField(
            label = if (isTransfer) "From account" else "Account",
            selected = accounts.firstOrNull { it.id == accountId },
            options = accounts,
            optionLabel = { it.name },
            onSelect = { accountId = it?.id },
            enabled = accounts.isNotEmpty(),
            supportingText = if (accounts.isEmpty()) "Add an account first" else null
        )
        if (isTransfer) {
            DropdownField(
                label = "To account",
                selected = accounts.firstOrNull { it.id == toAccountId },
                options = accounts.filter { it.id != accountId },
                optionLabel = { it.name },
                onSelect = { toAccountId = it?.id },
                enabled = accounts.size >= 2,
                supportingText = if (accounts.size < 2) "Transfers need at least two accounts" else null
            )
        }
        DropdownField(
            label = "Category",
            selected = categories.firstOrNull { it.id == categoryId },
            options = categories,
            optionLabel = { it.name },
            onSelect = { categoryId = it?.id },
            // Scheduled postings without their own category inherit the schedule's.
            noneOption = if (isScheduled) "Same as schedule" else "Uncategorized"
        )
        if (posting != null) {
            ListSectionHeader("Receipts")
            ReceiptAttachments(postingId = debit!!.id)
        }
    }
}

/** Receipts attached to a posting, with open / detach and capture actions. */
@Composable
private fun ReceiptAttachments(postingId: String) {
    val receiptRepo = koinInject<ReceiptRepository>()
    val fileStore = koinInject<ReceiptFileStore>()
    val idGen = koinInject<IdGenerator>()
    val snackbar = LocalSnackbarController.current
    val pickers = LocalReceiptPickers.current
    val scope = rememberCoroutineScope()
    val openReceipt = rememberReceiptOpener()
    val receipts by remember(postingId) { receiptRepo.watchByPosting(postingId) }.collectAsState(initial = emptyList())

    val attachFile: (PlatformFile?) -> Unit = { file ->
        if (file != null) {
            scope.launch {
                val receiptId = idGen.newId()
                fileStore.store(receiptId, file.name, file.readBytes())
                receiptRepo.save(
                    Receipt(
                        id = receiptId,
                        postingId = postingId,
                        filePath = file.name,
                        originalName = file.name,
                        addedAt = Clock.System.now().toEpochMilliseconds()
                    )
                )
                snackbar.show("Receipt attached")
            }
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
            title = receipt.originalName,
            supporting = "Added ${formatTimestamp(receipt.addedAt)}",
            onClick = { openReceipt(receipt) },
            trailing = {
                androidx.compose.foundation.layout.Row {
                    TooltipIconButton(onClick = { openReceipt(receipt) }, tooltip = "Open") {
                        Icon(MaterialIcons.Filled.Open_in_new, contentDescription = "Open ${receipt.originalName}")
                    }
                    TooltipIconButton(
                        onClick = {
                            scope.launch {
                                receiptRepo.detach(receipt.id)
                                snackbar.showUndo("Receipt detached") { receiptRepo.attach(receipt.id, postingId) }
                            }
                        },
                        tooltip = "Detach"
                    ) {
                        Icon(MaterialIcons.Filled.Link_off, contentDescription = "Detach ${receipt.originalName}")
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
