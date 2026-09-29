package finance.shilling.app

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
    private var listener: ((List<NativeTab>, String?) -> Unit)? = null
    private var tabs: List<NativeTab> = emptyList()
    private var selected: String? = null

    /** Swift: receives the tabs (empty while the main scaffold isn't showing) and the selected key. */
    fun setListener(listener: (List<NativeTab>, String?) -> Unit) {
        this.listener = listener
        listener(tabs, selected)
    }

    /** Swift: the user tapped the tab with [key]. */
    fun select(key: String) {
        selections.tryEmit(if (key == SETTINGS) null else ShelfDestination.valueOf(key))
    }

    val tabBar = PlatformTabBar(
        selections = selections,
        onChange = { order, selectedTab ->
            publish(
                tabs = order.map { NativeTab(it.name, it.title, it.systemImage()) } +
                    NativeTab(SETTINGS, "Settings", "gearshape"),
                selected = selectedTab?.name ?: SETTINGS
            )
        },
        onDispose = { publish(emptyList(), null) }
    )

    private fun publish(tabs: List<NativeTab>, selected: String?) {
        this.tabs = tabs
        this.selected = selected
        listener?.invoke(tabs, selected)
    }

    private fun ShelfDestination.systemImage(): String = when (this) {
        ShelfDestination.HOME -> "house"
        ShelfDestination.PLAN -> "calendar"
        ShelfDestination.ACTIVITY -> "clock.arrow.circlepath"
        ShelfDestination.RECEIPTS -> "receipt"
    }
}
