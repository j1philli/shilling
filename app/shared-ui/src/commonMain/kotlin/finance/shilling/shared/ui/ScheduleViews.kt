package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Delete
import com.composables.icons.materialicons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun SchedulesView() {
    var selectedType by remember { mutableStateOf(ScheduleType.EXPENSE) }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Schedules", "Manage your recurring and one-off transactions.")
        Spacer(Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScheduleType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = { Text(type.name.lowercase().replaceFirstChar { it.titlecase() } + "s") }
                )
            }
        }
        Spacer(Modifier.padding(top = 12.dp))
        ShillingDivider()
        Spacer(Modifier.padding(top = 12.dp))
        when (selectedType) {
            ScheduleType.EXPENSE -> ExpensesContent()
            ScheduleType.INCOME -> IncomeContent()
            ScheduleType.TRANSFER -> TransfersContent()
        }
    }
}

@Composable
private fun ExpensesContent() {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val scope = rememberCoroutineScope()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())
    val schedules by scheduleRepo.watchAll().collectAsState(initial = emptyList())
    val expenseSchedules = schedules.filter { it.type == ScheduleType.EXPENSE }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AddSectionToggle(
            label = "Add expense",
            expanded = showAdd || editingSchedule != null,
            onToggle = {
                if (editingSchedule != null) editingSchedule = null
                else showAdd = !showAdd
            }
        )
        if (showAdd || editingSchedule != null) {
            ShillingCard {
                AddScheduleForm(
                    accounts = accounts,
                    categories = categories,
                    presetType = ScheduleType.EXPENSE,
                    editingSchedule = editingSchedule,
                    onAdd = { schedule -> scope.launch { scheduleRepo.upsert(schedule) } },
                    onCancel = { editingSchedule = null; showAdd = false }
                )
            }
        }
        if (expenseSchedules.isEmpty()) {
            Text("No expense schedules recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            expenseSchedules.forEach { schedule ->
                ShillingCard {
                    ScheduleRow(
                        schedule = schedule,
                        accounts = accounts,
                        categories = categories,
                        onEdit = { selected -> editingSchedule = selected; showAdd = true },
                        onDelete = { id -> scope.launch { scheduleRepo.delete(id) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomeContent() {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val scope = rememberCoroutineScope()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())
    val schedules by scheduleRepo.watchAll().collectAsState(initial = emptyList())
    val upcoming by windowUseCase.watchWindowForComingFriday().collectAsState(initial = emptyList())
    val incomeSchedules = schedules.filter { it.type == ScheduleType.INCOME }
    val upcomingIncome = upcoming.filter { it.tx.type == ScheduleType.INCOME }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AddSectionToggle(
            label = "Add income",
            expanded = showAdd || editingSchedule != null,
            onToggle = {
                if (editingSchedule != null) editingSchedule = null
                else showAdd = !showAdd
            }
        )
        if (showAdd || editingSchedule != null) {
            ShillingCard {
                AddScheduleForm(
                    accounts = accounts,
                    categories = categories,
                    presetType = ScheduleType.INCOME,
                    editingSchedule = editingSchedule,
                    onAdd = { schedule -> scope.launch { scheduleRepo.upsert(schedule) } },
                    onCancel = { editingSchedule = null; showAdd = false }
                )
            }
        }
        if (incomeSchedules.isEmpty()) {
            Text("No income schedules yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (upcomingIncome.isNotEmpty()) {
                Text("Upcoming this window", style = MaterialTheme.typography.titleMedium)
                upcomingIncome.forEach { item ->
                    ShillingCard {
                        OccurrenceRow(
                            item = item,
                            onMarkPosted = { scope.launch { postingRepo.recordFromOccurrence(it.tx) } },
                            onSkip = { scope.launch { scheduleRepo.upsertException(ScheduleException(scheduleId = it.tx.scheduleId, date = it.tx.date, skip = true)) } },
                            onOverride = { itItem, amount ->
                                scope.launch {
                                    scheduleRepo.upsertException(ScheduleException(scheduleId = itItem.tx.scheduleId, date = itItem.tx.date, skip = false, overrideAmount = amount, overrideAccountId = itItem.tx.accountId, overrideCounterAccountId = itItem.tx.counterAccountId))
                                }
                            }
                        )
                    }
                }
            }
            Text("All income schedules", style = MaterialTheme.typography.titleMedium)
            incomeSchedules.forEach { schedule ->
                ShillingCard {
                    ScheduleRow(
                        schedule = schedule,
                        accounts = accounts,
                        categories = categories,
                        onEdit = { selected -> editingSchedule = selected; showAdd = true },
                        onDelete = { id -> scope.launch { scheduleRepo.delete(id) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun TransfersContent() {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val scope = rememberCoroutineScope()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())
    val schedules by scheduleRepo.watchAll().collectAsState(initial = emptyList())
    val upcoming by windowUseCase.watchWindowForComingFriday().collectAsState(initial = emptyList())
    val transferSchedules = schedules.filter { it.type == ScheduleType.TRANSFER }
    val upcomingTransfers = upcoming.filter { it.tx.type == ScheduleType.TRANSFER }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AddSectionToggle(
            label = "Add transfer",
            expanded = showAdd || editingSchedule != null,
            onToggle = {
                if (editingSchedule != null) editingSchedule = null
                else showAdd = !showAdd
            }
        )
        if (showAdd || editingSchedule != null) {
            ShillingCard {
                AddScheduleForm(
                    accounts = accounts,
                    categories = categories,
                    presetType = ScheduleType.TRANSFER,
                    editingSchedule = editingSchedule,
                    onAdd = { schedule -> scope.launch { scheduleRepo.upsert(schedule) } },
                    onCancel = { editingSchedule = null; showAdd = false }
                )
            }
        }
        if (upcomingTransfers.isNotEmpty()) {
            Text("Upcoming moves", style = MaterialTheme.typography.titleMedium)
            upcomingTransfers.forEach { item ->
                ShillingCard {
                    OccurrenceRow(
                        item = item,
                        onMarkPosted = { scope.launch { postingRepo.recordFromOccurrence(it.tx) } },
                        onSkip = { scope.launch { scheduleRepo.upsertException(ScheduleException(scheduleId = it.tx.scheduleId, date = it.tx.date, skip = true)) } },
                        onOverride = { itItem, amount ->
                            scope.launch {
                                scheduleRepo.upsertException(ScheduleException(scheduleId = itItem.tx.scheduleId, date = itItem.tx.date, skip = false, overrideAmount = amount, overrideAccountId = itItem.tx.accountId, overrideCounterAccountId = itItem.tx.counterAccountId))
                            }
                        }
                    )
                }
            }
        }
        Text("All transfer schedules", style = MaterialTheme.typography.titleMedium)
        if (transferSchedules.isEmpty()) {
            Text("No transfer schedules recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            transferSchedules.forEach { schedule ->
                ShillingCard {
                    ScheduleRow(
                        schedule = schedule,
                        accounts = accounts,
                        categories = categories,
                        onEdit = { selected -> editingSchedule = selected; showAdd = true },
                        onDelete = { id -> scope.launch { scheduleRepo.delete(id) } }
                    )
                }
            }
        }
    }
}

@Composable
fun ExpensesView() {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val scope = rememberCoroutineScope()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())
    val schedules by scheduleRepo.watchAll().collectAsState(initial = emptyList())
    val expenseSchedules = schedules.filter { it.type == ScheduleType.EXPENSE }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("All schedules", style = MaterialTheme.typography.titleLarge)
        Text("Set up your recurring and one-off expenses. Projections update automatically as you edit.")
        AddSectionToggle(
            label = "Add schedule",
            expanded = showAdd || editingSchedule != null,
            onToggle = {
                if (editingSchedule != null) {
                    editingSchedule = null
                } else {
                    showAdd = !showAdd
                }
            }
        )
        if (showAdd || editingSchedule != null) {
            AddScheduleForm(
                accounts = accounts,
                categories = categories,
                presetType = ScheduleType.EXPENSE,
                editingSchedule = editingSchedule,
                onAdd = { schedule ->
                    scope.launch { scheduleRepo.upsert(schedule) }
                },
                onCancel = {
                    editingSchedule = null
                    showAdd = false
                }
            )
        }
        HorizontalDivider()
        if (expenseSchedules.isEmpty()) {
            Text("No expense schedules recorded yet.")
        } else {
            expenseSchedules.forEach { schedule ->
                ScheduleRow(
                    schedule = schedule,
                    accounts = accounts,
                    categories = categories,
                    onEdit = { selected ->
                        editingSchedule = selected
                        showAdd = true
                    },
                    onDelete = { id -> scope.launch { scheduleRepo.delete(id) } }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun IncomeView() {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val scope = rememberCoroutineScope()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())
    val schedules by scheduleRepo.watchAll().collectAsState(initial = emptyList())
    val upcoming by windowUseCase.watchWindowForComingFriday().collectAsState(initial = emptyList())
    val incomeSchedules = schedules.filter { it.type == ScheduleType.INCOME }
    val upcomingIncome = upcoming.filter { it.tx.type == ScheduleType.INCOME }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Income schedules", style = MaterialTheme.typography.titleLarge)
        Text("Keep paychecks and other inflows organized alongside your bills.")
        AddSectionToggle(
            label = "Add income schedule",
            expanded = showAdd || editingSchedule != null,
            onToggle = {
                if (editingSchedule != null) {
                    editingSchedule = null
                } else {
                    showAdd = !showAdd
                }
            }
        )
        if (showAdd || editingSchedule != null) {
            AddScheduleForm(
                accounts = accounts,
                categories = categories,
                presetType = ScheduleType.INCOME,
                editingSchedule = editingSchedule,
                onAdd = { schedule -> scope.launch { scheduleRepo.upsert(schedule) } },
                onCancel = {
                    editingSchedule = null
                    showAdd = false
                }
            )
        }
        HorizontalDivider()
        if (incomeSchedules.isEmpty()) {
            Text("No income schedules yet.")
        } else {
            Text("Upcoming this window", style = MaterialTheme.typography.titleMedium)
            if (upcomingIncome.isEmpty()) {
                Text("No income due in the current window.")
            } else {
                upcomingIncome.forEach { item ->
                    OccurrenceRow(
                        item = item,
                        onMarkPosted = { scope.launch { postingRepo.recordFromOccurrence(it.tx) } },
                        onSkip = { scope.launch { scheduleRepo.upsertException(ScheduleException(scheduleId = it.tx.scheduleId, date = it.tx.date, skip = true)) } },
                        onOverride = { itItem, amount ->
                            scope.launch {
                                scheduleRepo.upsertException(ScheduleException(scheduleId = itItem.tx.scheduleId, date = itItem.tx.date, skip = false, overrideAmount = amount, overrideAccountId = itItem.tx.accountId, overrideCounterAccountId = itItem.tx.counterAccountId))
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
            Text("All income schedules", style = MaterialTheme.typography.titleMedium)
            incomeSchedules.forEach { schedule ->
                ScheduleRow(
                    schedule = schedule,
                    accounts = accounts,
                    categories = categories,
                    onEdit = { selected ->
                        editingSchedule = selected
                        showAdd = true
                    },
                    onDelete = { id -> scope.launch { scheduleRepo.delete(id) } }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun TransfersView() {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val scope = rememberCoroutineScope()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())
    val schedules by scheduleRepo.watchAll().collectAsState(initial = emptyList())
    val upcoming by windowUseCase.watchWindowForComingFriday().collectAsState(initial = emptyList())
    val transferSchedules = schedules.filter { it.type == ScheduleType.TRANSFER }
    val upcomingTransfers = upcoming.filter { it.tx.type == ScheduleType.TRANSFER }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Transfer schedules", style = MaterialTheme.typography.titleLarge)
        Text("Automate moving money between your accounts.")
        AddSectionToggle(
            label = "Add transfer schedule",
            expanded = showAdd || editingSchedule != null,
            onToggle = {
                if (editingSchedule != null) {
                    editingSchedule = null
                } else {
                    showAdd = !showAdd
                }
            }
        )
        if (showAdd || editingSchedule != null) {
            AddScheduleForm(
                accounts = accounts,
                categories = categories,
                presetType = ScheduleType.TRANSFER,
                editingSchedule = editingSchedule,
                onAdd = { schedule -> scope.launch { scheduleRepo.upsert(schedule) } },
                onCancel = {
                    editingSchedule = null
                    showAdd = false
                }
            )
        }
        HorizontalDivider()
        Text("Upcoming moves", style = MaterialTheme.typography.titleMedium)
        if (upcomingTransfers.isEmpty()) {
            Text("No transfers due in the current window.")
        } else {
            UpcomingInlineList(
                upcoming = upcomingTransfers,
                onMarkPosted = { scope.launch { postingRepo.recordFromOccurrence(it.tx) } },
                onSkip = { scope.launch { scheduleRepo.upsertException(ScheduleException(scheduleId = it.tx.scheduleId, date = it.tx.date, skip = true)) } },
                onOverride = { item, amount ->
                    scope.launch {
                        scheduleRepo.upsertException(ScheduleException(scheduleId = item.tx.scheduleId, date = item.tx.date, skip = false, overrideAmount = amount, overrideAccountId = item.tx.accountId, overrideCounterAccountId = item.tx.counterAccountId))
                    }
                }
            )
        }
        HorizontalDivider()
        Text("All transfer schedules", style = MaterialTheme.typography.titleMedium)
        if (transferSchedules.isEmpty()) {
            Text("No transfer schedules recorded yet.")
        } else {
            transferSchedules.forEach { schedule ->
                ScheduleRow(
                    schedule = schedule,
                    accounts = accounts,
                    categories = categories,
                    onEdit = { selected ->
                        editingSchedule = selected
                        showAdd = true
                    },
                    onDelete = { id -> scope.launch { scheduleRepo.delete(id) } }
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScheduleForm(
    accounts: List<Account>,
    categories: List<Category>,
    presetType: ScheduleType? = null,
    editingSchedule: Schedule? = null,
    onAdd: (Schedule) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val idGen = koinInject<IdGenerator>()
    val systemZone = remember { TimeZone.currentSystemDefault() }
    val utc = remember { TimeZone.UTC }
    val today = remember { Clock.System.now().toLocalDateTime(systemZone).date }

    var title by remember { mutableStateOf(editingSchedule?.title ?: "") }
    var amountText by remember { mutableStateOf(editingSchedule?.amount?.toString() ?: "0") }
    var type by remember { mutableStateOf(editingSchedule?.type ?: presetType ?: ScheduleType.EXPENSE) }
    var freq by remember { mutableStateOf(editingSchedule?.freq ?: Frequency.ONCE) }
    var intervalText by remember { mutableStateOf(editingSchedule?.interval?.toString() ?: "0") }
    var startDateText by remember { mutableStateOf((editingSchedule?.startDate ?: today).toString()) }
    var endDateText by remember { mutableStateOf(editingSchedule?.endDate?.toString().orEmpty()) }
    var selectedAccountId by remember { mutableStateOf(editingSchedule?.accountId ?: accounts.firstOrNull()?.id.orEmpty()) }
    var selectedCounterAccountId by remember { mutableStateOf(editingSchedule?.counterAccountId.orEmpty()) }
    var selectedCategoryId by remember { mutableStateOf(editingSchedule?.categoryId.orEmpty()) }
    var datePickerVisible by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = today.atStartOfDayIn(utc).toEpochMilliseconds()
    )

    var selectedDays by remember { mutableStateOf(editingSchedule?.byDayMask?.toSetOfDays() ?: emptySet()) }
    var monthDayText by remember { mutableStateOf(editingSchedule?.byMonthDay?.toString() ?: "0") }
    var nthWeekText by remember { mutableStateOf(editingSchedule?.nthWeekday?.toString() ?: "0") }
    var nthWeekday by remember { mutableStateOf(editingSchedule?.firstWeekdayFromMaskFallback() ?: DayOfWeek.MONDAY) }
    var lastDayFlag by remember { mutableStateOf(editingSchedule?.lastDayFlag ?: false) }
    var autoPay by remember { mutableStateOf(editingSchedule?.autoPay ?: false) }
    var notes by remember { mutableStateOf(editingSchedule?.notes.orEmpty()) }

    LaunchedEffect(editingSchedule?.id) {
        editingSchedule?.let { schedule ->
            title = schedule.title
            amountText = schedule.amount.toString()
            type = schedule.type
            freq = schedule.freq
            intervalText = schedule.interval.toString()
            startDateText = schedule.startDate.toString()
            endDateText = schedule.endDate?.toString().orEmpty()
            selectedAccountId = schedule.accountId
            selectedCounterAccountId = schedule.counterAccountId.orEmpty()
            selectedCategoryId = schedule.categoryId.orEmpty()
            selectedDays = schedule.byDayMask?.toSetOfDays() ?: emptySet()
            monthDayText = schedule.byMonthDay?.toString().orEmpty()
            nthWeekText = schedule.nthWeekday?.toString() ?: "1"
            nthWeekday = schedule.firstWeekdayFromMaskFallback()
            lastDayFlag = schedule.lastDayFlag
            autoPay = schedule.autoPay
            notes = schedule.notes.orEmpty()
        }
    }

    LaunchedEffect(startDateText) {
        runCatching { LocalDate.parse(startDateText) }.getOrNull()?.let { date ->
            datePickerState.selectedDateMillis = date.atStartOfDayIn(utc).toEpochMilliseconds()
        }
    }

    LaunchedEffect(accounts) {
        if (selectedAccountId.isEmpty() && accounts.isNotEmpty()) {
            selectedAccountId = accounts.first().id
        } else if (accounts.none { it.id == selectedAccountId }) {
            selectedAccountId = accounts.firstOrNull()?.id.orEmpty()
        }
    }

    LaunchedEffect(categories) {
        if (selectedCategoryId.isNotEmpty() && categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = ""
        }
    }

    LaunchedEffect(accounts, selectedAccountId, type) {
        if (type == ScheduleType.TRANSFER) {
            val fallback = accounts.firstOrNull { it.id != selectedAccountId }?.id.orEmpty()
            if (selectedCounterAccountId.isEmpty() || selectedCounterAccountId == selectedAccountId || accounts.none { it.id == selectedCounterAccountId }) {
                selectedCounterAccountId = fallback
            }
        } else {
            selectedCounterAccountId = ""
        }
    }

    val amount = amountText.toDoubleOrNull()
    val interval = intervalText.toIntOrNull()?.takeIf { it > 0 } ?: 1
    val startDate = runCatching { LocalDate.parse(startDateText) }.getOrNull()
    val endDate = endDateText.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val needsCounterAccount = type == ScheduleType.TRANSFER
    val hasCounterOptions = accounts.size >= 2
    val counterValid = if (needsCounterAccount) {
        hasCounterOptions && selectedCounterAccountId.isNotBlank() && selectedCounterAccountId != selectedAccountId
    } else {
        true
    }
    val enableSave = title.isNotBlank() && amount != null && startDate != null && counterValid

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (editingSchedule == null) "Add schedule" else "Edit schedule", style = MaterialTheme.typography.titleMedium)
        TextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            singleLine = true,
            maxLines = 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = amountText,
                onValueChange = { amountText = it },
                modifier = Modifier.weight(1f),
                label = { Text("Amount") },
                placeholder = { Text("0.00") }
            )
            if (presetType == null && editingSchedule == null) {
                ButtonDropdown(
                    label = type.name.lowercase().replaceFirstChar { it.titlecase() },
                    options = ScheduleType.values().toList(),
                    onSelect = { type = it },
                    itemLabel = { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } }
                )
            } else {
                Text(type.name.lowercase().replaceFirstChar { it.titlecase() }, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ButtonDropdown(
                label = freq.name.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() },
                options = Frequency.values().toList(),
                onSelect = { freq = it },
                itemLabel = { it.name.replace('_', ' ').lowercase().replaceFirstChar { c -> c.titlecase() } }
            )
            TextField(
                value = intervalText,
                onValueChange = { intervalText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Interval") },
                modifier = Modifier.width(120.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = startDateText,
                onValueChange = { startDateText = it },
                modifier = Modifier.weight(1f),
                label = { Text("Start date (yyyy-MM-dd)") }
            )
            Button(onClick = { datePickerVisible = true }) { Text("Pick date") }
        }
        TextField(
            value = endDateText,
            onValueChange = { endDateText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("End date (optional)") }
        )

        when (freq) {
            Frequency.WEEKLY -> {
                Text("Weekly on", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.values().forEach { day ->
                        val selected = selectedDays.contains(day)
                        DayToggle(day = day, selected = selected) {
                            selectedDays = if (selected) selectedDays - day else selectedDays + day
                        }
                    }
                }
            }
            Frequency.MONTHLY_BY_DAY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = monthDayText,
                        onValueChange = { monthDayText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Day of month") },
                        modifier = Modifier.width(140.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Last day")
                        Switch(checked = lastDayFlag, onCheckedChange = { lastDayFlag = it })
                    }
                }
            }
            Frequency.MONTHLY_BY_NTH_WEEKDAY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = nthWeekText,
                        onValueChange = { nthWeekText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Nth") },
                        modifier = Modifier.width(100.dp)
                    )
                    ButtonDropdown(
                        label = nthWeekday.name.take(3),
                        options = DayOfWeek.values().toList(),
                        onSelect = { nthWeekday = it },
                        itemLabel = { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } }
                    )
                }
            }
            else -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Account", style = MaterialTheme.typography.labelMedium)
            ButtonDropdown(
                label = accounts.firstOrNull { it.id == selectedAccountId }?.name ?: "Unassigned",
                options = accounts,
                onSelect = { selectedAccountId = it.id },
                itemLabel = { it.name },
                enabled = accounts.isNotEmpty()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Category", style = MaterialTheme.typography.labelMedium)
            ButtonDropdown(
                label = categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "Uncategorized",
                options = categories,
                onSelect = { selectedCategoryId = it.id },
                itemLabel = { it.name },
                leadingOption = "Uncategorized" to { selectedCategoryId = "" }
            )
        }

        if (type == ScheduleType.TRANSFER) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Destination", style = MaterialTheme.typography.labelMedium)
                ButtonDropdown(
                    label = accounts.firstOrNull { it.id == selectedCounterAccountId }?.name ?: "Select account",
                    options = accounts.filter { it.id != selectedAccountId },
                    onSelect = { selectedCounterAccountId = it.id },
                    itemLabel = { it.name },
                    enabled = accounts.filter { it.id != selectedAccountId }.isNotEmpty()
                )
            }
            if (!hasCounterOptions) {
                Text("Add a second account to enable transfers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto-pay")
            Switch(checked = autoPay, onCheckedChange = { autoPay = it })
        }

        TextField(
            value = notes,
            onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Notes (optional)") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (enableSave) {
                        val schedule = Schedule(
                            id = editingSchedule?.id ?: idGen.newId(),
                            title = title.trim(),
                            amount = amount!!,
                            type = type,
                            accountId = selectedAccountId,
                            counterAccountId = if (type == ScheduleType.TRANSFER) selectedCounterAccountId else null,
                            categoryId = selectedCategoryId.takeIf { it.isNotBlank() },
                            startDate = startDate!!,
                            endDate = endDate,
                            freq = freq,
                            interval = interval,
                            byDayMask = when (freq) {
                                Frequency.WEEKLY -> selectedDays.toDayMask()
                                Frequency.MONTHLY_BY_NTH_WEEKDAY -> 1 shl nthWeekday.ordinal
                                else -> null
                            },
                            byMonthDay = when (freq) {
                                Frequency.MONTHLY_BY_DAY -> monthDayText.toIntOrNull()?.takeIf { it > 0 } ?: startDate!!.dayOfMonth
                                Frequency.YEARLY -> startDate!!.dayOfMonth
                                else -> null
                            },
                            nthWeekday = if (freq == Frequency.MONTHLY_BY_NTH_WEEKDAY) nthWeekText.toIntOrNull()?.takeIf { it > 0 } ?: 1 else null,
                            lastDayFlag = freq == Frequency.MONTHLY_BY_DAY && lastDayFlag,
                            autoPay = autoPay,
                            notes = notes.ifBlank { null }
                        )
                        onAdd(schedule)
                        if (editingSchedule != null) {
                            onCancel?.invoke()
                        } else {
                            title = ""
                            amountText = "0"
                            startDateText = today.toString()
                            endDateText = ""
                            selectedCounterAccountId = ""
                            selectedCategoryId = ""
                            selectedDays = emptySet()
                            monthDayText = "0"
                            lastDayFlag = false
                            nthWeekText = "0"
                            notes = ""
                        }
                    }
                },
                enabled = enableSave
            ) { Text(if (editingSchedule == null) "Save schedule" else "Save changes") }
            TextButton(onClick = {
                if (editingSchedule != null) {
                    onCancel?.invoke()
                } else {
                    title = ""
                    amountText = "0"
                    startDateText = today.toString()
                    endDateText = ""
                    selectedCounterAccountId = ""
                    selectedCategoryId = ""
                    selectedDays = emptySet()
                    monthDayText = "0"
                    lastDayFlag = false
                    nthWeekText = "0"
                    notes = ""
                }
            }) { Text(if (editingSchedule == null) "Clear" else "Cancel") }
        }

        if (datePickerVisible) {
            DatePickerDialog(
                onDismissRequest = { datePickerVisible = false },
                confirmButton = {
                    TextButton(onClick = {
                        val millis = datePickerState.selectedDateMillis
                        if (millis != null) {
                        val selected = Instant.fromEpochMilliseconds(millis)
                            .toLocalDateTime(utc)
                            .date
                        startDateText = selected.toString()
                        }
                        datePickerVisible = false
                    }) { Text("Use date") }
                },
                dismissButton = {
                    TextButton(onClick = { datePickerVisible = false }) {
                        Text("Cancel")
                    }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

@Composable
fun ScheduleRow(
    schedule: Schedule,
    accounts: List<Account>,
    categories: List<Category>,
    onEdit: (Schedule) -> Unit,
    onDelete: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sourceName = accounts.firstOrNull { it.id == schedule.accountId }?.name ?: "Unassigned"
        val destinationName = schedule.counterAccountId?.let { id -> accounts.firstOrNull { it.id == id }?.name ?: "Unassigned" }
        val category = categories.firstOrNull { it.id == schedule.categoryId }
        Column {
            Text(schedule.title, fontWeight = FontWeight.Bold)
            val description = buildString {
                append("${describeRecurrence(schedule)} • Starts ${schedule.startDate}")
                schedule.endDate?.let { append(" • Ends $it") }
                when {
                    schedule.type == ScheduleType.TRANSFER && destinationName != null -> {
                        append(" • $sourceName -> $destinationName")
                    }
                    schedule.accountId.isNotBlank() -> {
                        append(" • $sourceName")
                    }
                }
            }
            Text(description, style = MaterialTheme.typography.labelSmall)
            category?.let {
                Text("Category: ${it.name}", style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatSigned(schedule.type, schedule.amount), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            TooltipIconButton(onClick = { onEdit(schedule) }, tooltip = "Edit schedule") {
                Icon(MaterialIcons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
            }
            TooltipIconButton(onClick = { onDelete(schedule.id) }, tooltip = "Delete schedule") {
                Icon(MaterialIcons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DayToggle(day: DayOfWeek, selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(day.name.take(3)) }
    )
}
