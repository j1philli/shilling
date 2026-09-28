package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.composables.icons.materialicons.filled.Expand_less
import com.composables.icons.materialicons.filled.Expand_more
import finance.shilling.shared.data.Category
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Check
import com.composables.icons.materialicons.filled.Check_circle
import com.composables.icons.materialicons.filled.More_vert
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTxWithAccount
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.koin.compose.koinInject

enum class PlanPeriod(val label: String) { WEEK("Week"), MONTH("Month") }

enum class PlanSection(val label: String) {
    OVERVIEW("Overview"), SCHEDULES("Schedules"), CATEGORIES("Categories"), ACCOUNTS("Accounts")
}

/** Asks Plan to open a specific section (and period), e.g. from a Home tile. */
data class PlanRequest(val section: PlanSection, val period: PlanPeriod? = null)

private enum class PlanGrouping(val label: String) { BY_DAY("By day"), BY_CATEGORY("By category") }

/** One schedule's (or one-off transaction's) occurrences within the period. */
private data class PlanLine(
    val key: String,
    val title: String,
    val type: ScheduleType,
    val items: List<ScheduledTxWithAccount>,
    val total: Double,
    val isSchedule: Boolean
)

private data class PlanCategory(
    val key: String,
    val category: Category?,
    val lines: List<PlanLine>,
    /** Income minus expenses; transfers only count when the group has nothing else. */
    val total: Double,
    val totalType: ScheduleType
)

private fun groupByCategory(items: List<ScheduledTxWithAccount>): List<PlanCategory> =
    items.groupBy { it.category?.id }.map { (id, group) ->
        val lines = group.groupBy { it.tx.scheduleId }.map { (scheduleId, occurrences) ->
            val first = occurrences.first()
            PlanLine(
                key = scheduleId,
                title = first.tx.title,
                type = first.tx.type,
                items = occurrences,
                total = occurrences.sumOf { it.tx.amount },
                isSchedule = first.isScheduledOccurrence
            )
        }.sortedWith(compareBy<PlanLine> { it.type.ordinal }.thenBy { it.title.lowercase() })
        val income = group.filter { it.tx.type == ScheduleType.INCOME }.sumOf { it.tx.amount }
        val expense = group.filter { it.tx.type == ScheduleType.EXPENSE }.sumOf { it.tx.amount }
        val onlyTransfers = group.all { it.tx.type == ScheduleType.TRANSFER }
        val net = income - expense
        PlanCategory(
            key = id ?: "uncategorized",
            category = group.first().category,
            lines = lines,
            total = if (onlyTransfers) group.sumOf { it.tx.amount } else kotlin.math.abs(net),
            totalType = when {
                onlyTransfers -> ScheduleType.TRANSFER
                net >= 0 -> ScheduleType.INCOME
                else -> ScheduleType.EXPENSE
            }
        )
    }.sortedWith(compareBy<PlanCategory> { it.category == null }.thenBy { it.category?.name?.lowercase() })

/**
 * Plan: everything about expected money. Overview shows a week or month (actionable by day,
 * or broken down by category); Schedules, Categories and Accounts manage what feeds it.
 */
@Composable
fun PlanView(
    request: PlanRequest?,
    onRequestConsumed: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onOpenSchedule: (id: String?, type: ScheduleType?) -> Unit,
    onOpenCategory: (String?) -> Unit,
    onOpenAccount: (String?) -> Unit
) {
    var sectionName by rememberSaveable { mutableStateOf(PlanSection.OVERVIEW.name) }
    var periodName by rememberSaveable { mutableStateOf(PlanPeriod.WEEK.name) }
    LaunchedEffect(request) {
        if (request != null) {
            sectionName = request.section.name
            request.period?.let { periodName = it.name }
            onRequestConsumed()
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
            period = PlanPeriod.valueOf(periodName),
            onPeriodChange = { periodName = it.name },
            onOpenTransaction = onOpenTransaction,
            onOpenSchedule = { onOpenSchedule(it, null) },
            headerBottom = tabs
        )
        PlanSection.SCHEDULES -> SchedulesView(onOpenSchedule = onOpenSchedule, title = "Plan", headerBottom = tabs)
        PlanSection.CATEGORIES -> CategoriesView(onOpenCategory = onOpenCategory, title = "Plan", headerBottom = tabs)
        PlanSection.ACCOUNTS -> AccountsView(onOpenAccount = onOpenAccount, title = "Plan", headerBottom = tabs)
    }
}

@Composable
private fun PlanOverview(
    period: PlanPeriod,
    onPeriodChange: (PlanPeriod) -> Unit,
    onOpenTransaction: (String) -> Unit,
    onOpenSchedule: (String) -> Unit,
    headerBottom: @Composable () -> Unit
) {
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val postingRepo = koinInject<PostingRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val snackbar = LocalSnackbarController.current
    val actions = remember(postingRepo, scheduleRepo, snackbar) { OccurrenceActions(postingRepo, scheduleRepo, snackbar) }
    val scope = rememberCoroutineScope()

    val weekStart = DisplayPreferences.weekStart
    val todayDate = remember { today() }
    var groupingName by rememberSaveable { mutableStateOf(PlanGrouping.BY_DAY.name) }
    val grouping = PlanGrouping.valueOf(groupingName)
    var anchorEpochDay by rememberSaveable { mutableLongStateOf(todayDate.toEpochDays()) }
    val anchor = LocalDate.fromEpochDays(anchorEpochDay)

    val range = remember(anchor, weekStart, period) {
        when (period) {
            PlanPeriod.WEEK -> windowUseCase.computeWindow(anchor, weekStart)
            PlanPeriod.MONTH -> anchor.startOfMonth().let { it to it.plus(1, DateTimeUnit.MONTH) }
        }
    }
    val currentRange = remember(todayDate, weekStart, period) {
        when (period) {
            PlanPeriod.WEEK -> windowUseCase.computeWindow(todayDate, weekStart)
            PlanPeriod.MONTH -> todayDate.startOfMonth().let { it to it.plus(1, DateTimeUnit.MONTH) }
        }
    }
    val items by remember(range) { windowUseCase.watchWindow(range.first, range.second) }
        .collectAsState(initial = emptyList())
    val categories = remember(items) { groupByCategory(items) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var showPicker by remember { mutableStateOf(false) }
    var amountTarget by remember { mutableStateOf<ScheduledTxWithAccount?>(null) }

    val toRecord = items.count { !it.posted }
    val subtitle = when {
        items.isEmpty() -> null
        toRecord == 0 -> "Everything recorded"
        else -> "$toRecord to record"
    }
    val step: (Int) -> Unit = { direction ->
        anchorEpochDay = when (period) {
            PlanPeriod.WEEK -> anchor.plus(7 * direction, DateTimeUnit.DAY)
            PlanPeriod.MONTH -> anchor.startOfMonth().plus(direction, DateTimeUnit.MONTH)
        }.toEpochDays()
    }

    ScreenScaffold(title = "Plan", subtitle = subtitle, maxContentWidth = 840.dp, headerBottom = headerBottom) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PlanPeriod.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = period == option,
                                onClick = { onPeriodChange(option) },
                                shape = SegmentedButtonDefaults.itemShape(index, PlanPeriod.entries.size)
                            ) { Text(option.label) }
                        }
                    }
                    PeriodNavigator(
                        label = when (period) {
                            PlanPeriod.WEEK -> formatDateRange(range.first, range.second.minus(1, DateTimeUnit.DAY))
                            PlanPeriod.MONTH -> formatMonthYear(range.first)
                        },
                        previousLabel = "Previous ${period.label.lowercase()}",
                        nextLabel = "Next ${period.label.lowercase()}",
                        onPrevious = { step(-1) },
                        onNext = { step(1) },
                        onPick = { showPicker = true },
                        resetLabel = if (range != currentRange) "This ${period.label.lowercase()}" else null,
                        onReset = { anchorEpochDay = todayDate.toEpochDays() }
                    )
                    PlanSummaryCard(items)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PlanGrouping.entries.forEach { option ->
                            FilterChip(
                                selected = grouping == option,
                                onClick = { groupingName = option.name },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "Nothing scheduled this ${period.label.lowercase()}",
                        message = "Bills, paychecks and transfers from your schedules appear here.",
                        modifier = Modifier.padding(top = Spacing.xl)
                    )
                }
            } else if (grouping == PlanGrouping.BY_DAY) {
                items.groupBy { it.tx.date }.entries.sortedBy { it.key }.forEach { (date, dayItems) ->
                    item(key = "header-$date") { ListSectionHeader(formatDayHeader(date, todayDate)) }
                    items(dayItems, key = { "${it.tx.scheduleId}-${it.tx.date}-${it.postingId}" }) { item ->
                        OccurrenceRow(
                            item = item,
                            onMarkPosted = { scope.launch { actions.markPosted(item) } },
                            onUnmark = { scope.launch { actions.unmarkPosted(item) } },
                            onSkip = { scope.launch { actions.skip(item) } },
                            onChangeAmount = { amountTarget = item },
                            onOpenTransaction = { item.postingId?.let(onOpenTransaction) }
                        )
                    }
                }
            } else {
                categories.forEach { group ->
                    val isOpen = group.key in expanded
                    item(key = "cat-${group.key}") {
                        CategoryHeaderRow(
                            group = group,
                            expanded = isOpen,
                            onToggle = { expanded = if (isOpen) expanded - group.key else expanded + group.key }
                        )
                    }
                    if (isOpen) {
                        items(group.lines, key = { "line-${group.key}-${it.key}" }) { line ->
                            PlanLineRow(
                                line = line,
                                onClick = {
                                    if (line.isSchedule) onOpenSchedule(line.key)
                                    else line.items.first().postingId?.let(onOpenTransaction)
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
            initial = range.first,
            confirmLabel = "Go to ${period.label.lowercase()}",
            onConfirm = {
                anchorEpochDay = it.toEpochDays()
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    amountTarget?.let { target ->
        ChangeAmountDialog(
            item = target,
            onConfirm = { amount ->
                amountTarget = null
                scope.launch { actions.changeAmount(target, amount) }
            },
            onDismiss = { amountTarget = null }
        )
    }
}

@Composable
private fun PlanSummaryCard(items: List<ScheduledTxWithAccount>) {
    val income = items.filter { it.tx.type == ScheduleType.INCOME }.sumOf { it.tx.amount }
    val expenses = items.filter { it.tx.type == ScheduleType.EXPENSE }.sumOf { it.tx.amount }
    val net = income - expenses
    ShillingCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryFigure("Income", formatCurrency(income), MaterialTheme.colorScheme.tertiary)
            SummaryFigure("Expenses", formatCurrency(expenses), MaterialTheme.colorScheme.onSurface)
            SummaryFigure(
                if (net >= 0) "Surplus" else "Deficit",
                formatCurrency(kotlin.math.abs(net)),
                netColor(net),
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
private fun CategoryHeaderRow(group: PlanCategory, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.md, horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ColorDot(colorFromHex(group.category?.color))
        Column(modifier = Modifier.weight(1f)) {
            Text(group.category?.name ?: "Uncategorized", style = MaterialTheme.typography.titleSmall)
            Text(
                "${group.lines.size} ${if (group.lines.size == 1) "item" else "items"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AmountText(group.totalType, group.total)
        Icon(
            if (expanded) MaterialIcons.Filled.Expand_less else MaterialIcons.Filled.Expand_more,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlanLineRow(line: PlanLine, onClick: () -> Unit) {
    val count = line.items.size
    val sameAmount = line.items.map { it.tx.amount }.distinct().size == 1
    val supporting = buildList {
        add(
            when {
                count == 1 -> formatDate(line.items.first().tx.date)
                sameAmount -> "$count × ${formatCurrency(line.items.first().tx.amount)}"
                else -> "$count times"
            }
        )
        line.items.first().account?.name?.let { add(it) }
        val recorded = line.items.count { it.posted }
        if (recorded > 0) add(if (recorded == count) line.type.postedLabel else "$recorded of $count ${line.type.postedLabel.lowercase()}")
    }.joinToString(" · ")
    EntityListItem(
        title = line.title,
        supporting = supporting,
        trailing = { AmountText(line.type, line.total) },
        onClick = onClick,
        modifier = Modifier.padding(start = Spacing.lg)
    )
}

@Composable
private fun OccurrenceRow(
    item: ScheduledTxWithAccount,
    onMarkPosted: () -> Unit,
    onUnmark: () -> Unit,
    onSkip: () -> Unit,
    onChangeAmount: () -> Unit,
    onOpenTransaction: () -> Unit
) {
    val tx = item.tx
    var menuOpen by remember { mutableStateOf(false) }
    val accountLabel = item.account?.name ?: "No account"
    val supporting = buildList {
        add(
            if (tx.type == ScheduleType.TRANSFER) "$accountLabel → ${item.counterAccount?.name ?: "No account"}"
            else accountLabel
        )
        item.category?.let { add(it.name) }
        if (item.posted) add(tx.type.postedLabel) else if (tx.autoPay) add("Auto-pay")
    }.joinToString(" · ")

    EntityListItem(
        title = tx.title,
        supporting = supporting,
        leading = { ColorDot(colorFromHex(item.category?.color)) },
        onClick = { if (item.posted) onOpenTransaction() else menuOpen = true },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AmountText(tx.type, tx.amount)
                if (item.posted) {
                    Icon(
                        MaterialIcons.Filled.Check_circle,
                        contentDescription = tx.type.postedLabel,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    FilledTonalIconButton(onClick = onMarkPosted) {
                        Icon(MaterialIcons.Filled.Check, contentDescription = tx.type.markActionLabel)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(MaterialIcons.Filled.More_vert, contentDescription = "More actions for ${tx.title}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (item.posted) {
                            DropdownMenuItem(
                                text = { Text("View transaction") },
                                onClick = { menuOpen = false; onOpenTransaction() }
                            )
                            if (item.isScheduledOccurrence) {
                                DropdownMenuItem(
                                    text = { Text("Mark not ${tx.type.postedLabel.lowercase()}") },
                                    onClick = { menuOpen = false; onUnmark() }
                                )
                            }
                        } else {
                            DropdownMenuItem(
                                text = { Text(tx.type.markActionLabel) },
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
    item: ScheduledTxWithAccount,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(formatAmountInput(item.tx.amount)) }
    val amount = parseAmountInput(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change amount") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Applies only to ${item.tx.title} on ${formatDate(item.tx.date)}. The schedule is unchanged.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AmountField(value = text, onValueChange = { text = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { amount?.let(onConfirm) }, enabled = amount != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
