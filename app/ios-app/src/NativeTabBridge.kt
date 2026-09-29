package finance.shilling.app

import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.presentation.HomeDestination
import finance.shilling.shared.ui.PlatformRoute
import finance.shilling.shared.ui.PlatformTabBar
import finance.shilling.shared.ui.ShelfDestination
import kotlinx.coroutines.flow.MutableSharedFlow

/** One tab of the native tab bar. [key] is a [ShelfDestination] name or [NativeTabBridge.SETTINGS]. */
data class NativeTab(val key: String, val title: String, val systemImage: String)

/**
 * Connects the Compose scaffold to the native UITabBarController in App.swift.
 * Swift listens for the tab list + selection and reports taps back through [select].
 */
object NativeTabBridge {
    const val SETTINGS = "SETTINGS"

    private val selections = MutableSharedFlow<ShelfDestination?>(extraBufferCapacity = 1)
    private val homeNavigation = MutableSharedFlow<HomeDestination>(extraBufferCapacity = 1)
    private val routeRequests = MutableSharedFlow<PlatformRoute>(extraBufferCapacity = 1)
    private var listener: ((List<NativeTab>, String?, Boolean) -> Unit)? = null
    private var tabs: List<NativeTab> = emptyList()
    private var selected: String? = null
    private var detailOpen = false

    /**
     * Swift: receives the tabs (empty while the main scaffold isn't showing), the selected key, and
     * whether a Compose detail/editor screen is open (shown over native tab screens).
     */
    fun setListener(listener: (List<NativeTab>, String?, Boolean) -> Unit) {
        this.listener = listener
        listener(tabs, selected, detailOpen)
    }

    /** Swift: open a transaction in the (Compose) editor; null creates one. */
    fun openTransaction(postingId: String?) {
        routeRequests.tryEmit(PlatformRoute.Transaction(postingId))
    }

    /** Swift: open a receipt in the (Compose) editor; null adds one. */
    fun openReceipt(receiptId: String?) {
        routeRequests.tryEmit(PlatformRoute.Receipt(receiptId))
    }

    /** Swift: open a schedule in the (Compose) editor; null adds one of [type]. */
    fun openSchedule(scheduleId: String?, type: ScheduleType?) {
        routeRequests.tryEmit(PlatformRoute.Schedule(scheduleId, type))
    }

    fun openCategory(categoryId: String?) {
        routeRequests.tryEmit(PlatformRoute.Category(categoryId))
    }

    fun openAccount(accountId: String?) {
        routeRequests.tryEmit(PlatformRoute.Account(accountId))
    }

    /** Swift: open CSV import (Compose). */
    fun openImport() {
        routeRequests.tryEmit(PlatformRoute.Import)
    }

    /** Swift: the user tapped the tab with [key]. */
    fun select(key: String) {
        selections.tryEmit(if (key == SETTINGS) null else ShelfDestination.valueOf(key))
    }

    /** Swift: a tile on the native Home screen was tapped; Compose opens the matching screen. */
    fun openHome(destination: HomeDestination) {
        homeNavigation.tryEmit(destination)
    }

    val tabBar = PlatformTabBar(
        selections = selections,
        onChange = { order, selectedTab, detail ->
            publish(
                tabs = order.map { NativeTab(it.name, it.title, it.systemImage()) } +
                    NativeTab(SETTINGS, "Settings", "gearshape"),
                selected = selectedTab?.name ?: SETTINGS,
                detailOpen = detail
            )
        },
        onDispose = { publish(emptyList(), null, false) },
        homeNavigation = homeNavigation,
        routeRequests = routeRequests
    )

    private fun publish(tabs: List<NativeTab>, selected: String?, detailOpen: Boolean) {
        this.tabs = tabs
        this.selected = selected
        this.detailOpen = detailOpen
        listener?.invoke(tabs, selected, detailOpen)
    }

    private fun ShelfDestination.systemImage(): String = when (this) {
        ShelfDestination.HOME -> "house"
        ShelfDestination.PLAN -> "calendar"
        ShelfDestination.ACTIVITY -> "clock.arrow.circlepath"
        ShelfDestination.RECEIPTS -> "receipt"
    }
}
