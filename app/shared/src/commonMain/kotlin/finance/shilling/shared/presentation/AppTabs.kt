package finance.shilling.shared.presentation

import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_TAB_ORDER

/** The app's tabs, in default order. Settings is pinned after them and isn't reorderable. */
enum class AppTab(val title: String) {
    HOME("Home"),
    PLAN("Plan"),
    ACTIVITY("Activity"),
    RECEIPTS("Receipts")
}

/** The user's saved tab order. */
object TabOrder {
    /** Tabs that were merged into others, mapped to where they live now. */
    private val legacyTabs = mapOf(
        "WEEKLY" to "PLAN", "BUDGET" to "PLAN", "SCHEDULES" to "PLAN", "CATEGORIES" to "PLAN",
        "ACCOUNTS" to "PLAN", "HISTORY" to "ACTIVITY", "IMPORT" to "ACTIVITY"
    )

    /** A saved tab name (possibly of a merged tab) as today's tab. */
    fun resolve(name: String?): AppTab? =
        (legacyTabs[name] ?: name)?.let { resolved -> AppTab.entries.find { it.name == resolved } }

    fun load(settings: Settings): List<AppTab> {
        val saved = settings.getStringOrNull(SETTINGS_KEY_TAB_ORDER) ?: return AppTab.entries.toList()
        // Merged tabs take the first of their predecessors' saved positions.
        val result = saved.split(",").mapNotNull(::resolve).distinct().toMutableList()
        // Append any new tabs not in the saved order.
        AppTab.entries.filter { it !in result }.forEach { result.add(it) }
        return result
    }

    fun save(settings: Settings, order: List<AppTab>) {
        settings.putString(SETTINGS_KEY_TAB_ORDER, order.joinToString(",") { it.name })
    }

    fun reset(settings: Settings) {
        settings.remove(SETTINGS_KEY_TAB_ORDER)
    }
}

/** Where a Home tile leads: a tab and, for Plan, the section/period to open. */
data class HomeTarget(val tab: AppTab, val planRequest: PlanRequest? = null)

val HomeDestination.target: HomeTarget
    get() = when (this) {
        HomeDestination.ACCOUNTS -> HomeTarget(AppTab.PLAN, PlanRequest(PlanSection.ACCOUNTS))
        HomeDestination.WEEK -> HomeTarget(AppTab.PLAN, PlanRequest(PlanSection.OVERVIEW, PlanPeriod.WEEK))
        HomeDestination.MONTH -> HomeTarget(AppTab.PLAN, PlanRequest(PlanSection.OVERVIEW, PlanPeriod.MONTH))
        HomeDestination.SCHEDULES -> HomeTarget(AppTab.PLAN, PlanRequest(PlanSection.SCHEDULES))
        HomeDestination.CATEGORIES -> HomeTarget(AppTab.PLAN, PlanRequest(PlanSection.CATEGORIES))
        HomeDestination.ACTIVITY -> HomeTarget(AppTab.ACTIVITY)
        HomeDestination.RECEIPTS -> HomeTarget(AppTab.RECEIPTS)
    }
