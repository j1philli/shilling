package finance.shilling.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Calendar_month
import com.composables.icons.materialicons.filled.History
import com.composables.icons.materialicons.filled.Home
import com.composables.icons.materialicons.filled.Receipt
import com.composables.icons.materialicons.filled.Settings
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_TAB_ORDER
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.FeatureGate
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import finance.shilling.shared.presentation.HomeDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import finance.shilling.shared.presentation.PlanPeriod
import finance.shilling.shared.presentation.PlanRequest
import finance.shilling.shared.presentation.PlanRequests
import finance.shilling.shared.presentation.PlanSection

enum class ShelfDestination(
    val title: String,
    val icon: ImageVector,
    val route: Any
) {
    HOME("Home", MaterialIcons.Filled.Home, HomeRoute),
    PLAN("Plan", MaterialIcons.Filled.Calendar_month, PlanRoute),
    ACTIVITY("Activity", MaterialIcons.Filled.History, ActivityRoute),
    RECEIPTS("Receipts", MaterialIcons.Filled.Receipt, ReceiptsRoute),
}

/** Platform-provided receipt capture buttons (camera / photo library on mobile). */
data class ReceiptPickers(
    val camera: ReceiptPickerButton? = null,
    val photo: ReceiptPickerButton? = null
)

val LocalReceiptPickers = staticCompositionLocalOf { ReceiptPickers() }

/** True when the platform draws the tab bar (see [PlatformTabBar]). */
val LocalPlatformTabBar = staticCompositionLocalOf { false }

/**
 * A tab bar drawn by the platform instead of Compose (iOS: a native UITabBarController).
 * When given to [ShillingScaffold], the scaffold draws no bar or rail of its own.
 */
class PlatformTabBar(
    /** Taps on the platform bar: a tab, or null for Settings. */
    val selections: Flow<ShelfDestination?>,
    /**
     * Tab order (Settings is always last), the current selection (null = Settings), and whether a
     * detail/editor route is open (the platform must show Compose over a native tab screen then).
     */
    val onChange: (order: List<ShelfDestination>, selected: ShelfDestination?, detailOpen: Boolean) -> Unit,
    /** The scaffold left the screen (e.g. sign-out); the platform bar should hide. */
    val onDispose: () -> Unit,
    /** Taps on a platform-drawn Home screen's tiles, routed like the Compose Home. */
    val homeNavigation: Flow<HomeDestination> = emptyFlow(),
    /** Detail/editor screens a platform-drawn screen asks Compose to open. */
    val routeRequests: Flow<PlatformRoute> = emptyFlow()
)

/** Compose screens a platform-drawn screen can open (while those screens are still Compose). */
sealed interface PlatformRoute {
    /** A transaction; null id creates one. */
    data class Transaction(val postingId: String?) : PlatformRoute
    data object Import : PlatformRoute
    /** A receipt; null id adds one. */
    data class Receipt(val receiptId: String?) : PlatformRoute
    /** A schedule; null id adds one (of [type], when given). */
    data class Schedule(val scheduleId: String?, val type: ScheduleType? = null) : PlatformRoute
    data class Category(val categoryId: String?) : PlatformRoute
    data class Account(val accountId: String?) : PlatformRoute
}

/** Tabs that were merged into others, mapped to where they live now. */
private val LEGACY_TABS = mapOf(
    "WEEKLY" to "PLAN", "BUDGET" to "PLAN", "SCHEDULES" to "PLAN", "CATEGORIES" to "PLAN",
    "ACCOUNTS" to "PLAN", "HISTORY" to "ACTIVITY", "IMPORT" to "ACTIVITY"
)

private fun loadTabOrder(settings: Settings): List<ShelfDestination> {
    val saved = settings.getStringOrNull(SETTINGS_KEY_TAB_ORDER)
        ?: return ShelfDestination.entries.toList()
    // Merged tabs take the first of their predecessors' saved positions.
    val result = saved.split(",")
        .map { name -> LEGACY_TABS[name] ?: name }
        .mapNotNull { name -> ShelfDestination.entries.find { it.name == name } }
        .distinct()
        .toMutableList()
    // Append any new destinations not in saved order
    ShelfDestination.entries.filter { it !in result }.forEach { result.add(it) }
    return result
}

private fun saveTabOrder(settings: Settings, order: List<ShelfDestination>) {
    settings.putString(SETTINGS_KEY_TAB_ORDER, order.joinToString(",") { it.name })
}

/** Restores the default tab order (used by Settings). */
fun resetTabOrder(settings: Settings) {
    settings.remove(SETTINGS_KEY_TAB_ORDER)
    TabOrderChanges.version++
}

/** Bumped when the tab order is reset elsewhere so the scaffold reloads it. */
internal object TabOrderChanges {
    var version by mutableIntStateOf(0)
}

/**
 * Long-press-and-drag reordering shared by the bottom bar (horizontal) and the rail
 * (vertical). Items shift out of the way while dragging; the order commits on release.
 */
private class TabReorderState(val vertical: Boolean) {
    var draggedIndex by mutableStateOf<Int?>(null)
    var offset by mutableFloatStateOf(0f)
    var itemSize by mutableFloatStateOf(0f)

    /** The release that ends a drag also lands as a tap on a tab; ignore taps briefly after a drag. */
    var suppressClicks by mutableStateOf(false)

    /** Wraps a tab's onClick so a drop doesn't also navigate. */
    fun guardClick(onClick: () -> Unit): () -> Unit = { if (!suppressClicks) onClick() }

    fun targetIndex(count: Int): Int? {
        val from = draggedIndex ?: return null
        if (itemSize <= 0f) return from
        return (from + (offset / itemSize).roundToInt()).coerceIn(0, count - 1)
    }

    fun shiftFor(index: Int, count: Int): Float {
        val from = draggedIndex ?: return 0f
        val to = targetIndex(count) ?: return 0f
        return when {
            index == from -> 0f
            from < to && index in (from + 1)..to -> -itemSize
            from > to && index in to until from -> itemSize
            else -> 0f
        }
    }

    fun reset() {
        draggedIndex = null
        offset = 0f
    }
}

@Composable
private fun Modifier.reorderableTab(
    state: TabReorderState,
    index: Int,
    order: List<ShelfDestination>,
    onReorder: (List<ShelfDestination>) -> Unit
): Modifier {
    val haptics = LocalHapticFeedback.current
    val shift by animateFloatAsState(state.shiftFor(index, order.size))
    val isDragged = state.draggedIndex == index
    val currentOnReorder by rememberUpdatedState(onReorder)
    val scope = rememberCoroutineScope()
    return this
        .zIndex(if (isDragged) 1f else 0f)
        .onSizeChanged { size -> state.itemSize = (if (state.vertical) size.height else size.width).toFloat() }
        .graphicsLayer {
            val translation = if (isDragged) state.offset else shift
            if (state.vertical) translationY = translation else translationX = translation
            val scale = if (isDragged) 1.08f else 1f
            scaleX = scale
            scaleY = scale
            alpha = if (isDragged) 0.9f else 1f
        }
        .pointerInput(index, order) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.suppressClicks = true
                    state.draggedIndex = index
                    state.offset = 0f
                },
                onDrag = { change, amount ->
                    change.consume()
                    state.offset += if (state.vertical) amount.y else amount.x
                },
                onDragEnd = {
                    val from = state.draggedIndex
                    val to = state.targetIndex(order.size)
                    if (from != null && to != null && from != to) {
                        currentOnReorder(order.toMutableList().apply { add(to, removeAt(from)) })
                    }
                    state.reset()
                    scope.launch {
                        delay(400)
                        state.suppressClicks = false
                    }
                },
                onDragCancel = {
                    state.reset()
                    state.suppressClicks = false
                }
            )
        }
}

private const val TAB_FADE_OUT_MS = 90
private const val TAB_FADE_IN_MS = 150
private const val PUSH_MS = 300

/** Top-level destinations: the tabs plus Settings. */
private fun NavDestination.isTopLevel(): Boolean =
    shelfDestination() != null || hasRoute(SettingsRoute::class)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.isTopLevel() && targetState.destination.isTopLevel()

private fun NavDestination.shelfDestination(): ShelfDestination? =
    ShelfDestination.entries.firstOrNull { hasRoute(it.route::class) }

internal fun NavHostController.navigateToTab(destination: ShelfDestination) = navigateTopLevel(destination.route)

internal fun NavHostController.navigateToSettings() = navigateTopLevel(SettingsRoute)

/** Top-level switch: keep Home at the root, save/restore each destination's own stack. */
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
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
    onPendingReceiptConsumed: () -> Unit = {},
    developerToolsEnabled: Boolean = false,
    /** Platform hook given the app's NavController (the web build binds browser history). */
    navControllerHook: @Composable (NavHostController) -> Unit = {},
    platformTabBar: PlatformTabBar? = null
) {
    val settings: Settings = koinInject()
    val navController = rememberNavController()
    var orderedDestinations by remember(TabOrderChanges.version) { mutableStateOf(loadTabOrder(settings)) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Detail routes aren't part of any tab, so keep highlighting the tab they were opened from.
    var selectedTabName by rememberSaveable { mutableStateOf(ShelfDestination.HOME.name) }
    LaunchedEffect(currentDestination) {
        currentDestination?.shelfDestination()?.let { selectedTabName = it.name }
    }
    val showingSettings = currentDestination?.hasRoute(SettingsRoute::class) == true
    val selectedTab = if (showingSettings) null else (
        (LEGACY_TABS[selectedTabName] ?: selectedTabName)
            .let { name -> ShelfDestination.entries.find { it.name == name } } ?: ShelfDestination.HOME
    )

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val snackbarController = remember(snackbarHostState, snackbarScope) {
        SnackbarController(snackbarHostState, snackbarScope)
    }

    LaunchedEffect(navController) {
        externalNavRequest.filterNotNull().collect { dest ->
            if (selectedTabName != dest.name || showingSettings) navController.navigateToTab(dest)
        }
    }
    LaunchedEffect(pendingReceiptFile, autoOpenCamera) {
        if (pendingReceiptFile != null || autoOpenCamera) {
            navController.navigateToTab(ShelfDestination.RECEIPTS)
            navController.navigate(ReceiptRoute())
        }
    }

    // Lets Home open a specific Plan section/period; consumed by whichever Plan screen shows.
    val planRequests = koinInject<PlanRequests>()
    val openHomeDestination: (HomeDestination) -> Unit = { destination ->
        val request = when (destination) {
            HomeDestination.ACCOUNTS -> PlanRequest(PlanSection.ACCOUNTS)
            HomeDestination.WEEK -> PlanRequest(PlanSection.OVERVIEW, PlanPeriod.WEEK)
            HomeDestination.MONTH -> PlanRequest(PlanSection.OVERVIEW, PlanPeriod.MONTH)
            HomeDestination.SCHEDULES -> PlanRequest(PlanSection.SCHEDULES)
            HomeDestination.CATEGORIES -> PlanRequest(PlanSection.CATEGORIES)
            HomeDestination.ACTIVITY, HomeDestination.RECEIPTS -> null
        }
        when {
            request != null -> {
                planRequests.request(request)
                navController.navigateToTab(ShelfDestination.PLAN)
            }
            destination == HomeDestination.ACTIVITY -> navController.navigateToTab(ShelfDestination.ACTIVITY)
            else -> navController.navigateToTab(ShelfDestination.RECEIPTS)
        }
    }

    val onSelectTab: (ShelfDestination) -> Unit = { navController.navigateToTab(it) }
    val onSettings: () -> Unit = { navController.navigateToSettings() }
    val onReorder: (List<ShelfDestination>) -> Unit = { newOrder ->
        orderedDestinations = newOrder
        saveTabOrder(settings, newOrder)
    }

    val host: @Composable (Modifier) -> Unit = { modifier ->
        CompositionLocalProvider(
            LocalSnackbarController provides snackbarController,
            LocalReceiptPickers provides ReceiptPickers(cameraButton, photoButton),
            LocalAutoLaunchCamera provides autoOpenCamera,
            LocalPlatformTabBar provides (platformTabBar != null)
        ) {
            ShillingNavHost(
                navController = navController,
                onHomeDestination = openHomeDestination,
                modifier = modifier,
                authService = authService,
                featureGate = featureGate,
                selfHosted = selfHosted,
                developerToolsEnabled = developerToolsEnabled,
                pendingReceiptFile = pendingReceiptFile,
                onPendingReceiptConsumed = onPendingReceiptConsumed
            )
        }
    }

    val useCompactChrome = !currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    if (platformTabBar != null) {
        LaunchedEffect(navController, platformTabBar) {
            platformTabBar.selections.collect { dest -> if (dest == null) onSettings() else onSelectTab(dest) }
        }
        LaunchedEffect(navController, platformTabBar) {
            platformTabBar.homeNavigation.collect(openHomeDestination)
        }
        LaunchedEffect(navController, platformTabBar) {
            platformTabBar.routeRequests.collect { request ->
                when (request) {
                    is PlatformRoute.Transaction -> navController.navigate(TransactionRoute(request.postingId))
                    PlatformRoute.Import -> navController.navigate(ImportRoute)
                    is PlatformRoute.Receipt -> navController.navigate(ReceiptRoute(request.receiptId))
                    is PlatformRoute.Schedule -> navController.navigate(ScheduleRoute(request.scheduleId, request.type?.name))
                    is PlatformRoute.Category -> navController.navigate(CategoryRoute(request.categoryId))
                    is PlatformRoute.Account -> navController.navigate(AccountRoute(request.accountId))
                }
            }
        }
        val detailOpen = currentDestination != null && !currentDestination.isTopLevel()
        LaunchedEffect(platformTabBar, orderedDestinations, selectedTab, detailOpen) {
            platformTabBar.onChange(orderedDestinations, selectedTab, detailOpen)
        }
        DisposableEffect(platformTabBar) {
            onDispose { platformTabBar.onDispose() }
        }
        PlatformTabBarScaffold(snackbarHostState = snackbarHostState, host = host)
    } else if (useCompactChrome) {
        CompactScaffold(
            selectedTab = selectedTab,
            showingSettings = showingSettings,
            orderedDestinations = orderedDestinations,
            snackbarHostState = snackbarHostState,
            onSelectTab = onSelectTab,
            onSettings = onSettings,
            onReorder = onReorder,
            host = host
        )
    } else {
        ExpandedScaffold(
            selectedTab = selectedTab,
            showingSettings = showingSettings,
            navRailTopPadding = navRailTopPadding,
            orderedDestinations = orderedDestinations,
            snackbarHostState = snackbarHostState,
            onSelectTab = onSelectTab,
            onSettings = onSettings,
            onReorder = onReorder,
            host = host
        )
    }
    navControllerHook(navController)
}

@Composable
private fun ShillingNavHost(
    navController: NavHostController,
    onHomeDestination: (HomeDestination) -> Unit,
    modifier: Modifier,
    authService: AuthService,
    featureGate: FeatureGate,
    selfHosted: Boolean,
    developerToolsEnabled: Boolean,
    pendingReceiptFile: PlatformFile?,
    onPendingReceiptConsumed: () -> Unit
) {
    val back: () -> Unit = { navController.popBackStack() }
    // Tabs cross-fade quickly; detail pages slide in from the trailing edge and back out.
    // (The platform default on iOS slides every change, including tab switches.)
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        enterTransition = {
            if (isTabSwitch()) fadeIn(tween(TAB_FADE_IN_MS, delayMillis = TAB_FADE_OUT_MS))
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(PUSH_MS))
        },
        exitTransition = {
            if (isTabSwitch()) fadeOut(tween(TAB_FADE_OUT_MS))
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(PUSH_MS)) { it / 4 }
        },
        popEnterTransition = {
            if (isTabSwitch()) fadeIn(tween(TAB_FADE_IN_MS, delayMillis = TAB_FADE_OUT_MS))
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(PUSH_MS)) { it / 4 }
        },
        popExitTransition = {
            if (isTabSwitch()) fadeOut(tween(TAB_FADE_OUT_MS))
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(PUSH_MS))
        }
    ) {
        composable<HomeRoute> {
            HomeView(onDestination = onHomeDestination)
        }
        composable<PlanRoute> {
            PlanView(
                onOpenTransaction = { navController.navigate(TransactionRoute(it)) },
                onOpenSchedule = { id, type -> navController.navigate(ScheduleRoute(id, type?.name)) },
                onOpenCategory = { navController.navigate(CategoryRoute(it)) },
                onOpenAccount = { navController.navigate(AccountRoute(it)) }
            )
        }
        composable<ActivityRoute> {
            ActivityView(
                onOpenTransaction = { navController.navigate(TransactionRoute(it)) },
                onOpenImport = { navController.navigate(ImportRoute) }
            )
        }
        composable<ImportRoute> { ImportView(onClose = back) }
        composable<ReceiptsRoute> {
            ReceiptsScreen(onOpenReceipt = { navController.navigate(ReceiptRoute(it)) })
        }
        composable<SettingsRoute> {
            SettingsView(developerToolsEnabled = developerToolsEnabled)
        }

        composable<TransactionRoute> { entry ->
            val route = entry.toRoute<TransactionRoute>()
            TransactionEditor(
                postingId = route.postingId,
                navIcon = ScreenNavIcon.BACK,
                onClose = back,
                onSaved = { back() }
            )
        }
        composable<AccountRoute> { entry ->
            AccountEditor(
                accountId = entry.toRoute<AccountRoute>().accountId,
                navIcon = ScreenNavIcon.BACK,
                onClose = back,
                onSaved = { back() }
            )
        }
        composable<CategoryRoute> { entry ->
            CategoryEditor(
                categoryId = entry.toRoute<CategoryRoute>().categoryId,
                navIcon = ScreenNavIcon.BACK,
                onClose = back,
                onSaved = { back() }
            )
        }
        composable<ScheduleRoute> { entry ->
            val route = entry.toRoute<ScheduleRoute>()
            ScheduleEditor(
                scheduleId = route.scheduleId,
                presetType = route.type?.let { name -> ScheduleType.entries.firstOrNull { it.name == name } },
                navIcon = ScreenNavIcon.BACK,
                onClose = back,
                onSaved = { back() }
            )
        }
        composable<ReceiptRoute> { entry ->
            ReceiptEditor(
                receiptId = entry.toRoute<ReceiptRoute>().receiptId,
                navIcon = ScreenNavIcon.BACK,
                onClose = back,
                onSaved = { back() },
                initialFile = pendingReceiptFile,
                onInitialFileConsumed = onPendingReceiptConsumed
            )
        }
    }
}

@Composable
private fun ExpandedScaffold(
    selectedTab: ShelfDestination?,
    showingSettings: Boolean,
    navRailTopPadding: Dp,
    orderedDestinations: List<ShelfDestination>,
    snackbarHostState: SnackbarHostState,
    onSelectTab: (ShelfDestination) -> Unit,
    onSettings: () -> Unit,
    onReorder: (List<ShelfDestination>) -> Unit,
    host: @Composable (Modifier) -> Unit
) {
    val reorder = remember { TabReorderState(vertical = true) }
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail {
            if (navRailTopPadding > 0.dp) {
                Spacer(modifier = Modifier.height(navRailTopPadding))
            }
            // Press and hold a tab, then drag, to reorder.
            orderedDestinations.forEachIndexed { index, destination ->
                NavigationRailItem(
                    selected = destination == selectedTab,
                    onClick = reorder.guardClick { onSelectTab(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(destination.title) },
                    modifier = Modifier.reorderableTab(reorder, index, orderedDestinations, onReorder)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            NavigationRailItem(
                selected = showingSettings,
                onClick = onSettings,
                icon = { Icon(MaterialIcons.Filled.Settings, contentDescription = null) },
                label = { Text("Settings") },
                modifier = Modifier.padding(bottom = Spacing.md)
            )
        }
        VerticalDivider()
        Scaffold(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(top = navRailTopPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                host(Modifier.widthIn(max = 1200.dp).fillMaxSize())
            }
        }
    }
}

/** Content only: the platform draws the tab bar and reports its height through the safe area. */
@Composable
private fun PlatformTabBarScaffold(
    snackbarHostState: SnackbarHostState,
    host: @Composable (Modifier) -> Unit
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            host(Modifier.widthIn(max = 1200.dp).fillMaxSize())
        }
    }
}

@Composable
private fun CompactScaffold(
    selectedTab: ShelfDestination?,
    showingSettings: Boolean,
    orderedDestinations: List<ShelfDestination>,
    snackbarHostState: SnackbarHostState,
    onSelectTab: (ShelfDestination) -> Unit,
    onSettings: () -> Unit,
    onReorder: (List<ShelfDestination>) -> Unit,
    host: @Composable (Modifier) -> Unit
) {
    val reorder = remember { TabReorderState(vertical = false) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                // Press and hold a tab, then drag, to reorder. Settings stays pinned at the end.
                orderedDestinations.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = destination == selectedTab,
                        onClick = reorder.guardClick { onSelectTab(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.title) },
                        modifier = Modifier.reorderableTab(reorder, index, orderedDestinations, onReorder)
                    )
                }
                NavigationBarItem(
                    selected = showingSettings,
                    onClick = onSettings,
                    icon = { Icon(MaterialIcons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        host(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        )
    }
}
