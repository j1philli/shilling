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
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ReceiptWithPosting
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.time.Clock
import finance.shilling.shared.presentation.formatCurrency
import finance.shilling.shared.presentation.formatDate
import finance.shilling.shared.presentation.formatFileSize
import finance.shilling.shared.presentation.formatTimestamp
import finance.shilling.shared.presentation.label
import finance.shilling.shared.presentation.parseAmountInput
import finance.shilling.shared.presentation.today
import finance.shilling.shared.presentation.formatAmountInput

private enum class ReceiptFilter(val label: String) {
    ALL("All"), UNATTACHED("Not attached"), ATTACHED("Attached")
}

@Composable
fun ReceiptsScreen(onOpenReceipt: (String?) -> Unit) {
    val receiptRepo = koinInject<ReceiptRepository>()
    val receipts by remember { receiptRepo.watchAll() }.collectAsState(initial = emptyList())
    var filterName by rememberSaveable { mutableStateOf(ReceiptFilter.ALL.name) }
    val filter = ReceiptFilter.valueOf(filterName)
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(receipts, filter) {
        when (filter) {
            ReceiptFilter.ALL -> receipts
            ReceiptFilter.UNATTACHED -> receipts.filter { it.receipt.postingId == null }
            ReceiptFilter.ATTACHED -> receipts.filter { it.receipt.postingId != null }
        }.sortedByDescending { it.receipt.addedAt }
    }

    ListDetailLayout(
        selectedKey = selectedKey,
        onDismissDetail = { selectedKey = null },
        list = { twoPane ->
            val open: (String?) -> Unit = { id ->
                if (twoPane) selectedKey = id ?: NEW_ITEM_KEY else onOpenReceipt(id)
            }
            ScreenScaffold(
                title = "Receipts",
                subtitle = receipts.count { it.receipt.postingId == null }.takeIf { it > 0 }?.let { "$it not attached" },
                actions = { AddButton("Add", onClick = { open(null) }) }
            ) { padding ->
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    item(key = "filters") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            ReceiptFilter.entries.forEach { f ->
                                FilterChip(selected = filter == f, onClick = { filterName = f.name }, label = { Text(f.label) })
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "empty") {
                            when (filter) {
                                ReceiptFilter.ALL -> EmptyState(
                                    title = "No receipts yet",
                                    message = "Keep receipts here and attach them to transactions when they post.",
                                    actionLabel = "Add receipt",
                                    onAction = { open(null) }
                                )
                                ReceiptFilter.UNATTACHED -> EmptyState(title = "All receipts are attached")
                                ReceiptFilter.ATTACHED -> EmptyState(title = "No attached receipts")
                            }
                        }
                    } else {
                        items(filtered, key = { it.receipt.id }) { item ->
                            EntityListItem(
                                title = item.receipt.originalName,
                                supporting = receiptSummary(item),
                                trailing = item.receipt.amount?.let { amount ->
                                    { Text(formatCurrency(amount), style = MaterialTheme.typography.bodyLarge) }
                                },
                                selected = twoPane && selectedKey == item.receipt.id,
                                onClick = { open(item.receipt.id) }
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

private fun receiptSummary(item: ReceiptWithPosting): String = buildList {
    add(item.receipt.receiptDate?.let { formatDate(LocalDate.fromEpochDays(it)) } ?: "Added ${formatDate(addedDate(item.receipt))}")
    add(item.postingTitle?.let { "Attached to $it" } ?: "Not attached")
}.joinToString(" · ")

private fun addedDate(receipt: Receipt): LocalDate =
    kotlin.time.Instant.fromEpochMilliseconds(receipt.addedAt)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date

@Composable
fun ReceiptEditor(
    receiptId: String?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    initialFile: PlatformFile? = null,
    onInitialFileConsumed: () -> Unit = {}
) {
    if (receiptId == null) {
        NewReceiptForm(navIcon, onClose, onSaved, initialFile, onInitialFileConsumed)
        return
    }
    val receiptRepo = koinInject<ReceiptRepository>()
    val loadable = rememberLoadable(receiptId) {
        receiptRepo.watchAll().map { list -> list.firstOrNull { it.receipt.id == receiptId } }
    }
    when (loadable) {
        Loadable.Loading -> EditorPlaceholder("Receipt", navIcon, onClose, loading = true, missingMessage = "")
        is Loadable.Ready -> loadable.value?.let { item ->
            ExistingReceiptForm(item, navIcon, onClose, onSaved)
        } ?: EditorPlaceholder("Receipt", navIcon, onClose, loading = false, missingMessage = "This receipt was deleted.")
    }
}

@Composable
private fun NewReceiptForm(
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    initialFile: PlatformFile?,
    onInitialFileConsumed: () -> Unit
) {
    val receiptRepo = koinInject<ReceiptRepository>()
    val fileStore = koinInject<ReceiptFileStore>()
    val idGen = koinInject<IdGenerator>()
    val snackbar = LocalSnackbarController.current
    val pickers = LocalReceiptPickers.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf<String?>(null) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var receiptDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var attachTo by remember { mutableStateOf<PostingWithDetails?>(null) }
    var showAttach by remember { mutableStateOf(false) }

    val handleFile: (PlatformFile?) -> Unit = { file ->
        if (file != null) {
            scope.launch {
                bytes = file.readBytes()
                fileName = file.name
                if (name.isBlank()) name = file.name
            }
        }
    }
    LaunchedEffect(initialFile) {
        if (initialFile != null) {
            handleFile(initialFile)
            onInitialFileConsumed()
        }
    }
    val fileLauncher = rememberFilePickerLauncher(type = FileKitType.File(), onResult = handleFile)
    val amount = parseAmountInput(amountText)

    EditorScaffold(
        title = "New receipt",
        navIcon = navIcon,
        onClose = onClose,
        saveEnabled = bytes != null && name.isNotBlank() && (amountText.isBlank() || amount != null),
        onSave = {
            val data = bytes ?: return@EditorScaffold
            scope.launch {
                val id = idGen.newId()
                val storedName = name.trim()
                fileStore.store(id, storedName, data)
                receiptRepo.save(
                    Receipt(
                        id = id,
                        postingId = attachTo?.posting?.id,
                        filePath = storedName,
                        originalName = storedName,
                        addedAt = Clock.System.now().toEpochMilliseconds(),
                        notes = notes.trim().ifBlank { null },
                        receiptDate = receiptDate,
                        amount = amount
                    )
                )
                snackbar.show("Receipt saved")
                onSaved()
            }
        }
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            pickers.camera?.invoke(handleFile)
            pickers.photo?.invoke(handleFile)
            OutlinedButton(onClick = { fileLauncher.launch() }) { Text("Choose file") }
        }
        Text(
            fileName?.let { "$it · ${formatFileSize(bytes?.size ?: 0)}" } ?: "No file selected",
            style = MaterialTheme.typography.bodyMedium,
            color = if (fileName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextInputField(value = name, onValueChange = { name = it }, label = "Name")
        ReceiptMetadataFields(
            receiptDate = receiptDate,
            onDateChange = { receiptDate = it },
            amountText = amountText,
            onAmountChange = { amountText = it },
            notes = notes,
            onNotesChange = { notes = it }
        )
        ListSectionHeader("Transaction")
        AttachmentSummary(
            label = attachTo?.let { "${it.title} · ${formatDate(it.posting.date)}" },
            onAttach = { showAttach = true },
            onDetach = { attachTo = null }
        )
    }

    if (showAttach) {
        AttachTransactionDialog(
            receiptAmount = amount,
            receiptDate = receiptDate?.let { LocalDate.fromEpochDays(it) },
            onSelect = { attachTo = it; showAttach = false },
            onDismiss = { showAttach = false }
        )
    }
}

@Composable
private fun ExistingReceiptForm(
    item: ReceiptWithPosting,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val receipt = item.receipt
    val receiptRepo = koinInject<ReceiptRepository>()
    val fileStore = koinInject<ReceiptFileStore>()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val openReceipt = rememberReceiptOpener()
    var receiptDate by rememberSaveable(receipt.id) { mutableStateOf(receipt.receiptDate) }
    var amountText by rememberSaveable(receipt.id) { mutableStateOf(receipt.amount?.let(::formatAmountInput).orEmpty()) }
    var notes by rememberSaveable(receipt.id) { mutableStateOf(receipt.notes.orEmpty()) }
    var showAttach by remember { mutableStateOf(false) }
    val amount = parseAmountInput(amountText)

    EditorScaffold(
        title = receipt.originalName,
        subtitle = "Added ${formatTimestamp(receipt.addedAt)}",
        navIcon = navIcon,
        onClose = onClose,
        saveEnabled = amountText.isBlank() || amount != null,
        onSave = {
            scope.launch {
                receiptRepo.updateMetadata(receipt.id, notes.trim().ifBlank { null }, receiptDate, amount)
                snackbar.show("Receipt updated")
                onSaved()
            }
        },
        delete = DeleteConfirmation(
            title = "Delete receipt?",
            message = "The file is removed from this device and your synced devices. This can't be undone.",
            confirmLabel = "Delete receipt",
            onConfirm = {
                scope.launch {
                    fileStore.delete(receipt.id)
                    receiptRepo.delete(receipt.id)
                    onSaved()
                    snackbar.show("Receipt deleted")
                }
            }
        )
    ) {
        OutlinedButton(onClick = { openReceipt(receipt) }) { Text("Open receipt") }
        ReceiptMetadataFields(
            receiptDate = receiptDate,
            onDateChange = { receiptDate = it },
            amountText = amountText,
            onAmountChange = { amountText = it },
            notes = notes,
            onNotesChange = { notes = it }
        )
        ListSectionHeader("Transaction")
        AttachmentSummary(
            label = item.postingTitle?.let { title ->
                val date = item.postingDate?.let { runCatching { formatDate(LocalDate.parse(it)) }.getOrDefault(it) }
                listOfNotNull(title, date).joinToString(" · ")
            },
            onAttach = { showAttach = true },
            onDetach = {
                val previous = receipt.postingId ?: return@AttachmentSummary
                scope.launch {
                    receiptRepo.detach(receipt.id)
                    snackbar.showUndo("Receipt detached") { receiptRepo.attach(receipt.id, previous) }
                }
            }
        )
    }

    if (showAttach) {
        AttachTransactionDialog(
            receiptAmount = amount,
            receiptDate = receiptDate?.let { LocalDate.fromEpochDays(it) },
            onSelect = { posting ->
                showAttach = false
                scope.launch {
                    receiptRepo.attach(receipt.id, posting.posting.id)
                    snackbar.show("Attached to ${posting.title}")
                }
            },
            onDismiss = { showAttach = false }
        )
    }
}

@Composable
private fun ReceiptMetadataFields(
    receiptDate: Long?,
    onDateChange: (Long?) -> Unit,
    amountText: String,
    onAmountChange: (String) -> Unit,
    notes: String,
    onNotesChange: (String) -> Unit
) {
    DateField(
        value = receiptDate?.let { LocalDate.fromEpochDays(it) },
        onValueChange = { onDateChange(it.toEpochDays()) },
        label = "Receipt date",
        placeholder = "Optional",
        onClear = { onDateChange(null) }
    )
    AmountField(value = amountText, onValueChange = onAmountChange, supportingText = "Optional")
    TextInputField(value = notes, onValueChange = onNotesChange, label = "Notes", singleLine = false)
}

@Composable
private fun AttachmentSummary(label: String?, onAttach: () -> Unit, onDetach: () -> Unit) {
    Text(
        label ?: "Not attached to a transaction",
        style = MaterialTheme.typography.bodyLarge,
        color = if (label != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedButton(onClick = onAttach) { Text(if (label == null) "Attach to transaction" else "Change") }
        if (label != null) TextButton(onClick = onDetach) { Text("Detach") }
    }
}

/** Searchable picker over the last six months of transactions; amount matches float to the top. */
@Composable
private fun AttachTransactionDialog(
    receiptAmount: Double?,
    receiptDate: LocalDate?,
    onSelect: (PostingWithDetails) -> Unit,
    onDismiss: () -> Unit
) {
    val postingRepo = koinInject<PostingRepository>()
    val end = remember { today().plus(1, DateTimeUnit.DAY) }
    val start = remember { end.minus(6, DateTimeUnit.MONTH) }
    val postings by remember { postingRepo.watchBetween(start, end) }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val results = remember(postings, query, receiptAmount, receiptDate) {
        val q = query.trim().lowercase()
        postings
            .filter { !it.posting.id.endsWith("_cr") }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.accountName?.lowercase()?.contains(q) == true }
            .sortedWith(
                compareBy<PostingWithDetails> {
                    if (receiptAmount != null && abs(it.posting.amount - receiptAmount) < 0.005) 0 else 1
                }.thenBy {
                    receiptDate?.let { d -> abs(it.posting.date.toEpochDays() - d.toEpochDays()) } ?: 0
                }.thenByDescending { it.posting.date }
            )
            .take(100)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach to transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(MaterialIcons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (results.isEmpty()) {
                    Text(
                        "No transactions in the last six months.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(results, key = { it.posting.id }) { posting ->
                            EntityListItem(
                                title = posting.title,
                                supporting = listOfNotNull(formatDate(posting.posting.date), posting.accountName).joinToString(" · "),
                                trailing = { AmountText(posting.posting.type, posting.posting.amount) },
                                onClick = { onSelect(posting) }
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
