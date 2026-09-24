package finance.shilling.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
fun HomeView(onNavigate: (ShelfDestination) -> Unit) {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val receiptRepo = koinInject<ReceiptRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val budgetUseCase = koinInject<ComputeBudgetUseCase>()

    val systemZone = remember { TimeZone.currentSystemDefault() }
    val today = remember { Clock.System.now().toLocalDateTime(systemZone).date }
    val monthStart = remember { today.startOfMonth() }
    val monthEnd = remember(monthStart) { monthStart.plus(1, DateTimeUnit.MONTH) }
    val historyStart = remember(today) { today.minus(1, DateTimeUnit.YEAR) }
    val historyEnd = remember(today) { today.plus(1, DateTimeUnit.DAY) }

    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())
    val schedules by scheduleRepo.watchAll().collectAsState(initial = emptyList())
    val receipts by receiptRepo.watchAll().collectAsState(initial = emptyList())
    val upcoming by windowUseCase.watchWindowForComingFriday().collectAsState(initial = emptyList())
    val recentPostings by postingRepo.watchBetween(historyStart, historyEnd).collectAsState(initial = emptyList())
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
    val budgetAccent = if (summary.netChange >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    val budgetCaption = if (summary.lines.isEmpty()) {
        "No scheduled items"
    } else {
        "${summary.lines.size} items · ${summary.categoryTotals.size} categories"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 720.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeGreeting(today)

            if (isWide) {
                HomeBentoWideLayout(
                    totalBalance = totalBalance,
                    accountCount = accounts.size,
                    upcoming = upcoming,
                    unpostedCount = unpostedCount,
                    netLabel = netLabel,
                    netChange = summary.netChange,
                    budgetAccent = budgetAccent,
                    budgetCaption = budgetCaption,
                    recentThree = recentThree,
                    scheduleCount = schedules.size,
                    scheduleBreakdown = scheduleBreakdown,
                    unattachedReceipts = unattachedReceipts,
                    categoryCount = categories.size,
                    topCategories = topCategories,
                    onNavigate = onNavigate
                )
            } else {
                HomeBentoCompactLayout(
                    totalBalance = totalBalance,
                    accountCount = accounts.size,
                    upcoming = upcoming,
                    unpostedCount = unpostedCount,
                    netLabel = netLabel,
                    netChange = summary.netChange,
                    budgetAccent = budgetAccent,
                    budgetCaption = budgetCaption,
                    recentThree = recentThree,
                    scheduleCount = schedules.size,
                    scheduleBreakdown = scheduleBreakdown,
                    unattachedReceipts = unattachedReceipts,
                    categoryCount = categories.size,
                    topCategories = topCategories,
                    onNavigate = onNavigate
                )
            }
        }
    }
}

@Composable
private fun HomeBentoCompactLayout(
    totalBalance: Double,
    accountCount: Int,
    upcoming: List<ScheduledTxWithAccount>,
    unpostedCount: Int,
    netLabel: String,
    netChange: Double,
    budgetAccent: Color,
    budgetCaption: String,
    recentThree: List<PostingWithDetails>,
    scheduleCount: Int,
    scheduleBreakdown: Map<ScheduleType, Int>,
    unattachedReceipts: Int,
    categoryCount: Int,
    topCategories: List<CategoryTotal>,
    onNavigate: (ShelfDestination) -> Unit
) {
    HomeHeroTile(
        balance = totalBalance,
        accountCount = accountCount,
        onClick = { onNavigate(ShelfDestination.ACCOUNTS) }
    )

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BentoTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = MaterialIcons.Filled.Calendar_month,
            label = "Weekly",
            value = if (upcoming.isEmpty()) "Clear" else "${upcoming.size}",
            caption = weeklyCaption(upcoming.size, unpostedCount),
            accent = MaterialTheme.colorScheme.primary,
            onClick = { onNavigate(ShelfDestination.WEEKLY) }
        ) {
            if (upcoming.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                upcoming.take(2).forEach { item ->
                    BentoMiniRow(
                        title = item.tx.title,
                        trailing = formatSigned(item.tx.type, item.tx.amount),
                        trailingColor = amountColor(item.tx.type)
                    )
                }
            }
        }
        BentoTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = MaterialIcons.Filled.Bar_chart,
            label = "Budget",
            value = formatCurrency(netChange),
            caption = "$netLabel · $budgetCaption",
            accent = budgetAccent,
            onClick = { onNavigate(ShelfDestination.BUDGET) }
        )
    }

    BentoTile(
        modifier = Modifier.fillMaxWidth(),
        icon = MaterialIcons.Filled.History,
        label = "History",
        value = if (recentThree.isEmpty()) "—" else "${recentThree.size} recent",
        caption = if (recentThree.isEmpty()) "Transactions appear once you post" else "Latest activity",
        accent = MaterialTheme.colorScheme.secondary,
        onClick = { onNavigate(ShelfDestination.HISTORY) }
    ) {
        if (recentThree.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            recentThree.forEach { item ->
                HistoryMiniRow(item)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BentoTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = MaterialIcons.Filled.Swap_horiz,
            label = "Schedules",
            value = if (scheduleCount == 0) "—" else "$scheduleCount",
            caption = scheduleCaption(scheduleBreakdown),
            accent = MaterialTheme.colorScheme.primary,
            onClick = { onNavigate(ShelfDestination.SCHEDULES) }
        )
        BentoTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = MaterialIcons.Filled.Receipt,
            label = "Receipts",
            value = if (unattachedReceipts == 0) "✓" else "$unattachedReceipts",
            caption = if (unattachedReceipts == 0) "All caught up" else "to attach",
            accent = if (unattachedReceipts == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            onClick = { onNavigate(ShelfDestination.RECEIPTS) }
        )
        BentoTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = MaterialIcons.Filled.Label,
            label = "Categories",
            value = if (categoryCount == 0) "—" else "$categoryCount",
            caption = if (topCategories.isEmpty()) "labels" else "top spenders",
            accent = MaterialTheme.colorScheme.secondary,
            onClick = { onNavigate(ShelfDestination.CATEGORIES) }
        ) {
            if (topCategories.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                topCategories.take(2).forEach { total ->
                    val dotColor = colorFromHex(total.category?.color) ?: MaterialTheme.colorScheme.onSurfaceVariant
                    BentoMiniRow(
                        title = total.category?.name ?: "Other",
                        leadingDot = dotColor,
                        trailing = formatCurrency(total.total),
                        trailingColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeBentoWideLayout(
    totalBalance: Double,
    accountCount: Int,
    upcoming: List<ScheduledTxWithAccount>,
    unpostedCount: Int,
    netLabel: String,
    netChange: Double,
    budgetAccent: Color,
    budgetCaption: String,
    recentThree: List<PostingWithDetails>,
    scheduleCount: Int,
    scheduleBreakdown: Map<ScheduleType, Int>,
    unattachedReceipts: Int,
    categoryCount: Int,
    topCategories: List<CategoryTotal>,
    onNavigate: (ShelfDestination) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HomeHeroTile(
            modifier = Modifier.weight(1.1f).fillMaxHeight(),
            balance = totalBalance,
            accountCount = accountCount,
            onClick = { onNavigate(ShelfDestination.ACCOUNTS) }
        )
        Column(
            modifier = Modifier.weight(0.9f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BentoTile(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                icon = MaterialIcons.Filled.Calendar_month,
                label = "Weekly",
                value = if (upcoming.isEmpty()) "Clear week" else "${upcoming.size} upcoming",
                caption = weeklyCaption(upcoming.size, unpostedCount),
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onNavigate(ShelfDestination.WEEKLY) }
            )
            BentoTile(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                icon = MaterialIcons.Filled.Bar_chart,
                label = "Budget · $netLabel",
                value = formatCurrency(netChange),
                caption = budgetCaption,
                accent = budgetAccent,
                onClick = { onNavigate(ShelfDestination.BUDGET) }
            )
        }
    }

    BentoTile(
        modifier = Modifier.fillMaxWidth(),
        icon = MaterialIcons.Filled.History,
        label = "History",
        value = if (recentThree.isEmpty()) "No transactions yet" else "Recent activity",
        caption = null,
        accent = MaterialTheme.colorScheme.secondary,
        onClick = { onNavigate(ShelfDestination.HISTORY) }
    ) {
        if (recentThree.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                recentThree.forEach { item ->
                    Column(modifier = Modifier.weight(1f)) {
                        HistoryMiniRow(item)
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BentoTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = MaterialIcons.Filled.Swap_horiz,
            label = "Schedules",
            value = if (scheduleCount == 0) "None yet" else "$scheduleCount active",
            caption = scheduleCaption(scheduleBreakdown),
            accent = MaterialTheme.colorScheme.primary,
            onClick = { onNavigate(ShelfDestination.SCHEDULES) }
        )
        BentoTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = MaterialIcons.Filled.Receipt,
            label = "Receipts",
            value = if (unattachedReceipts == 0) "All caught up" else "$unattachedReceipts waiting",
            caption = if (unattachedReceipts == 0) null else "Tap to attach",
            accent = if (unattachedReceipts == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            onClick = { onNavigate(ShelfDestination.RECEIPTS) }
        )
        BentoTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            icon = MaterialIcons.Filled.Label,
            label = "Categories",
            value = if (categoryCount == 0) "None yet" else "$categoryCount labels",
            caption = if (topCategories.isEmpty()) null else "This month",
            accent = MaterialTheme.colorScheme.secondary,
            onClick = { onNavigate(ShelfDestination.CATEGORIES) }
        ) {
            if (topCategories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                topCategories.forEach { total ->
                    val dotColor = colorFromHex(total.category?.color) ?: MaterialTheme.colorScheme.onSurfaceVariant
                    BentoMiniRow(
                        title = total.category?.name ?: "Other",
                        leadingDot = dotColor,
                        trailing = formatCurrency(total.total),
                        trailingColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeGreeting(today: LocalDate) {
    val hour = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour }
    val greeting = remember(hour) {
        when (hour) {
            in 0..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val monthLabel = remember(today) {
        today.month.name.lowercase().replaceFirstChar { it.titlecase() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            greeting,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "$monthLabel ${today.day}, ${today.year}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
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
                color = MaterialTheme.colorScheme.onSurface
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
    val tileBackground = accent.copy(alpha = 0.08f)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(tileBackground)
            .clickable(onClick = onClick)
            .padding(16.dp)
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
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
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
private fun HistoryMiniRow(item: PostingWithDetails) {
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
    unposted == 0 -> "All posted"
    unposted == 1 -> "1 to post"
    else -> "$unposted to post"
}

private fun scheduleCaption(breakdown: Map<ScheduleType, Int>): String {
    if (breakdown.isEmpty()) return "No schedules yet"
    return ScheduleType.entries.mapNotNull { type ->
        breakdown[type]?.let { count ->
            val short = when (type) {
                ScheduleType.EXPENSE -> "exp"
                ScheduleType.INCOME -> "inc"
                ScheduleType.TRANSFER -> "xfer"
            }
            "$count $short"
        }
    }.joinToString(" · ")
}

@Composable
private fun amountColor(type: ScheduleType): Color = when (type) {
    ScheduleType.EXPENSE -> MaterialTheme.colorScheme.error
    ScheduleType.INCOME -> MaterialTheme.colorScheme.tertiary
    ScheduleType.TRANSFER -> MaterialTheme.colorScheme.primary
}
