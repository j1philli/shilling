package finance.shilling.shared.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.generateOccurrences
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ScheduleRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.koin.compose.koinInject
import finance.shilling.shared.presentation.describeRecurrence
import finance.shilling.shared.presentation.firstWeekdayFromMaskFallback
import finance.shilling.shared.presentation.formatDate
import finance.shilling.shared.presentation.fullLabel
import finance.shilling.shared.presentation.intervalUnit
import finance.shilling.shared.presentation.label
import finance.shilling.shared.presentation.parseAmountInput
import finance.shilling.shared.presentation.pluralLabel
import finance.shilling.shared.presentation.shortLabel
import finance.shilling.shared.presentation.today
import finance.shilling.shared.presentation.weekOfMonth
import finance.shilling.shared.presentation.formatAmountInput
import finance.shilling.shared.presentation.SchedulesViewModel
import org.koin.compose.viewmodel.koinViewModel

private const val NEW_SCHEDULE_PREFIX = "new:"

@Composable
fun SchedulesView(onOpenSchedule: (id: String?, type: ScheduleType?) -> Unit,
    title: String = "Schedules",
    headerBottom: (@Composable () -> Unit)? = null,
    viewModel: SchedulesViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val filter = state.filter
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    ListDetailLayout(
        selectedKey = selectedKey,
        onDismissDetail = { selectedKey = null },
        list = { twoPane ->
            val open: (String?) -> Unit = { id ->
                if (twoPane) selectedKey = id ?: "$NEW_SCHEDULE_PREFIX${filter?.name ?: ""}"
                else onOpenSchedule(id, filter)
            }
            ScreenScaffold(
                title = title,
                headerBottom = headerBottom,
                actions = { AddButton("Add", onClick = { open(null) }) }
            ) { padding ->
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    item(key = "filters") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            FilterChip(selected = filter == null, onClick = { viewModel.setFilter(null) }, label = { Text("All") })
                            state.filterOptions.forEach { type ->
                                FilterChip(
                                    selected = filter == type,
                                    onClick = { viewModel.setFilter(type) },
                                    label = { Text(type.pluralLabel) }
                                )
                            }
                        }
                    }
                    val empty = state.empty
                    if (empty != null) {
                        item(key = "empty") {
                            EmptyState(
                                title = empty.title,
                                message = empty.message,
                                actionLabel = empty.actionLabel,
                                onAction = { open(null) }
                            )
                        }
                    } else {
                        state.groups.forEach { group ->
                            group.header?.let { header ->
                                item(key = "header-$header") { ListSectionHeader(header) }
                            }
                            items(group.rows, key = { it.id }) { row ->
                                EntityListItem(
                                    title = row.title,
                                    supporting = row.supporting,
                                    leading = { ColorDot(colorFromHex(row.categoryColor)) },
                                    trailing = { Text(row.amount, color = amountColor(row.type), style = MaterialTheme.typography.bodyLarge) },
                                    selected = twoPane && selectedKey == row.id,
                                    onClick = { open(row.id) }
                                )
                            }
                        }
                    }
                }
            }
        },
        detail = { key ->
            val isNew = key.startsWith(NEW_SCHEDULE_PREFIX)
            ScheduleEditor(
                scheduleId = key.takeUnless { isNew },
                presetType = if (isNew) key.removePrefix(NEW_SCHEDULE_PREFIX).takeIf { it.isNotEmpty() }
                    ?.let { ScheduleType.valueOf(it) } else null,
                navIcon = ScreenNavIcon.CLOSE,
                onClose = { selectedKey = null },
                onSaved = { selectedKey = null }
            )
        },
        emptyDetail = { EmptyState(title = "No schedule selected", message = "Choose a schedule to edit it.") }
    )
}


@Composable
fun ScheduleEditor(
    scheduleId: String?,
    presetType: ScheduleType?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val scheduleRepo = koinInject<ScheduleRepository>()
    if (scheduleId == null) {
        ScheduleForm(existing = null, presetType = presetType, navIcon = navIcon, onClose = onClose, onSaved = onSaved)
        return
    }
    val loadable = rememberLoadable(scheduleId) {
        scheduleRepo.watchAll().map { list -> list.firstOrNull { it.id == scheduleId } }
    }
    when (loadable) {
        Loadable.Loading -> EditorPlaceholder("Schedule", navIcon, onClose, loading = true, missingMessage = "")
        is Loadable.Ready -> loadable.value?.let { schedule ->
            ScheduleForm(existing = schedule, presetType = null, navIcon = navIcon, onClose = onClose, onSaved = onSaved)
        } ?: EditorPlaceholder("Schedule", navIcon, onClose, loading = false, missingMessage = "This schedule was deleted.")
    }
}

private val nthLabels = listOf("First", "Second", "Third", "Fourth", "Fifth")

@Composable
private fun ScheduleForm(
    existing: Schedule?,
    presetType: ScheduleType?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val scheduleRepo = koinInject<ScheduleRepository>()
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val idGen = koinInject<IdGenerator>()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val accounts by remember { accountRepo.watchAll() }.collectAsState(initial = emptyList())
    val categories by remember { categoryRepo.watchAll() }.collectAsState(initial = emptyList())
    val key = existing?.id

    val initialStart = existing?.startDate ?: today()
    var type by rememberSaveable(key) { mutableStateOf(existing?.type ?: presetType ?: ScheduleType.EXPENSE) }
    var title by rememberSaveable(key) { mutableStateOf(existing?.title.orEmpty()) }
    var amountText by rememberSaveable(key) { mutableStateOf(existing?.amount?.let(::formatAmountInput).orEmpty()) }
    var accountId by rememberSaveable(key) { mutableStateOf(existing?.accountId?.takeIf { it.isNotBlank() }) }
    var toAccountId by rememberSaveable(key) { mutableStateOf(existing?.counterAccountId) }
    var categoryId by rememberSaveable(key) { mutableStateOf(existing?.categoryId) }
    var freq by rememberSaveable(key) { mutableStateOf(existing?.freq ?: Frequency.MONTHLY_BY_DAY) }
    var intervalText by rememberSaveable(key) { mutableStateOf((existing?.interval ?: 1).toString()) }
    var startEpochDay by rememberSaveable(key) { mutableLongStateOf(initialStart.toEpochDays()) }
    var endEpochDay by rememberSaveable(key) { mutableStateOf(existing?.endDate?.toEpochDays()?.toLong()) }
    var weekdayMask by rememberSaveable(key) { mutableIntStateOf(existing?.byDayMask ?: 0) }
    var monthDayText by rememberSaveable(key) { mutableStateOf((existing?.byMonthDay ?: initialStart.day).toString()) }
    var lastDay by rememberSaveable(key) { mutableStateOf(existing?.lastDayFlag ?: false) }
    var nth by rememberSaveable(key) { mutableIntStateOf(existing?.nthWeekday ?: initialStart.weekOfMonth()) }
    var nthWeekday by rememberSaveable(key) { mutableStateOf(existing?.firstWeekdayFromMaskFallback() ?: initialStart.dayOfWeek) }
    var autoPay by rememberSaveable(key) { mutableStateOf(existing?.autoPay ?: false) }
    var notes by rememberSaveable(key) { mutableStateOf(existing?.notes.orEmpty()) }

    LaunchedEffect(accounts) {
        if (accountId == null || accounts.none { it.id == accountId }) accountId = accounts.firstOrNull()?.id
    }

    val startDate = LocalDate.fromEpochDays(startEpochDay)
    val endDate = endEpochDay?.let { LocalDate.fromEpochDays(it) }
    val amount = parseAmountInput(amountText)
    val interval = intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val monthDay = monthDayText.toIntOrNull()
    val isTransfer = type == ScheduleType.TRANSFER
    val errors = buildList {
        if (isTransfer && (toAccountId == null || toAccountId == accountId)) add("Choose a different destination account")
        if (freq == Frequency.MONTHLY_BY_DAY && !lastDay && (monthDay == null || monthDay !in 1..31)) add("Day of month must be 1–31")
        if (endDate != null && endDate < startDate) add("End date is before the start date")
    }
    val canSave = title.isNotBlank() && amount != null && amount > 0 && accountId != null && errors.isEmpty()

    val draft = Schedule(
        id = existing?.id ?: "draft",
        title = title.trim(),
        amount = amount ?: 0.0,
        type = type,
        accountId = accountId.orEmpty(),
        counterAccountId = if (isTransfer) toAccountId else null,
        categoryId = categoryId,
        startDate = startDate,
        endDate = if (freq == Frequency.ONCE) null else endDate,
        freq = freq,
        interval = if (freq.intervalUnit(interval) != null) interval else 1,
        byDayMask = when (freq) {
            Frequency.WEEKLY -> weekdayMask.takeIf { it != 0 } ?: (1 shl startDate.dayOfWeek.ordinal)
            Frequency.MONTHLY_BY_NTH_WEEKDAY -> 1 shl nthWeekday.ordinal
            else -> null
        },
        byMonthDay = when (freq) {
            Frequency.MONTHLY_BY_DAY -> monthDay ?: startDate.day
            Frequency.YEARLY -> startDate.day
            else -> null
        },
        nthWeekday = if (freq == Frequency.MONTHLY_BY_NTH_WEEKDAY) nth else null,
        lastDayFlag = freq == Frequency.MONTHLY_BY_DAY && lastDay,
        autoPay = autoPay,
        notes = notes.trim().ifBlank { null }
    )
    val nextOccurrence = remember(draft) {
        val from = maxOf(today(), draft.startDate)
        generateOccurrences(draft, from, from.plus(3, DateTimeUnit.YEAR)).firstOrNull()?.date
    }

    EditorScaffold(
        title = existing?.title ?: "New schedule",
        navIcon = navIcon,
        onClose = onClose,
        saveEnabled = canSave,
        onSave = {
            scope.launch {
                scheduleRepo.upsert(draft.copy(id = existing?.id ?: idGen.newId()))
                snackbar.show(if (existing == null) "Schedule added" else "Schedule updated")
                onSaved()
            }
        },
        delete = existing?.let { schedule ->
            DeleteConfirmation(
                title = "Delete ${schedule.title}?",
                message = "Transactions already recorded from this schedule are deleted too. This can't be undone.",
                confirmLabel = "Delete schedule",
                onConfirm = {
                    scope.launch {
                        scheduleRepo.delete(schedule.id)
                        onSaved()
                        snackbar.show("${schedule.title} deleted")
                    }
                }
            )
        }
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ScheduleType.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = type == option,
                    onClick = { type = option },
                    shape = SegmentedButtonDefaults.itemShape(index, ScheduleType.entries.size)
                ) { Text(option.label) }
            }
        }
        TextInputField(
            value = title,
            onValueChange = { title = it },
            label = "Name",
            placeholder = when (type) {
                ScheduleType.EXPENSE -> "e.g. Rent"
                ScheduleType.INCOME -> "e.g. Paycheck"
                ScheduleType.TRANSFER -> "e.g. Savings"
            }
        )
        AmountField(value = amountText, onValueChange = { amountText = it })
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
            noneOption = "Uncategorized"
        )

        ListSectionHeader("Timing")
        DropdownField(
            label = "Repeats",
            selected = freq,
            options = Frequency.entries,
            optionLabel = { it.label },
            onSelect = { it?.let { f -> freq = f } }
        )
        DateField(
            value = startDate,
            onValueChange = { startEpochDay = it.toEpochDays() },
            label = if (freq == Frequency.ONCE) "Date" else "Starts on"
        )
        freq.intervalUnit(interval)?.let { unit ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                NumberField(
                    value = intervalText,
                    onValueChange = { intervalText = it },
                    label = "Every",
                    modifier = Modifier.width(120.dp)
                )
                Text(unit, style = MaterialTheme.typography.bodyLarge)
            }
        }
        when (freq) {
            Frequency.WEEKLY -> {
                Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val effectiveMask = weekdayMask.takeIf { it != 0 } ?: (1 shl startDate.dayOfWeek.ordinal)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    DayOfWeek.entries.forEach { day ->
                        val bit = 1 shl day.ordinal
                        val isOn = effectiveMask and bit != 0
                        FilterChip(
                            selected = isOn,
                            onClick = {
                                val next = effectiveMask xor bit
                                if (next != 0) weekdayMask = next
                            },
                            label = { Text(day.shortLabel) }
                        )
                    }
                }
            }
            Frequency.MONTHLY_BY_DAY -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Switch(checked = lastDay, onCheckedChange = { lastDay = it })
                    Text("Last day of the month", style = MaterialTheme.typography.bodyLarge)
                }
                if (!lastDay) {
                    NumberField(
                        value = monthDayText,
                        onValueChange = { monthDayText = it },
                        label = "Day of month",
                        isError = monthDay == null || monthDay !in 1..31,
                        supportingText = "Months without this day are skipped. Use \"Last day\" for month-end bills.",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Frequency.MONTHLY_BY_NTH_WEEKDAY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    DropdownField(
                        label = "Week",
                        selected = nth,
                        options = (1..5).toList(),
                        optionLabel = { nthLabels[it - 1] },
                        onSelect = { it?.let { n -> nth = n } },
                        modifier = Modifier.weight(1f)
                    )
                    DropdownField(
                        label = "Day",
                        selected = nthWeekday,
                        options = DayOfWeek.entries,
                        optionLabel = { it.fullLabel },
                        onSelect = { it?.let { d -> nthWeekday = d } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            else -> Unit
        }
        if (freq != Frequency.ONCE) {
            DateField(
                value = endDate,
                onValueChange = { endEpochDay = it.toEpochDays() },
                label = "Ends on",
                placeholder = "Never",
                onClear = { endEpochDay = null }
            )
        }
        Text(
            nextOccurrence?.let { "Next: ${formatDate(it)}" } ?: "No upcoming dates",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        errors.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        ListSectionHeader("Details")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Switch(checked = autoPay, onCheckedChange = { autoPay = it })
            Column {
                Text("Auto-pay", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Labels it in Plan as paid automatically by your bank.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextInputField(value = notes, onValueChange = { notes = it }, label = "Notes", singleLine = false)
    }
}
