package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Delete
import com.composables.icons.materialicons.filled.Add
import com.composables.icons.materialicons.filled.Arrow_back
import com.composables.icons.materialicons.filled.Edit
import com.composables.icons.materialicons.filled.Link
import com.composables.icons.materialicons.filled.Link_off
import com.composables.icons.materialicons.filled.Open_in_new
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ReceiptWithPosting
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import co.touchlab.kermit.Logger
import kotlin.math.abs
import kotlin.math.roundToLong

private enum class ReceiptFilter(val label: String) {
    ALL("All"), UNATTACHED("Unattached"), ATTACHED("Attached")
}

private val openLog = Logger.withTag("ReceiptOpen")

private data class ReceiptPreviewState(
    val fileName: String,
    val bytes: ByteArray
)

private fun formatTimestamp(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.year}-${dt.monthNumber.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')} " +
        "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}

@Composable
fun ReceiptsScreen(
    cameraButton: ReceiptPickerButton? = null,
    photoButton: ReceiptPickerButton? = null,
    autoOpenCamera: Boolean = false,
    pendingReceiptFile: PlatformFile? = null,
    onPendingReceiptConsumed: () -> Unit = {}
) {
    val receiptRepo = koinInject<ReceiptRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val fileStore = koinInject<ReceiptFileStore>()
    val idGen = koinInject<IdGenerator>()
    val scope = rememberCoroutineScope()
    val receiptsWithPostings by receiptRepo.watchAll().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(ReceiptFilter.ALL) }
    var showAddPage by remember { mutableStateOf(autoOpenCamera || pendingReceiptFile != null) }
    var preview by remember { mutableStateOf<ReceiptPreviewState?>(null) }

    LaunchedEffect(pendingReceiptFile) {
        if (pendingReceiptFile != null) {
            showAddPage = true
        }
    }

    val filtered = remember(receiptsWithPostings, filter) {
        when (filter) {
            ReceiptFilter.ALL -> receiptsWithPostings
            ReceiptFilter.UNATTACHED -> receiptsWithPostings.filter { it.receipt.postingId == null }
            ReceiptFilter.ATTACHED -> receiptsWithPostings.filter { it.receipt.postingId != null }
        }
    }

    if (showAddPage) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipIconButton(onClick = { showAddPage = false }, tooltip = "Back to receipts") {
                    Icon(MaterialIcons.Filled.Arrow_back, contentDescription = "Back")
                }
                Text("New receipt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            ShillingCard {
                CompositionLocalProvider(LocalAutoLaunchCamera provides autoOpenCamera) {
                    AddReceiptForm(
                        cameraButton = cameraButton,
                        photoButton = photoButton,
                        initialFile = pendingReceiptFile,
                        onInitialFileConsumed = onPendingReceiptConsumed,
                        onSave = { name, notes, receiptDate, amount, bytes ->
                            scope.launch {
                                val receiptId = idGen.newId()
                                fileStore.store(receiptId, name, bytes)
                                val receipt = Receipt(
                                    id = receiptId,
                                    filePath = name,
                                    originalName = name,
                                    addedAt = Clock.System.now().toEpochMilliseconds(),
                                    notes = notes.ifBlank { null },
                                    receiptDate = receiptDate,
                                    amount = amount
                                )
                                receiptRepo.save(receipt)
                                showAddPage = false
                            }
                        }
                    )
                }
            }
        }
    } else Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Receipts", "Upload and manage receipts. Attach to transactions when ready.")
        Spacer(Modifier.height(16.dp))

        Button(onClick = { showAddPage = true }) {
            Icon(MaterialIcons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add receipt")
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReceiptFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Text(
                when (filter) {
                    ReceiptFilter.ALL -> "No receipts yet. Add one above."
                    ReceiptFilter.UNATTACHED -> "No unattached receipts."
                    ReceiptFilter.ATTACHED -> "No attached receipts."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.receipt.id }) { item ->
                    ShillingCard {
                        ReceiptRow(
                            item = item,
                            receiptRepo = receiptRepo,
                            postingRepo = postingRepo,
                            fileStore = fileStore,
                            scope = scope,
                            onOpenReceipt = { receipt ->
                                scope.launch {
                                    openLog.i { "Open requested: id=${receipt.id}, name=${receipt.originalName}" }
                                    runCatching {
                                        if (receipt.originalName.isPreviewableImageName()) {
                                            val bytes = fileStore.read(receipt.id)
                                            if (bytes != null) {
                                                openLog.i { "Opening in-app preview: id=${receipt.id}, size=${bytes.size}B" }
                                                preview = ReceiptPreviewState(receipt.originalName, bytes)
                                            } else {
                                                openLog.w { "Preview bytes missing, falling back to external open: id=${receipt.id}" }
                                                fileStore.openExternally(receipt.id, receipt.originalName)
                                            }
                                        } else if (receipt.originalName.isHeifFamilyName()) {
                                            openLog.i { "HEIC/HEIF not previewable in-app; opening externally: id=${receipt.id}" }
                                            fileStore.openExternally(receipt.id, receipt.originalName)
                                        } else {
                                            openLog.i { "Non-image file, opening externally: id=${receipt.id}" }
                                            fileStore.openExternally(receipt.id, receipt.originalName)
                                        }
                                    }
                                    .onFailure { t ->
                                        openLog.e(t) { "Open failed: id=${receipt.id}, name=${receipt.originalName}" }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    preview?.let { current ->
        ReceiptImagePreviewDialog(
            fileName = current.fileName,
            imageBytes = current.bytes,
            onDismiss = { preview = null }
        )
    }
}

@Composable
private fun AddReceiptForm(
    cameraButton: ReceiptPickerButton? = null,
    photoButton: ReceiptPickerButton? = null,
    initialFile: PlatformFile? = null,
    onInitialFileConsumed: () -> Unit = {},
    onSave: (name: String, notes: String, receiptDate: Long?, amount: Double?, bytes: ByteArray) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var pickedBytes by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()

    val handleFile: (PlatformFile?) -> Unit = { file ->
        if (file != null) {
            scope.launch {
                pickedBytes = file.readBytes()
                pickedFileName = file.name
                if (name.isBlank()) name = file.name
            }
        }
    }

    LaunchedEffect(initialFile) {
        if (initialFile != null) {
            pickedBytes = initialFile.readBytes()
            pickedFileName = initialFile.name
            name = initialFile.name
            onInitialFileConsumed()
        }
    }

    val fileLauncher = rememberFilePickerLauncher(type = FileKitType.File(), onResult = handleFile)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            cameraButton?.invoke(handleFile)
            photoButton?.invoke(handleFile)
            Button(onClick = { fileLauncher.launch() }) { Text("File") }
        }

        pickedFileName?.let { fn ->
            val sizeKb = (pickedBytes?.size ?: 0) / 1024
            Text(
                "Selected: $fn (${sizeKb}KB)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Receipt name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("Date (yyyy-mm-dd)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                modifier = Modifier.width(120.dp),
                singleLine = true
            )
        }
        Button(
            onClick = {
                val receiptDate = try {
                    LocalDate.parse(dateText).toEpochDays().toLong()
                } catch (_: Exception) { null }
                val amount = amountText.toDoubleOrNull()
                onSave(name, notes, receiptDate, amount, pickedBytes ?: ByteArray(0))
                name = ""
                notes = ""
                dateText = ""
                amountText = ""
                pickedFileName = null
                pickedBytes = null
            },
            enabled = name.isNotBlank() && pickedBytes != null
        ) { Text("Save") }
    }
}

@Composable
private fun ReceiptRow(
    item: ReceiptWithPosting,
    receiptRepo: ReceiptRepository,
    postingRepo: PostingRepository,
    fileStore: ReceiptFileStore,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenReceipt: (Receipt) -> Unit
) {
    val receipt = item.receipt
    var showAttach by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    ) {
        Text(receipt.originalName, fontWeight = FontWeight.Bold)
        val parts = mutableListOf("Added ${formatTimestamp(receipt.addedAt)}")
        receipt.receiptDate?.let {
            parts += "Date: ${LocalDate.fromEpochDays(it.toInt())}"
        }
        receipt.amount?.let { parts += formatCurrency(it) }
        Text(parts.joinToString(" | "), style = MaterialTheme.typography.labelSmall)
        receipt.notes?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.postingTitle != null) {
            Text(
                "Attached to: ${item.postingTitle} (${item.postingDate})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text("Unattached", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TooltipIconButton(onClick = { onOpenReceipt(receipt) }, tooltip = "Open receipt") {
                Icon(MaterialIcons.Filled.Open_in_new, contentDescription = "Open")
            }
            TooltipIconButton(onClick = { showEdit = !showEdit }, tooltip = "Edit metadata") {
                Icon(MaterialIcons.Filled.Edit, contentDescription = "Edit")
            }
            if (receipt.postingId != null) {
                TooltipIconButton(onClick = {
                    scope.launch { receiptRepo.detach(receipt.id) }
                }, tooltip = "Detach from transaction") {
                    Icon(MaterialIcons.Filled.Link_off, contentDescription = "Detach")
                }
            } else {
                TooltipIconButton(onClick = { showAttach = !showAttach }, tooltip = "Attach to transaction") {
                    Icon(MaterialIcons.Filled.Link, contentDescription = "Attach")
                }
            }
            TooltipIconButton(onClick = {
                scope.launch {
                    fileStore.delete(receipt.id)
                    receiptRepo.delete(receipt.id)
                }
            }, tooltip = "Delete receipt") {
                Icon(MaterialIcons.Filled.Delete, contentDescription = "Delete")
            }
        }

        if (showEdit) {
            EditReceiptMetadata(
                receipt = receipt,
                onSave = { rNotes, receiptDate, amount ->
                    scope.launch {
                        receiptRepo.updateMetadata(receipt.id, rNotes, receiptDate, amount)
                        showEdit = false
                    }
                },
                onCancel = { showEdit = false }
            )
        }

        if (showAttach) {
            AttachToPosting(
                postingRepo = postingRepo,
                onAttach = { postingId ->
                    scope.launch {
                        receiptRepo.attach(receipt.id, postingId)
                        showAttach = false
                    }
                },
                onCancel = { showAttach = false }
            )
        }
    }
}

@Composable
private fun EditReceiptMetadata(
    receipt: Receipt,
    onSave: (notes: String?, receiptDate: Long?, amount: Double?) -> Unit,
    onCancel: () -> Unit
) {
    var notes by remember(receipt.id) { mutableStateOf(receipt.notes ?: "") }
    var dateText by remember(receipt.id) {
        mutableStateOf(receipt.receiptDate?.let { LocalDate.fromEpochDays(it.toInt()).toString() } ?: "")
    }
    var amountText by remember(receipt.id) { mutableStateOf(receipt.amount?.toString() ?: "") }

    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("Date (yyyy-mm-dd)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                modifier = Modifier.width(120.dp),
                singleLine = true
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val receiptDate = try {
                    LocalDate.parse(dateText).toEpochDays().toLong()
                } catch (_: Exception) { null }
                onSave(notes.ifBlank { null }, receiptDate, amountText.toDoubleOrNull())
            }) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun AttachToPosting(
    postingRepo: PostingRepository,
    onAttach: (String) -> Unit,
    onCancel: () -> Unit
) {
    var recentPostings by remember { mutableStateOf(emptyList<finance.shilling.shared.data.PostingWithDetails>()) }
    LaunchedEffect(Unit) {
        recentPostings = postingRepo.loadRecentPostings(20)
    }

    Spacer(Modifier.height(8.dp))
    if (recentPostings.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No postings to attach to.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    } else {
        Column {
            Text("Select a transaction:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            recentPostings.take(10).forEach { posting ->
                TextButton(onClick = { onAttach(posting.posting.id) }) {
                    Text(
                        "${posting.title} | ${posting.posting.date} | ${formatCurrency(posting.posting.amount)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
