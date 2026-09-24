package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Attach_file
import com.composables.icons.materialicons.filled.Calendar_month
import com.composables.icons.materialicons.filled.Check_circle
import com.composables.icons.materialicons.filled.Chevron_left
import com.composables.icons.materialicons.filled.Chevron_right
import com.composables.icons.materialicons.filled.Delete
import com.composables.icons.materialicons.filled.Open_in_new
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTxWithAccount
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import co.touchlab.kermit.Logger

private val weeklyOpenLog = Logger.withTag("WeeklyReceiptOpen")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyView() {
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val postingRepo = koinInject<PostingRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val scope = rememberCoroutineScope()
    val systemZone = remember { TimeZone.currentSystemDefault() }
    val utc = remember { TimeZone.UTC }
    val today = Clock.System.now().toLocalDateTime(systemZone).date
    var anchor by remember { mutableStateOf(today) }
    val window = remember(anchor) { windowUseCase.computeFridayWindow(anchor) }
    val upcoming by windowUseCase.watchWindow(window.first, window.second).collectAsState(initial = emptyList())
    var datePickerVisible by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = window.first.atStartOfDayIn(utc).toEpochMilliseconds()
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 900.dp
        val onMarkPosted: (ScheduledTxWithAccount) -> Unit = { item ->
            scope.launch { postingRepo.recordFromOccurrence(item.tx) }
        }
        val onSkip: (ScheduledTxWithAccount) -> Unit = { item ->
            scope.launch { scheduleRepo.upsertException(ScheduleException(scheduleId = item.tx.scheduleId, date = item.tx.date, skip = true)) }
        }
        val onOverride: (ScheduledTxWithAccount, Double) -> Unit = { item, amount ->
            scope.launch {
                scheduleRepo.upsertException(ScheduleException(scheduleId = item.tx.scheduleId, date = item.tx.date, skip = false, overrideAmount = amount, overrideAccountId = item.tx.accountId, overrideCounterAccountId = item.tx.counterAccountId))
            }
        }

        val onPrevWeek = { anchor = anchor.minus(7, DateTimeUnit.DAY) }
        val onNextWeek = { anchor = anchor.plus(7, DateTimeUnit.DAY) }
        val onPickDate = {
            val millis = datePickerState.selectedDateMillis
            if (millis != null) {
                anchor = Instant.fromEpochMilliseconds(millis).toLocalDateTime(utc).date
            }
            datePickerVisible = false
        }

        if (isWide) {
            UpcomingWideLayout(
                maxWidth = maxWidth,
                window = window,
                upcoming = upcoming,
                onMarkPosted = onMarkPosted,
                onSkip = onSkip,
                onOverride = onOverride,
                onPrevWeek = onPrevWeek,
                onNextWeek = onNextWeek,
                onOpenDatePicker = { datePickerVisible = true }
            )
        } else {
            UpcomingStackedLayout(
                window = window,
                upcoming = upcoming,
                onMarkPosted = onMarkPosted,
                onSkip = onSkip,
                onOverride = onOverride,
                onPrevWeek = onPrevWeek,
                onNextWeek = onNextWeek,
                onOpenDatePicker = { datePickerVisible = true }
            )
        }

        if (datePickerVisible) {
            DatePickerDialog(
                onDismissRequest = { datePickerVisible = false },
                confirmButton = { TextButton(onClick = onPickDate) { Text("Use week of date") } },
                dismissButton = { TextButton(onClick = { datePickerVisible = false }) { Text("Cancel") } }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

@Composable
private fun UpcomingStackedLayout(
    window: Pair<kotlinx.datetime.LocalDate, kotlinx.datetime.LocalDate>,
    upcoming: List<ScheduledTxWithAccount>,
    onMarkPosted: (ScheduledTxWithAccount) -> Unit,
    onSkip: (ScheduledTxWithAccount) -> Unit,
    onOverride: (ScheduledTxWithAccount, Double) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onOpenDatePicker: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionHeader(
                "Upcoming",
                "Showing ${window.first} → ${window.second.minus(1, DateTimeUnit.DAY)}"
            )
        }
        item {
            WeekNavigation(onPrevWeek, onNextWeek, onOpenDatePicker)
        }
        item { ShillingDivider() }
        if (upcoming.isEmpty()) {
            item { Text("No expenses due in this window.") }
        } else {
            items(upcoming, key = { it.tx.scheduleId + it.tx.date.toString() + it.posted }) { item ->
                OccurrenceRow(item = item, onMarkPosted = onMarkPosted, onSkip = onSkip, onOverride = onOverride)
                ShillingDivider()
            }
        }
    }
}

@Composable
private fun UpcomingWideLayout(
    maxWidth: Dp,
    window: Pair<kotlinx.datetime.LocalDate, kotlinx.datetime.LocalDate>,
    upcoming: List<ScheduledTxWithAccount>,
    onMarkPosted: (ScheduledTxWithAccount) -> Unit,
    onSkip: (ScheduledTxWithAccount) -> Unit,
    onOverride: (ScheduledTxWithAccount, Double) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onOpenDatePicker: () -> Unit
) {
    val spacing = 24.dp
    val formWidth = maxWidth * 0.4f
    val remaining = maxWidth - formWidth - spacing
    val listWidth = if (remaining <= 0.dp) maxWidth * 0.5f else remaining
    val formScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        Column(
            modifier = Modifier
                .width(formWidth)
                .fillMaxHeight()
                .verticalScroll(formScroll)
        ) {
            SectionHeader(
                "Upcoming",
                "Showing ${window.first} → ${window.second.minus(1, DateTimeUnit.DAY)}"
            )
            Spacer(Modifier.height(12.dp))
            WeekNavigation(onPrevWeek, onNextWeek, onOpenDatePicker)
            Spacer(Modifier.height(12.dp))
            Text("Pick a week to see what's coming due.", style = MaterialTheme.typography.bodySmall)
        }
        Column(
            modifier = Modifier
                .width(listWidth)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                UpcomingList(
                    upcoming = upcoming,
                    onMarkPosted = onMarkPosted,
                    onSkip = onSkip,
                    onOverride = onOverride,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun WeekNavigation(
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onOpenDatePicker: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onPrevWeek, modifier = Modifier.weight(1f)) {
                Icon(MaterialIcons.Filled.Chevron_left, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Previous")
            }
            OutlinedButton(onClick = onNextWeek, modifier = Modifier.weight(1f)) {
                Text("Next")
                Icon(MaterialIcons.Filled.Chevron_right, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Button(onClick = onOpenDatePicker, modifier = Modifier.fillMaxWidth()) {
            Icon(MaterialIcons.Filled.Calendar_month, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Jump to week")
        }
    }
}

@Composable
fun UpcomingList(
    upcoming: List<ScheduledTxWithAccount>,
    onMarkPosted: (ScheduledTxWithAccount) -> Unit,
    onSkip: (ScheduledTxWithAccount) -> Unit,
    onOverride: (ScheduledTxWithAccount, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    if (upcoming.isEmpty()) {
        Column(modifier = modifier.padding(vertical = 8.dp)) {
            Text("No items due in this window.")
        }
        return
    }
    val grouped = upcoming.groupBy { it.tx.date }.entries.sortedBy { it.key }
    LazyColumn(modifier = modifier) {
        grouped.forEach { (date, itemsForDay) ->
            item(key = "header-$date") {
                Text(
                    date.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                ShillingDivider()
            }
            items(itemsForDay, key = { it.tx.scheduleId + it.tx.date.toString() + it.posted }) { item ->
                OccurrenceRow(item = item, onMarkPosted = onMarkPosted, onSkip = onSkip, onOverride = onOverride)
                ShillingDivider()
            }
        }
    }
}

@Composable
fun UpcomingInlineList(
    upcoming: List<ScheduledTxWithAccount>,
    onMarkPosted: (ScheduledTxWithAccount) -> Unit,
    onSkip: (ScheduledTxWithAccount) -> Unit,
    onOverride: (ScheduledTxWithAccount, Double) -> Unit
) {
    if (upcoming.isEmpty()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text("No items due in this window.")
        }
        return
    }
    val grouped = upcoming.groupBy { it.tx.date }.entries.sortedBy { it.key }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        grouped.forEach { (date, itemsForDay) ->
            Text(
                date.toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            ShillingDivider()
            itemsForDay.forEach { item ->
                OccurrenceRow(item = item, onMarkPosted = onMarkPosted, onSkip = onSkip, onOverride = onOverride)
                ShillingDivider()
            }
        }
    }
}

@Composable
fun OccurrenceRow(
    item: ScheduledTxWithAccount,
    onMarkPosted: (ScheduledTxWithAccount) -> Unit,
    onSkip: (ScheduledTxWithAccount) -> Unit,
    onOverride: (ScheduledTxWithAccount, Double) -> Unit
) {
    val receiptRepo = koinInject<ReceiptRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val fileStore = koinInject<ReceiptFileStore>()
    val idGen = koinInject<IdGenerator>()
    val scope = rememberCoroutineScope()
    val tx = item.tx
    var showOverride by remember { mutableStateOf(false) }
    var overrideText by remember { mutableStateOf(tx.amount.toString()) }
    var previewFileName by remember { mutableStateOf<String?>(null) }
    var previewBytes by remember { mutableStateOf<ByteArray?>(null) }
    val amountColor = when (tx.type) {
        ScheduleType.EXPENSE -> MaterialTheme.colorScheme.error
        ScheduleType.INCOME -> MaterialTheme.colorScheme.tertiary
        ScheduleType.TRANSFER -> MaterialTheme.colorScheme.primary
    }

    val postingId = remember(tx) { "${tx.scheduleId}_${tx.date}" }
    val receipts by receiptRepo.watchByPosting(postingId).collectAsState(initial = emptyList())

    val fileLauncher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file != null) {
            scope.launch {
                val bytes = file.readBytes()
                val receiptId = idGen.newId()
                fileStore.store(receiptId, file.name, bytes)
                val receipt = Receipt(
                    id = receiptId,
                    postingId = postingId,
                    filePath = file.name,
                    originalName = file.name,
                    addedAt = Clock.System.now().toEpochMilliseconds()
                )
                receiptRepo.save(receipt)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(tx.title, fontWeight = FontWeight.Bold)
                val accountLabel = item.account?.name ?: "Unassigned"
                val counterLabel = item.counterAccount?.name ?: tx.counterAccountId ?: "Unassigned"
                val rawSubtitle = when (tx.type) {
                    ScheduleType.TRANSFER -> "${tx.date} • $accountLabel -> $counterLabel • transfer"
                    else -> "${tx.date} • $accountLabel • ${tx.type.name.lowercase()}"
                }
                val subtitle = item.category?.name?.let { "$rawSubtitle • $it" } ?: rawSubtitle
                Text(subtitle, style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatSigned(tx),
                    color = amountColor,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.width(8.dp))
                if (item.posted) {
                    Icon(
                        MaterialIcons.Filled.Check_circle,
                        contentDescription = "Settled",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Settled", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    TooltipIconButton(onClick = { fileLauncher.launch() }, tooltip = "Attach receipt") {
                        Icon(MaterialIcons.Filled.Attach_file, contentDescription = "Attach receipt", modifier = Modifier.size(20.dp))
                    }
                } else {
                    val postLabel = when (tx.type) {
                        ScheduleType.INCOME -> "Received"
                        ScheduleType.EXPENSE -> "Paid"
                        ScheduleType.TRANSFER -> "Transfer"
                    }
                    TextButton(onClick = { onMarkPosted(item) }) { Text(postLabel) }
                    TextButton(onClick = { onSkip(item) }) { Text("Skip") }
                    TextButton(onClick = { showOverride = !showOverride }) { Text("Override") }
                }
            }
        }
        if (receipts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            receipts.forEach { receipt ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(receipt.originalName, style = MaterialTheme.typography.labelSmall)
                    TooltipIconButton(onClick = {
                        scope.launch {
                            weeklyOpenLog.i { "Open requested: id=${receipt.id}, name=${receipt.originalName}" }
                            runCatching {
                                if (receipt.originalName.isPreviewableImageName()) {
                                    val bytes = fileStore.read(receipt.id)
                                    if (bytes != null) {
                                        weeklyOpenLog.i { "Opening in-app preview: id=${receipt.id}, size=${bytes.size}B" }
                                        previewFileName = receipt.originalName
                                        previewBytes = bytes
                                    } else {
                                        weeklyOpenLog.w { "Preview bytes missing, falling back to external open: id=${receipt.id}" }
                                        fileStore.openExternally(receipt.id, receipt.originalName)
                                    }
                                } else if (receipt.originalName.isHeifFamilyName()) {
                                    weeklyOpenLog.i { "HEIC/HEIF not previewable in-app; opening externally: id=${receipt.id}" }
                                    fileStore.openExternally(receipt.id, receipt.originalName)
                                } else {
                                    weeklyOpenLog.i { "Non-image file, opening externally: id=${receipt.id}" }
                                    fileStore.openExternally(receipt.id, receipt.originalName)
                                }
                            }
                            .onFailure { t ->
                                weeklyOpenLog.e(t) { "Open failed: id=${receipt.id}, name=${receipt.originalName}" }
                            }
                        }
                    }, tooltip = "Open receipt") {
                        Icon(MaterialIcons.Filled.Open_in_new, contentDescription = "Open receipt", modifier = Modifier.size(18.dp))
                    }
                    TooltipIconButton(onClick = {
                        scope.launch {
                            fileStore.delete(receipt.id)
                            receiptRepo.delete(receipt.id)
                        }
                    }, tooltip = "Remove receipt") {
                        Icon(MaterialIcons.Filled.Delete, contentDescription = "Remove receipt", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (showOverride && !item.posted) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = overrideText,
                    onValueChange = { overrideText = it },
                    label = { Text("Amount") },
                    modifier = Modifier.width(160.dp),
                    singleLine = true
                )
                TextButton(onClick = {
                    overrideText.toDoubleOrNull()?.let { amount ->
                        onOverride(item, amount)
                        showOverride = false
                    }
                }) { Text("Save override") }
            }
        }
    }

    val currentPreviewName = previewFileName
    val currentPreviewBytes = previewBytes
    if (currentPreviewName != null && currentPreviewBytes != null) {
        ReceiptImagePreviewDialog(
            fileName = currentPreviewName,
            imageBytes = currentPreviewBytes,
            onDismiss = {
                previewFileName = null
                previewBytes = null
            }
        )
    }
}
