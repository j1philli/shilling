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
import finance.shilling.shared.presentation.ScheduleEditorViewModel
import finance.shilling.shared.presentation.EditorLoad
import org.koin.core.parameter.parametersOf

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
    val viewModel = koinViewModel<ScheduleEditorViewModel>(key = "schedule-${scheduleId ?: "new-${presetType?.name}"}") {
        parametersOf(scheduleId, presetType)
    }
    val state by viewModel.state.collectAsState()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val f = state.fields

    when (state.load) {
        EditorLoad.LOADING -> EditorPlaceholder("Schedule", navIcon, onClose, loading = true, missingMessage = "")
        EditorLoad.MISSING -> EditorPlaceholder("Schedule", navIcon, onClose, loading = false, missingMessage = state.missingMessage)
        EditorLoad.READY -> EditorScaffold(
            title = state.title,
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
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                state.types.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = f.type == option,
                        onClick = { viewModel.setType(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, state.types.size)
                    ) { Text(option.label) }
                }
            }
            TextInputField(value = f.title, onValueChange = viewModel::setTitle, label = "Name", placeholder = state.namePlaceholder)
            AmountField(value = f.amountText, onValueChange = viewModel::setAmountText)
            DropdownField(
                label = state.accountLabel,
                selected = state.accounts.firstOrNull { it.id == f.accountId },
                options = state.accounts,
                optionLabel = { it.label },
                onSelect = { viewModel.setAccount(it?.id) },
                enabled = state.accounts.isNotEmpty(),
                supportingText = state.accountHint
            )
            if (state.isTransfer) {
                DropdownField(
                    label = "To account",
                    selected = state.accounts.firstOrNull { it.id == f.toAccountId },
                    options = state.toAccountChoices,
                    optionLabel = { it.label },
                    onSelect = { viewModel.setToAccount(it?.id) },
                    enabled = state.accounts.size >= 2,
                    supportingText = state.toAccountHint
                )
            }
            DropdownField(
                label = "Category",
                selected = state.categories.firstOrNull { it.id == f.categoryId },
                options = state.categories,
                optionLabel = { it.label },
                onSelect = { viewModel.setCategory(it?.id) },
                noneOption = "Uncategorized"
            )

            ListSectionHeader("Timing")
            DropdownField(
                label = "Repeats",
                selected = state.frequencies.first { it.frequency == f.frequency },
                options = state.frequencies,
                optionLabel = { it.label },
                onSelect = { it?.let { choice -> viewModel.setFrequency(choice.frequency) } }
            )
            DateField(
                value = LocalDate.fromEpochDays(f.startEpochDay),
                onValueChange = { viewModel.setStart(it.toEpochDays()) },
                label = state.startLabel
            )
            state.intervalUnit?.let { unit ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    NumberField(
                        value = f.intervalText,
                        onValueChange = viewModel::setIntervalText,
                        label = "Every",
                        modifier = Modifier.width(120.dp)
                    )
                    Text(unit, style = MaterialTheme.typography.bodyLarge)
                }
            }
            when (f.frequency) {
                Frequency.WEEKLY -> {
                    Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        state.weekdays.forEach { day ->
                            FilterChip(
                                selected = state.effectiveWeekdayMask and (1 shl day.index) != 0,
                                onClick = { viewModel.toggleWeekday(day.index) },
                                label = { Text(day.shortLabel) }
                            )
                        }
                    }
                }
                Frequency.MONTHLY_BY_DAY -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Switch(checked = f.lastDay, onCheckedChange = viewModel::setLastDay)
                        Text("Last day of the month", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (!f.lastDay) {
                        NumberField(
                            value = f.monthDayText,
                            onValueChange = viewModel::setMonthDayText,
                            label = "Day of month",
                            isError = state.monthDayError,
                            supportingText = state.monthDayHint,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Frequency.MONTHLY_BY_NTH_WEEKDAY -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        DropdownField(
                            label = "Week",
                            selected = state.nthOptions[f.nth - 1],
                            options = state.nthOptions,
                            optionLabel = { it.label },
                            onSelect = { it?.let { choice -> viewModel.setNth(choice.id.toInt()) } },
                            modifier = Modifier.weight(1f)
                        )
                        DropdownField(
                            label = "Day",
                            selected = state.weekdays[f.nthWeekdayIndex],
                            options = state.weekdays,
                            optionLabel = { it.fullLabel },
                            onSelect = { it?.let { day -> viewModel.setNthWeekday(day.index) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                else -> Unit
            }
            if (state.showsEndDate) {
                DateField(
                    value = f.endEpochDay?.let { LocalDate.fromEpochDays(it) },
                    onValueChange = { viewModel.setEnd(it.toEpochDays()) },
                    label = "Ends on",
                    placeholder = "Never",
                    onClear = { viewModel.setEnd(null) }
                )
            }
            Text(
                state.nextOccurrence,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.errors.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            ListSectionHeader("Details")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Switch(checked = f.autoPay, onCheckedChange = viewModel::setAutoPay)
                Column {
                    Text("Auto-pay", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.autoPayHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextInputField(value = f.notes, onValueChange = viewModel::setNotes, label = "Notes", singleLine = false)
        }
    }
}
