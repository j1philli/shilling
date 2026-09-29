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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.PostingWithDetails
import org.koin.compose.viewmodel.koinViewModel
import finance.shilling.shared.presentation.HomeDestination
import finance.shilling.shared.presentation.HomeUiState
import finance.shilling.shared.presentation.HomeViewModel
import finance.shilling.shared.presentation.formatSigned
import finance.shilling.shared.presentation.formatCurrency

@Composable
fun HomeView(
    onDestination: (HomeDestination) -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val go = onDestination

    ScreenScaffold(title = state.greeting, subtitle = state.dateLabel) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWide = maxWidth >= 720.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                if (isWide) HomeBentoWideLayout(state, go) else HomeBentoCompactLayout(state, go)
            }
        }
    }
}

@Composable
private fun HomeBentoCompactLayout(t: HomeUiState, go: (HomeDestination) -> Unit) {
    HomeHeroTile(
        value = t.balanceValue,
        caption = t.balanceCaption,
        onClick = { go(HomeDestination.ACCOUNTS) }
    )

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ThisWeekTile(t, go, Modifier.weight(1f).fillMaxHeight(), previewCount = 2)
        ThisMonthTile(t, go, Modifier.weight(1f).fillMaxHeight())
    }

    ActivityTile(t, go, Modifier.fillMaxWidth(), wide = false)

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SchedulesTile(t, go, Modifier.weight(1f).fillMaxHeight())
        ReceiptsTile(t, go, Modifier.weight(1f).fillMaxHeight())
    }
    CategoriesTile(t, go, Modifier.fillMaxWidth(), previewCount = 3)
}

@Composable
private fun HomeBentoWideLayout(t: HomeUiState, go: (HomeDestination) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        HomeHeroTile(
            modifier = Modifier.weight(1.1f).fillMaxHeight(),
            value = t.balanceValue,
            caption = t.balanceCaption,
            onClick = { go(HomeDestination.ACCOUNTS) }
        )
        Column(
            modifier = Modifier.weight(0.9f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            ThisWeekTile(t, go, Modifier.weight(1f).fillMaxWidth(), previewCount = 0)
            ThisMonthTile(t, go, Modifier.weight(1f).fillMaxWidth())
        }
    }

    ActivityTile(t, go, Modifier.fillMaxWidth(), wide = true)

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SchedulesTile(t, go, Modifier.weight(1f).fillMaxHeight())
        ReceiptsTile(t, go, Modifier.weight(1f).fillMaxHeight())
        CategoriesTile(t, go, Modifier.weight(1f).fillMaxHeight(), previewCount = 3)
    }
}

@Composable
private fun ThisWeekTile(t: HomeUiState, go: (HomeDestination) -> Unit, modifier: Modifier, previewCount: Int) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Calendar_month,
        label = "This week",
        value = t.weeklyValue,
        caption = t.weeklyCaption,
        accent = MaterialTheme.colorScheme.primary,
        onClick = { go(HomeDestination.WEEK) }
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
private fun ThisMonthTile(t: HomeUiState, go: (HomeDestination) -> Unit, modifier: Modifier) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Bar_chart,
        label = "This month",
        value = t.monthValue,
        caption = t.monthCaption,
        accent = netColor(t.monthNet),
        onClick = { go(HomeDestination.MONTH) }
    )
}

@Composable
private fun ActivityTile(t: HomeUiState, go: (HomeDestination) -> Unit, modifier: Modifier, wide: Boolean) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.History,
        label = "Activity",
        value = t.activityValue,
        caption = t.activityCaption,
        accent = MaterialTheme.colorScheme.secondary,
        onClick = { go(HomeDestination.ACTIVITY) }
    ) {
        if (t.recent.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            if (wide) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    t.recent.forEach { item ->
                        Column(modifier = Modifier.weight(1f)) { ActivityMiniRow(item) }
                    }
                }
            } else {
                t.recent.forEach { ActivityMiniRow(it) }
            }
        }
    }
}

@Composable
private fun SchedulesTile(t: HomeUiState, go: (HomeDestination) -> Unit, modifier: Modifier) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Swap_horiz,
        label = "Schedules",
        value = t.scheduleValue,
        caption = t.scheduleCaption,
        accent = MaterialTheme.colorScheme.primary,
        onClick = { go(HomeDestination.SCHEDULES) }
    )
}

@Composable
private fun ReceiptsTile(t: HomeUiState, go: (HomeDestination) -> Unit, modifier: Modifier) {
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
        onClick = { go(HomeDestination.RECEIPTS) }
    )
}

@Composable
private fun CategoriesTile(t: HomeUiState, go: (HomeDestination) -> Unit, modifier: Modifier, previewCount: Int) {
    BentoTile(
        modifier = modifier,
        icon = MaterialIcons.Filled.Label,
        label = "Categories",
        value = t.categoriesValue,
        caption = t.categoriesCaption,
        accent = MaterialTheme.colorScheme.secondary,
        onClick = { go(HomeDestination.CATEGORIES) }
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
    value: String,
    caption: String,
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
                value,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                caption,
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
