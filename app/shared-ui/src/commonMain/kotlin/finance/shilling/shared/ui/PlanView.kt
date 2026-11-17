package finance.shilling.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Check
import com.composables.icons.materialicons.filled.Check_circle
import com.composables.icons.materialicons.filled.Expand_less
import com.composables.icons.materialicons.filled.Expand_more
import com.composables.icons.materialicons.filled.More_vert
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.presentation.ChangeAmountPrompt
import finance.shilling.shared.presentation.OccurrenceUi
import finance.shilling.shared.presentation.PlanCategoryUi
import finance.shilling.shared.presentation.PlanGrouping
import finance.shilling.shared.presentation.PlanLineUi
import finance.shilling.shared.presentation.PlanOverviewViewModel
import finance.shilling.shared.presentation.PlanRequests
import finance.shilling.shared.presentation.PlanSection
import finance.shilling.shared.presentation.PlanSummaryUi
import finance.shilling.shared.presentation.Undoable
import finance.shilling.shared.presentation.parseAmountInput
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Plan: everything about expected money. Overview shows a week or month (actionable by day,
 * or broken down by category); Schedules, Categories and Accounts manage what feeds it.
 */
@Composable
fun PlanView(
    onOpenTransaction: (String) -> Unit,
    onOpenSchedule: (id: String?, type: ScheduleType?) -> Unit,
    onOpenCategory: (String?) -> Unit,
    onOpenAccount: (String?) -> Unit,
    overviewViewModel: PlanOverviewViewModel = koinViewModel()
) {
    val planRequests = koinInject<PlanRequests>()
    val request by planRequests.pending.collectAsState()
    var sectionName by rememberSaveable { mutableStateOf(PlanSection.OVERVIEW.name) }
    LaunchedEffect(request) {
        request?.let {
            sectionName = it.section.name
            it.period?.let(overviewViewModel::setPeriod)
            planRequests.consume()
        }
    }
    val section = PlanSection.valueOf(sectionName)
    val tabs: @Composable () -> Unit = {
        SecondaryScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = screenGutter() - Spacing.lg) {
            PlanSection.entries.forEach { option ->
                Tab(
                    selected = section == option,
                    onClick = { sectionName = option.name },
                    text = { Text(option.label) }
                )
            }
        }
    }
    when (section) {
        PlanSection.OVERVIEW -> PlanOverview(
            viewModel = overviewViewModel,
            onOpenTransaction = onOpenTransaction,
            onOpenSchedule = { onOpenSchedule(it, null) },
            headerBottom = tabs
        )
        PlanSection.SCHEDULES -> SchedulesView(onOpenSchedule = onOpenSchedule, title = "Plan", headerBottom = tabs)
        PlanSection.CATEGORIES -> CategoriesView(onOpenCategory = onOpenCategory, title = "Plan", headerBottom = tabs)
        PlanSection.ACCOUNTS -> AccountsView(onOpenAccount = onOpenAccount, title = "Plan", headerBottom = tabs)
    }
}

/** Shows an action's confirmation with Undo. */
fun SnackbarController.showUndoable(result: Undoable?) {
    result?.let { showUndo(it.message, it.undo) }
}

@Composable
private fun PlanOverview(
    viewModel: PlanOverviewViewModel,
    onOpenTransaction: (String) -> Unit,
    onOpenSchedule: (String) -> Unit,
    headerBottom: @Composable () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var amountTarget by remember { mutableStateOf<Pair<String, ChangeAmountPrompt>?>(null) }
    val act: (suspend () -> Undoable?) -> Unit = { action -> scope.launch { snackbar.showUndoable(action()) } }

    ScreenScaffold(title = "Plan", subtitle = state.subtitle, maxContentWidth = 840.dp, headerBottom = headerBottom) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        state.periods.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = state.period == option,
                                onClick = { viewModel.setPeriod(option) },
                                shape = SegmentedButtonDefaults.itemShape(index, state.periods.size)
                            ) { Text(option.label) }
                        }
                    }
                    PeriodNavigator(
                        label = state.rangeLabel,
                        previousLabel = state.previousLabel,
                        nextLabel = state.nextLabel,
                        onPrevious = { viewModel.step(-1) },
                        onNext = { viewModel.step(1) },
                        onPick = { showPicker = true },
                        resetLabel = state.resetLabel,
                        onReset = viewModel::resetToCurrent
                    )
                    PlanSummaryCard(state.summary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        state.groupings.forEach { option ->
                            FilterChip(
                                selected = state.grouping == option,
                                onClick = { viewModel.setGrouping(option) },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }
            }
            val emptyTitle = state.emptyTitle
            if (emptyTitle != null) {
                item(key = "empty") {
                    EmptyState(title = emptyTitle, message = state.emptyMessage, modifier = Modifier.padding(top = Spacing.xl))
                }
            } else if (state.grouping == PlanGrouping.BY_DAY) {
                state.days.forEach { day ->
                    item(key = "header-${day.header}") { ListSectionHeader(day.header) }
                    items(day.rows, key = { it.key }) { row ->
                        OccurrenceRow(
                            row = row,
                            onMarkPosted = { act { viewModel.markPosted(row.key) } },
                            onUnmark = { act { viewModel.unmark(row.key) } },
                            onSkip = { act { viewModel.skip(row.key) } },
                            onChangeAmount = { viewModel.changeAmountPrompt(row.key)?.let { amountTarget = row.key to it } },
                            onOpenTransaction = { row.postingId?.let(onOpenTransaction) }
                        )
                    }
                }
            } else {
                state.categories.forEach { group ->
                    item(key = "cat-${group.key}") {
                        CategoryHeaderRow(group = group, onToggle = { viewModel.toggleCategory(group.key) })
                    }
                    if (group.expanded) {
                        items(group.lines, key = { "line-${group.key}-${it.key}" }) { line ->
                            PlanLineRow(
                                line = line,
                                onClick = {
                                    if (line.isSchedule) onOpenSchedule(line.key)
                                    else line.postingId?.let(onOpenTransaction)
                                }
                            )
                        }
                    }
                    item(key = "div-${group.key}") { HorizontalDivider() }
                }
            }
        }
    }

    if (showPicker) {
        DatePickerModal(
            initial = LocalDate.fromEpochDays(state.rangeStartEpochDay),
            confirmLabel = "Go to ${state.period.label.lowercase()}",
            onConfirm = {
                viewModel.jumpTo(it.toEpochDays())
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    amountTarget?.let { (key, prompt) ->
        ChangeAmountDialog(
            prompt = prompt,
            onConfirm = { text ->
                amountTarget = null
                act { viewModel.changeAmount(key, text) }
            },
            onDismiss = { amountTarget = null }
        )
    }
}

@Composable
private fun PlanSummaryCard(summary: PlanSummaryUi) {
    ShillingCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryFigure("Income", summary.income, MaterialTheme.colorScheme.tertiary)
            SummaryFigure("Expenses", summary.expenses, MaterialTheme.colorScheme.onSurface)
            SummaryFigure(
                summary.netLabel,
                summary.net,
                if (summary.netPositive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                alignEnd = true
            )
        }
    }
}

@Composable
private fun SummaryFigure(label: String, value: String, color: Color, alignEnd: Boolean = false) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

@Composable
private fun CategoryHeaderRow(group: PlanCategoryUi, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.md, horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ColorDot(colorFromHex(group.color))
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleSmall)
            Text(
                group.countLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(group.total, color = amountColor(group.totalType), style = MaterialTheme.typography.bodyLarge)
        Icon(
            if (group.expanded) MaterialIcons.Filled.Expand_less else MaterialIcons.Filled.Expand_more,
            contentDescription = if (group.expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlanLineRow(line: PlanLineUi, onClick: () -> Unit) {
    EntityListItem(
        title = line.title,
        supporting = line.supporting,
        trailing = { Text(line.amount, color = amountColor(line.type), style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick,
        modifier = Modifier.padding(start = Spacing.lg)
    )
}

@Composable
private fun OccurrenceRow(
    row: OccurrenceUi,
    onMarkPosted: () -> Unit,
    onUnmark: () -> Unit,
    onSkip: () -> Unit,
    onChangeAmount: () -> Unit,
    onOpenTransaction: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    EntityListItem(
        title = row.title,
        supporting = row.supporting,
        leading = { ColorDot(colorFromHex(row.categoryColor)) },
        onClick = { if (row.posted) onOpenTransaction() else menuOpen = true },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(row.amount, color = amountColor(row.type), style = MaterialTheme.typography.bodyLarge)
                if (row.posted) {
                    Icon(
                        MaterialIcons.Filled.Check_circle,
                        contentDescription = row.postedLabel,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    FilledTonalIconButton(onClick = onMarkPosted) {
                        Icon(MaterialIcons.Filled.Check, contentDescription = row.markLabel)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(MaterialIcons.Filled.More_vert, contentDescription = "More actions for ${row.title}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (row.posted) {
                            DropdownMenuItem(
                                text = { Text("View transaction") },
                                onClick = { menuOpen = false; onOpenTransaction() }
                            )
                            if (row.canUnmark) {
                                DropdownMenuItem(
                                    text = { Text(row.unmarkLabel) },
                                    onClick = { menuOpen = false; onUnmark() }
                                )
                            }
                        } else {
                            DropdownMenuItem(
                                text = { Text(row.markLabel) },
                                onClick = { menuOpen = false; onMarkPosted() }
                            )
                            DropdownMenuItem(
                                text = { Text("Change amount…") },
                                onClick = { menuOpen = false; onChangeAmount() }
                            )
                            DropdownMenuItem(
                                text = { Text("Skip this time") },
                                onClick = { menuOpen = false; onSkip() }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ChangeAmountDialog(
    prompt: ChangeAmountPrompt,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(prompt.initialText) }
    val valid = parseAmountInput(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    prompt.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AmountField(value = text, onValueChange = { text = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
