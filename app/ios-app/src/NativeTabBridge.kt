package finance.shilling.app

import com.russhwolf.settings.Settings
import finance.shilling.shared.presentation.AppTab
import finance.shilling.shared.presentation.HomeDestination
import finance.shilling.shared.presentation.PlanRequests
import finance.shilling.shared.presentation.TabOrder
import finance.shilling.shared.presentation.target
import org.koin.mp.KoinPlatform

/** One tab of the native tab bar. [key] is an [AppTab] name or [NativeTabBridge.SETTINGS]. */
data class NativeTab(val key: String, val title: String, val systemImage: String)

/** The tab list and Home-tile routing for the native UITabBarController. */
object NativeTabBridge {
    const val SETTINGS = "SETTINGS"

    /** The user's tab order, then Settings. */
    fun tabs(): List<NativeTab> =
        TabOrder.load(KoinPlatform.getKoin().get<Settings>()).map { NativeTab(it.name, it.title, it.systemImage()) } +
            NativeTab(SETTINGS, "Settings", "gearshape")

    /** A Home tile was tapped: queues Plan's section, if any, and returns the tab key to show. */
    fun openHome(destination: HomeDestination): String {
        val target = destination.target
        target.planRequest?.let { KoinPlatform.getKoin().get<PlanRequests>().request(it) }
        return target.tab.name
    }

    private fun AppTab.systemImage(): String = when (this) {
        AppTab.HOME -> "house"
        AppTab.PLAN -> "calendar"
        AppTab.ACTIVITY -> "clock.arrow.circlepath"
        AppTab.RECEIPTS -> "receipt"
    }
}
