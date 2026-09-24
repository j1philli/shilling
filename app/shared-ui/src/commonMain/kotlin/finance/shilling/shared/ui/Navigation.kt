package finance.shilling.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Account_balance
import com.composables.icons.materialicons.filled.Bar_chart
import com.composables.icons.materialicons.filled.Calendar_month
import com.composables.icons.materialicons.filled.History
import com.composables.icons.materialicons.filled.Home
import com.composables.icons.materialicons.filled.Label
import com.composables.icons.materialicons.filled.Drag_indicator
import com.composables.icons.materialicons.filled.Edit
import com.composables.icons.materialicons.filled.Menu
import com.composables.icons.materialicons.filled.Receipt
import com.composables.icons.materialicons.filled.Settings
import com.composables.icons.materialicons.filled.Swap_horiz
import com.composables.icons.materialicons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.window.core.layout.WindowWidthSizeClass
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_TAB_ORDER
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.FeatureGate
import io.github.vinceglb.filekit.PlatformFile
import org.koin.compose.koinInject
import kotlin.math.roundToInt

enum class ShelfDestination(
    val title: String,
    val icon: ImageVector,
    val primaryOnCompact: Boolean = false
) {
    HOME("Home", MaterialIcons.Filled.Home, primaryOnCompact = true),
    WEEKLY("Weekly", MaterialIcons.Filled.Calendar_month, primaryOnCompact = true),
    HISTORY("History", MaterialIcons.Filled.History, primaryOnCompact = true),
    ACCOUNTS("Accounts", MaterialIcons.Filled.Account_balance),
    CATEGORIES("Categories", MaterialIcons.Filled.Label),
    BUDGET("Budget", MaterialIcons.Filled.Bar_chart, primaryOnCompact = true),
    SCHEDULES("Schedules", MaterialIcons.Filled.Swap_horiz, primaryOnCompact = true),
    IMPORT("Import", MaterialIcons.Filled.Upload),
    RECEIPTS("Receipts", MaterialIcons.Filled.Receipt),
}

private const val COMPACT_PRIMARY_COUNT = 4

private data class DragState(
    val draggedIndex: Int,
    val offsetY: Float
)

private fun defaultTabOrder(): List<ShelfDestination> {
    // Primary tabs first (for the compact bottom bar), then the rest
    val primary = ShelfDestination.entries.filter { it.primaryOnCompact }
    val overflow = ShelfDestination.entries.filter { !it.primaryOnCompact }
    return primary + overflow
}

private fun loadTabOrder(settings: Settings): List<ShelfDestination> {
    val saved = settings.getStringOrNull(SETTINGS_KEY_TAB_ORDER)
        ?: return defaultTabOrder()
    val savedNames = saved.split(",")
    val result = mutableListOf<ShelfDestination>()
    for (name in savedNames) {
        val dest = ShelfDestination.entries.find { it.name == name }
        if (dest != null) result.add(dest)
    }
    // Append any new destinations not in saved order
    for (dest in ShelfDestination.entries) {
        if (dest !in result) result.add(dest)
    }
    return result
}

private fun saveTabOrder(settings: Settings, order: List<ShelfDestination>) {
    settings.putString(SETTINGS_KEY_TAB_ORDER, order.joinToString(",") { it.name })
}

private fun getTargetIndex(draggedIndex: Int, offsetY: Float, itemHeight: Float, listSize: Int): Int {
    if (itemHeight <= 0f) return draggedIndex
    val indexOffset = (offsetY / itemHeight).roundToInt()
    return (draggedIndex + indexOffset).coerceIn(0, listSize - 1)
}

@Composable
fun ShillingScaffold(
    authService: AuthService,
    featureGate: FeatureGate,
    selfHosted: Boolean,
    onRetryHostedBootstrap: () -> Unit = {},
    navRailTopPadding: Dp = 0.dp,
    cameraButton: ReceiptPickerButton? = null,
    photoButton: ReceiptPickerButton? = null,
    externalNavRequest: StateFlow<ShelfDestination?> = MutableStateFlow(null),
    autoOpenCamera: Boolean = false,
    pendingReceiptFile: PlatformFile? = null,
    onPendingReceiptConsumed: () -> Unit = {}
) {
    val settings: Settings = koinInject()
    var currentDestination by remember { mutableStateOf(ShelfDestination.HOME) }
    var showSettings by remember { mutableStateOf(false) }
    var orderedDestinations by remember { mutableStateOf(loadTabOrder(settings)) }

    LaunchedEffect(Unit) {
        externalNavRequest.filterNotNull().collect { dest ->
            currentDestination = dest
            showSettings = false
        }
    }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    @Suppress("DEPRECATION")
    val widthClass = windowSizeClass.windowWidthSizeClass
    // Compact (phone / folded): bottom bar + More. Medium+ (tablet, unfolded, desktop): rail.
    val useCompactChrome = widthClass == WindowWidthSizeClass.COMPACT

    if (useCompactChrome) {
        CompactScaffold(
            currentDestination = currentDestination,
            showSettings = showSettings,
            authService = authService,
            featureGate = featureGate,
            selfHosted = selfHosted,
            orderedDestinations = orderedDestinations,
            cameraButton = cameraButton,
            photoButton = photoButton,
            autoOpenCamera = autoOpenCamera,
            pendingReceiptFile = pendingReceiptFile,
            onPendingReceiptConsumed = onPendingReceiptConsumed,
            onDestinationSelected = { currentDestination = it; showSettings = false },
            onSettingsClicked = { showSettings = true },
            onReorder = { newOrder ->
                orderedDestinations = newOrder
                saveTabOrder(settings, newOrder)
            }
        )
    } else {
        ExpandedScaffold(
            currentDestination = currentDestination,
            showSettings = showSettings,
            authService = authService,
            featureGate = featureGate,
            selfHosted = selfHosted,
            navRailTopPadding = navRailTopPadding,
            orderedDestinations = orderedDestinations,
            cameraButton = cameraButton,
            photoButton = photoButton,
            autoOpenCamera = autoOpenCamera,
            pendingReceiptFile = pendingReceiptFile,
            onPendingReceiptConsumed = onPendingReceiptConsumed,
            onDestinationSelected = { currentDestination = it; showSettings = false },
            onSettingsClicked = { showSettings = true },
            onReorder = { newOrder ->
                orderedDestinations = newOrder
                saveTabOrder(settings, newOrder)
            }
        )
    }
}

@Composable
private fun ExpandedScaffold(
    currentDestination: ShelfDestination,
    showSettings: Boolean,
    authService: AuthService,
    featureGate: FeatureGate,
    selfHosted: Boolean,
    navRailTopPadding: Dp,
    orderedDestinations: List<ShelfDestination>,
    cameraButton: ReceiptPickerButton?,
    photoButton: ReceiptPickerButton?,
    autoOpenCamera: Boolean = false,
    pendingReceiptFile: PlatformFile? = null,
    onPendingReceiptConsumed: () -> Unit = {},
    onDestinationSelected: (ShelfDestination) -> Unit,
    onSettingsClicked: () -> Unit,
    onReorder: (List<ShelfDestination>) -> Unit
) {
    var dragState by remember { mutableStateOf<DragState?>(null) }
    var itemHeight by remember { mutableFloatStateOf(0f) }

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier.fillMaxHeight().width(94.dp).padding(start = 2.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (navRailTopPadding > 0.dp) {
                Spacer(modifier = Modifier.height(navRailTopPadding))
            }
            orderedDestinations.forEachIndexed { index, destination ->
                val selected = currentDestination == destination && !showSettings
                val isDragged = dragState?.draggedIndex == index

                val targetShiftY = if (dragState != null && !isDragged) {
                    val dragIdx = dragState!!.draggedIndex
                    val targetIdx = getTargetIndex(
                        dragIdx, dragState!!.offsetY, itemHeight, orderedDestinations.size
                    )
                    when {
                        dragIdx < targetIdx && index in (dragIdx + 1)..targetIdx -> -itemHeight
                        dragIdx > targetIdx && index in targetIdx until dragIdx -> itemHeight
                        else -> 0f
                    }
                } else 0f

                val shiftY by animateFloatAsState(targetShiftY)

                Box(
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .onSizeChanged { size ->
                            if (itemHeight == 0f) itemHeight = size.height.toFloat()
                        }
                        .graphicsLayer {
                            val ds = dragState
                            translationY = if (ds != null && ds.draggedIndex == index) ds.offsetY else shiftY
                            shadowElevation = if (ds != null && ds.draggedIndex == index) 8f else 0f
                            alpha = if (ds != null && ds.draggedIndex == index) 0.85f else 1f
                        }
                        .pointerInput(index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragState = DragState(index, 0f)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val ds = dragState ?: return@detectDragGesturesAfterLongPress
                                    dragState = ds.copy(offsetY = ds.offsetY + dragAmount.y)
                                },
                                onDragEnd = {
                                    val ds = dragState ?: return@detectDragGesturesAfterLongPress
                                    val targetIndex = getTargetIndex(
                                        ds.draggedIndex, ds.offsetY, itemHeight,
                                        orderedDestinations.size
                                    )
                                    if (ds.draggedIndex != targetIndex) {
                                        val newOrder = orderedDestinations.toMutableList().apply {
                                            val item = removeAt(ds.draggedIndex)
                                            add(targetIndex, item)
                                        }
                                        onReorder(newOrder)
                                    }
                                    dragState = null
                                },
                                onDragCancel = { dragState = null }
                            )
                        }
                ) {
                    NavigationRailItem(
                        selected = selected,
                        onClick = { onDestinationSelected(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.title,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = { Text(destination.title, style = MaterialTheme.typography.labelSmall) },
                        alwaysShowLabel = true
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TooltipIconButton(
                onClick = onSettingsClicked,
                tooltip = "Settings",
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = MaterialIcons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = if (showSettings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        VerticalDivider()
        val contentTopPadding = maxOf(navRailTopPadding, 24.dp)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .safeDrawingPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = contentTopPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(modifier = Modifier.widthIn(max = 1200.dp).fillMaxSize()) {
                ContentRouter(
                    currentDestination = currentDestination,
                    showSettings = showSettings,
                    authService = authService,
                    featureGate = featureGate,
                    selfHosted = selfHosted,
                    cameraButton = cameraButton,
                    photoButton = photoButton,
                    autoOpenCamera = autoOpenCamera,
                    pendingReceiptFile = pendingReceiptFile,
                    onPendingReceiptConsumed = onPendingReceiptConsumed,
                    onDestinationSelected = onDestinationSelected
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactScaffold(
    currentDestination: ShelfDestination,
    showSettings: Boolean,
    authService: AuthService,
    featureGate: FeatureGate,
    selfHosted: Boolean,
    orderedDestinations: List<ShelfDestination>,
    cameraButton: ReceiptPickerButton?,
    photoButton: ReceiptPickerButton?,
    autoOpenCamera: Boolean = false,
    pendingReceiptFile: PlatformFile? = null,
    onPendingReceiptConsumed: () -> Unit = {},
    onDestinationSelected: (ShelfDestination) -> Unit,
    onSettingsClicked: () -> Unit,
    onReorder: (List<ShelfDestination>) -> Unit
) {
    val primaryDestinations = remember(orderedDestinations) {
        orderedDestinations.take(COMPACT_PRIMARY_COUNT)
    }
    val overflowDestinations = remember(orderedDestinations) {
        orderedDestinations.drop(COMPACT_PRIMARY_COUNT)
    }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showReorderSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            ContentRouter(
                currentDestination = currentDestination,
                showSettings = showSettings,
                authService = authService,
                featureGate = featureGate,
                selfHosted = selfHosted,
                cameraButton = cameraButton,
                photoButton = photoButton,
                autoOpenCamera = autoOpenCamera,
                pendingReceiptFile = pendingReceiptFile,
                onPendingReceiptConsumed = onPendingReceiptConsumed,
                onDestinationSelected = onDestinationSelected
            )
        }

        NavigationBar {
            primaryDestinations.forEach { destination ->
                val selected = currentDestination == destination && !showSettings
                NavigationBarItem(
                    selected = selected,
                    onClick = { onDestinationSelected(destination) },
                    icon = { Icon(destination.icon, contentDescription = destination.title) },
                    label = { Text(destination.title) }
                )
            }
            NavigationBarItem(
                selected = currentDestination in overflowDestinations || showSettings,
                onClick = { showMoreSheet = true },
                icon = { Icon(MaterialIcons.Filled.Menu, contentDescription = "More") },
                label = { Text("More") }
            )
        }
    }

    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                overflowDestinations.forEach { destination ->
                    ListItem(
                        headlineContent = { Text(destination.title) },
                        leadingContent = { Icon(destination.icon, contentDescription = destination.title) },
                        modifier = Modifier.clickable {
                            onDestinationSelected(destination)
                            showMoreSheet = false
                        }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ListItem(
                    headlineContent = { Text("Settings") },
                    leadingContent = { Icon(MaterialIcons.Filled.Settings, contentDescription = "Settings") },
                    modifier = Modifier.clickable {
                        onSettingsClicked()
                        showMoreSheet = false
                    }
                )
                ListItem(
                    headlineContent = { Text("Edit tabs") },
                    leadingContent = { Icon(MaterialIcons.Filled.Edit, contentDescription = "Edit tabs") },
                    modifier = Modifier.clickable {
                        showMoreSheet = false
                        showReorderSheet = true
                    }
                )
            }
        }
    }

    if (showReorderSheet) {
        ReorderTabsSheet(
            orderedDestinations = orderedDestinations,
            onReorder = onReorder,
            onDismiss = { showReorderSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderTabsSheet(
    orderedDestinations: List<ShelfDestination>,
    onReorder: (List<ShelfDestination>) -> Unit,
    onDismiss: () -> Unit
) {
    var localOrder by remember { mutableStateOf(orderedDestinations) }
    var dragState by remember { mutableStateOf<DragState?>(null) }
    var itemHeight by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(
        onDismissRequest = {
            onReorder(localOrder)
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "Reorder tabs",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            localOrder.forEachIndexed { index, destination ->
                val isDragged = dragState?.draggedIndex == index

                val targetShiftY = if (dragState != null && !isDragged) {
                    val dragIdx = dragState!!.draggedIndex
                    val targetIdx = getTargetIndex(
                        dragIdx, dragState!!.offsetY, itemHeight, localOrder.size
                    )
                    when {
                        dragIdx < targetIdx && index in (dragIdx + 1)..targetIdx -> -itemHeight
                        dragIdx > targetIdx && index in targetIdx until dragIdx -> itemHeight
                        else -> 0f
                    }
                } else 0f

                val shiftY by animateFloatAsState(targetShiftY)

                ListItem(
                    headlineContent = { Text(destination.title) },
                    leadingContent = { Icon(destination.icon, contentDescription = destination.title) },
                    trailingContent = {
                        Icon(
                            MaterialIcons.Filled.Drag_indicator,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .onSizeChanged { size ->
                            if (itemHeight == 0f) itemHeight = size.height.toFloat()
                        }
                        .graphicsLayer {
                            val ds = dragState
                            translationY = if (ds != null && ds.draggedIndex == index) ds.offsetY else shiftY
                            shadowElevation = if (ds != null && ds.draggedIndex == index) 8f else 0f
                            alpha = if (ds != null && ds.draggedIndex == index) 0.85f else 1f
                        }
                        .pointerInput(index) {
                            detectDragGestures(
                                onDragStart = {
                                    dragState = DragState(index, 0f)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val ds = dragState ?: return@detectDragGestures
                                    dragState = ds.copy(offsetY = ds.offsetY + dragAmount.y)
                                },
                                onDragEnd = {
                                    val ds = dragState ?: return@detectDragGestures
                                    val targetIndex = getTargetIndex(
                                        ds.draggedIndex, ds.offsetY, itemHeight, localOrder.size
                                    )
                                    if (ds.draggedIndex != targetIndex) {
                                        localOrder = localOrder.toMutableList().apply {
                                            val item = removeAt(ds.draggedIndex)
                                            add(targetIndex, item)
                                        }
                                    }
                                    dragState = null
                                },
                                onDragCancel = { dragState = null }
                            )
                        }
                )
            }
        }
    }
}

@Composable
private fun ContentRouter(
    currentDestination: ShelfDestination,
    showSettings: Boolean,
    authService: AuthService,
    featureGate: FeatureGate,
    selfHosted: Boolean,
    cameraButton: ReceiptPickerButton?,
    photoButton: ReceiptPickerButton?,
    autoOpenCamera: Boolean = false,
    pendingReceiptFile: PlatformFile? = null,
    onPendingReceiptConsumed: () -> Unit = {},
    onDestinationSelected: (ShelfDestination) -> Unit = {}
) {
    if (showSettings) {
        SettingsView(
            selfHosted = selfHosted,
            authService = authService,
            featureGate = featureGate
        )
    } else {
        when (currentDestination) {
            ShelfDestination.HOME -> HomeView(onNavigate = onDestinationSelected)
            ShelfDestination.WEEKLY -> WeeklyView()
            ShelfDestination.HISTORY -> HistoryView()
            ShelfDestination.ACCOUNTS -> AccountsView()
            ShelfDestination.CATEGORIES -> CategoriesView()
            ShelfDestination.BUDGET -> BudgetView()
            ShelfDestination.SCHEDULES -> SchedulesView()
            ShelfDestination.IMPORT -> ImportView()
            ShelfDestination.RECEIPTS -> ReceiptsScreen(
                cameraButton = cameraButton,
                photoButton = photoButton,
                autoOpenCamera = autoOpenCamera,
                pendingReceiptFile = pendingReceiptFile,
                onPendingReceiptConsumed = onPendingReceiptConsumed
            )
        }
    }
}
