package finance.shilling.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Account_balance
import com.composables.icons.materialicons.filled.Bar_chart
import com.composables.icons.materialicons.filled.Calendar_month
import com.composables.icons.materialicons.filled.History
import com.composables.icons.materialicons.filled.Label
import com.composables.icons.materialicons.filled.Receipt
import com.composables.icons.materialicons.filled.Swap_horiz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.BudgetSummary
import finance.shilling.shared.data.CategoryTotal
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTxWithAccount
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun HomeView(
    onNavigate: (ShelfDestination) -> Unit,
    onOpenPlan: (PlanRequest) -> Unit
) {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val receiptRepo = koinInject<ReceiptRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val budgetUseCase = koinInject<ComputeBudgetUseCase>()

    val today = remember { finance.shilling.shared.ui.today() }
    val weekStart = DisplayPreferences.weekStart
    val monthStart = remember { today.startOfMonth() }
    val monthEnd = remember(monthStart) { monthStart.plus(1, DateTimeUnit.MONTH) }
    val historyStart = remember(today) { today.minus(1, DateTimeUnit.YEAR) }
    val historyEnd = remember(today) { today.plus(1, DateTimeUnit.DAY) }

    val accounts by remember { accountRepo.watchAll() }.collectAsState(initial = emptyList())
    val categories by remember { categoryRepo.watchAll() }.collectAsState(initial = emptyList())
    val schedules by remember { scheduleRepo.watchAll() }.collectAsState(initial = emptyList())
    val receipts by remember { receiptRepo.watchAll() }.collectAsState(initial = emptyList())
    val upcoming by remember(weekStart) { windowUseCase.watchUpcomingWindow(weekStart) }
        .collectAsState(initial = emptyList())
    val recentPostings by remember(historyStart, historyEnd) { postingRepo.watchBetween(historyStart, historyEnd) }
        .collectAsState(initial = emptyList())
    val summary by produceState(
        initialValue = BudgetSummary.empty(monthStart, monthEnd),
        monthStart
    ) {
        budgetUseCase.watchBudget(monthStart).collect { value = it }
    }

    val totalBalance = remember(accounts) { accounts.sumOf { it.balance } }
    val unpostedCount = remember(upcoming) { upcoming.count { !it.posted } }
    val unattachedReceipts = remember(receipts) { receipts.count { it.receipt.postingId == null } }
    val scheduleBreakdown = remember(schedules) {
        schedules.groupingBy { it.type }.eachCount()
    }
    val topCategories = remember(summary) {
        summary.categoryTotals
            .filter { it.total != 0.0 }
            .sortedByDescending { kotlin.math.abs(it.total) }
            .take(3)
    }
    val recentThree = remember(recentPostings) { recentPostings.take(3) }

    val netLabel = if (summary.netChange >= 0) "Surplus" else "Deficit"
    val budgetAccent = netColor(summary.netChange)
    val budgetCaption = if (summary.lines.isEmpty()) {
        "Nothing scheduled this month"
    } else {
        "$netLabel · ${summary.lines.size} scheduled ${if (summary.lines.size == 1) "item" else "items"}"
    }

    ScreenScaffold(title = greetingFor(), subtitle = formatDateLong(today)) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWide = maxWidth >= 720.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                val tiles = HomeTiles(
                    totalBalance = totalBalance,
                    accountCount = accounts.size,
                    upcoming = upcoming,
                    unpostedCount = unpostedCount,
                    netChange = summary.netChange,
                    budgetAccent = budgetAccent,
                    budgetCaption = budgetCaption,
                    recentThree = recentThree,
                    scheduleCount = schedules.size,
                    scheduleBreakdown = scheduleBreakdown,
                    unattachedReceipts = unattachedReceipts,
                    receiptCount = receipts.size,
                    categoryCount = categories.size,
                    topCategories = topCategories
                )
                if (isWide) {
                    HomeBentoWideLayout(tiles, onNavigate, onOpenPlan)
                } else {
                    HomeBentoCompactLayout(tiles, onNavigate, onOpenPlan)
                }
            }
        }
    }
}

private data class HomeTiles(
    val totalBalance: Double,
    val accountCount: Int,
    val upcoming: List<ScheduledTxWithAccount>,
    val unpostedCount: Int,
    val netChange: Double,
    val budgetAccent: Color,
    val budgetCaption: String,
    val recentThree: List<PostingWithDetails>,
    val scheduleCount: Int,
    val scheduleBreakdown: Map<ScheduleType, Int>,
    val unattachedReceipts: Int,
    val receiptCount: Int,
    val categoryCount: Int,
    val topCategories: List<CategoryTotal>
) {
    // Shared copy so the compact and wide layouts always say the same thing.
    val weeklyValue get() = if (upcoming.isEmpty()) "Clear" else "${upcoming.size} due"
    val weeklyCaption get() = weeklyCaption(upcoming.size, unpostedCount)
    val activityValue get() = if (recentThree.isEmpty()) "No activity yet" else "Recent activity"
    val activityCaption get() = if (recentThree.isEmpty()) "Transactions appear here once recorded" else null
    val scheduleValue get() = if (scheduleCount == 0) "None yet" else "$scheduleCount active"
    val scheduleCaption get() = scheduleCaption(scheduleBreakdown)
    val receiptsValue get() = when {
        receiptCount == 0 -> "None yet"
        unattachedReceipts == 0 -> "All attached"
        else -> "$unattachedReceipts to attach"
    }
    val receiptsCaption get() = when {
        receiptCount == 0 -> "Keep receipts with your transactions"
        unattachedReceipts == 0 -> "Nothing waiting"
        else -> "Link them to transactions"
    }
    val categoriesValue get() = if (categoryCount == 0) "None yet" else "$categoryCount total"
    val categoriesCaption get() = if (topCategories.isEmpty()) "Label schedules and transactions" else "Largest this month"
}

@Composable
private fun greetingFor(): String {
    val hour = remember { kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour }
    return when (hour) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
}

private fun formatDateLong(date: LocalDate): String =
    "${date.dayOfWeek.fullLabel}, ${date.month.fullLabel} ${date.day}, ${date.year}"

@Composable
private fun HomeBentoCompactLayout(t: HomeTiles, onNavigate: (ShelfDestination) -> Unit, onOpenPlan: (PlanRequest) -> Unit) {
    HomeHeroTile(
        balance = t.totalBalance,
        accountCount = t.accountCount,
        onClick = { onOpenPlan(PlanRequest(PlanSection.ACCOUNTS)) }
    )

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ThisWeekTile(t, onOpenPlan, Modifier.weight(1f).fillMaxHeight(), previewCount = 2)
        ThisMonthTile(t, onOpenPlan, Modifier.weight(1f).fillMaxHeight())
    }

    ActivityTile(t, onNavigate, Modifier.fillMaxWidth(), wide = false)

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SchedulesTile(t, onOpenPlan, Modifier.weight(1f).fillMaxHeight())
        ReceiptsTile(t, onNavigate, Modifier.weight(1f).fillMaxHeight())
    }
    CategoriesTile(t, onOpenPlan, Modifier.fillMaxWidth(), previewCount = 3)
}

@Composable
private fun HomeBentoWideLayout(t: HomeTiles, onNavigate: (ShelfDestination) -> Unit, onOpenPlan: (PlanRequest) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        HomeHeroTile(
            modifier = Modifier.weight(1.1f).fillMaxHeight(),
            balance = t.totalBalance,
            accountCount = t.accountCount,
            onClick = { onOpenPlan(PlanRequest(PlanSection.ACCOUNTS)) }
        )
        Column(
            modifier = Modifier.weight(0.9f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            ThisWeekTile(t, onOpenPlan, Modifier.weight(1f).fillMaxWidth(), previewCount = 0)
            ThisMonthTile(t, onOpenPlan, Modifier.weight(1f).fillMaxWidth())
        }
    }

    ActivityTile(t, onNavigate, Modifier.fillMaxWidth(), wide = true)

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SchedulesTile(t, onOpenPlan, Modifier.weight(1f).fillMaxHeight())
        ReceiptsTile(t, onNavigate, Modifier.weight(1f).fillMaxHeight())
        CategoriesTile(t, onOpenPlan, Modifier.weight(1f).fillMaxHeight(), previewCount = 3)
    }
}

@Composable
private fun ThisWeekTile(t: HomeTiles, onOpenPlan: (PlanRequest) -> Unit, modifier: Modifier, previewCount: Int) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Calendar_month,
        label = "This week",
        value = t.weeklyValue,
        caption = t.weeklyCaption,
        accent = MaterialTheme.colorScheme.primary,
        onClick = { onOpenPlan(PlanRequest(PlanSection.OVERVIEW, PlanPeriod.WEEK)) }
    ) {
        if (previewCount > 0 && t.upcoming.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            t.upcoming.take(previewCount).forEach { item ->
                BentoMiniRow(
                    title = item.tx.title,
                    trailing = formatSigned(item.tx.type, item.tx.amount),
                    trailingColor = amountColor(item.tx.type)
                )
            }
        }
    }
}

@Composable
private fun ThisMonthTile(t: HomeTiles, onOpenPlan: (PlanRequest) -> Unit, modifier: Modifier) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Bar_chart,
        label = "This month",
        value = formatCurrency(t.netChange),
        caption = t.budgetCaption,
        accent = t.budgetAccent,
        onClick = { onOpenPlan(PlanRequest(PlanSection.OVERVIEW, PlanPeriod.MONTH)) }
    )
}

@Composable
private fun ActivityTile(t: HomeTiles, onNavigate: (ShelfDestination) -> Unit, modifier: Modifier, wide: Boolean) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.History,
        label = "Activity",
        value = t.activityValue,
        caption = t.activityCaption,
        accent = MaterialTheme.colorScheme.secondary,
        onClick = { onNavigate(ShelfDestination.ACTIVITY) }
    ) {
        if (t.recentThree.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            if (wide) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    t.recentThree.forEach { item ->
                        Column(modifier = Modifier.weight(1f)) { ActivityMiniRow(item) }
                    }
                }
            } else {
                t.recentThree.forEach { ActivityMiniRow(it) }
            }
        }
    }
}

@Composable
private fun SchedulesTile(t: HomeTiles, onOpenPlan: (PlanRequest) -> Unit, modifier: Modifier) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Swap_horiz,
        label = "Schedules",
        value = t.scheduleValue,
        caption = t.scheduleCaption,
        accent = MaterialTheme.colorScheme.primary,
        onClick = { onOpenPlan(PlanRequest(PlanSection.SCHEDULES)) }
    )
}

@Composable
private fun ReceiptsTile(t: HomeTiles, onNavigate: (ShelfDestination) -> Unit, modifier: Modifier) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Receipt,
        label = "Receipts",
        value = t.receiptsValue,
        caption = t.receiptsCaption,
        accent = when {
            t.receiptCount == 0 -> MaterialTheme.colorScheme.secondary
            t.unattachedReceipts == 0 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        onClick = { onNavigate(ShelfDestination.RECEIPTS) }
    )
}

@Composable
private fun CategoriesTile(t: HomeTiles, onOpenPlan: (PlanRequest) -> Unit, modifier: Modifier, previewCount: Int) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Label,
        label = "Categories",
        value = t.categoriesValue,
        caption = t.categoriesCaption,
        accent = MaterialTheme.colorScheme.secondary,
        onClick = { onOpenPlan(PlanRequest(PlanSection.CATEGORIES)) }
    ) {
        if (t.topCategories.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            t.topCategories.take(previewCount).forEach { total ->
                BentoMiniRow(
                    title = total.category?.name ?: "Uncategorized",
                    leadingDot = colorFromHex(total.category?.color) ?: MaterialTheme.colorScheme.outlineVariant,
                    trailing = formatCurrency(total.total),
                    trailingColor = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun HomeHeroTile(
    balance: Double,
    accountCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.surfaceContainerLow
        )
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(gradient)
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Total balance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                BentoIconBadge(
                    icon = MaterialIcons.Filled.Account_balance,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    background = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                )
            }
            Text(
                formatCurrency(balance),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                if (accountCount == 0) "Add an account to get started" else "$accountCount account${if (accountCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BentoTile(
    label: String,
    value: String,
    caption: String?,
    accent: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (() -> Unit)? = null
) {
    val tileBackground = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(tileBackground)
            .clickable(onClick = onClick)
            .padding(Spacing.lg)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.titleLarge,
                        color = accent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    caption?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                BentoIconBadge(
                    icon = icon,
                    tint = accent,
                    background = accent.copy(alpha = 0.14f)
                )
            }
            content?.invoke()
        }
    }
}

@Composable
private fun BentoIconBadge(
    icon: ImageVector,
    tint: Color,
    background: Color
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun BentoMiniRow(
    title: String,
    trailing: String,
    trailingColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingDot: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leadingDot?.let { color ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            trailing,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = trailingColor
        )
    }
}

@Composable
private fun ActivityMiniRow(item: PostingWithDetails) {
    val dotColor = colorFromHex(item.categoryColor)
    BentoMiniRow(
        title = item.title,
        trailing = formatSigned(item.posting.type, item.posting.amount),
        trailingColor = amountColor(item.posting.type),
        leadingDot = dotColor
    )
}

private fun weeklyCaption(total: Int, unposted: Int): String = when {
    total == 0 -> "Nothing due this week"
    unposted == 0 -> "All recorded"
    unposted == 1 -> "1 to record"
    else -> "$unposted to record"
}

private fun scheduleCaption(breakdown: Map<ScheduleType, Int>): String {
    if (breakdown.isEmpty()) return "Set up bills, paychecks and transfers"
    return ScheduleType.entries.mapNotNull { type ->
        breakdown[type]?.let { count -> "$count ${type.pluralLabel.lowercase()}" }
    }.joinToString(" · ")
}
