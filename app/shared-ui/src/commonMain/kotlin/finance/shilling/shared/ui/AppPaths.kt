package finance.shilling.shared.ui

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute

/**
 * Stable, readable paths for every route (e.g. `/plan`, `/transaction/abc`, `/schedule/new?type=INCOME`).
 * The web build mirrors these into the URL so browser back/forward, reload and links work.
 */
object AppPaths {
    const val HOME = "/home"

    private const val NEW = "new"

    private val topLevel: List<Pair<String, Any>> = listOf(
        HOME to HomeRoute,
        "/plan" to PlanRoute,
        "/activity" to ActivityRoute,
        "/receipts" to ReceiptsRoute,
        "/settings" to SettingsRoute,
    )

    fun pathOf(entry: NavBackStackEntry): String? {
        val destination = entry.destination
        topLevel.firstOrNull { (_, route) -> destination.hasRoute(route::class) }?.let { return it.first }
        return when {
            destination.hasRoute(ImportRoute::class) -> "/import"
            destination.hasRoute(TransactionRoute::class) ->
                "/transaction/${entry.toRoute<TransactionRoute>().postingId ?: NEW}"
            destination.hasRoute(AccountRoute::class) ->
                "/account/${entry.toRoute<AccountRoute>().accountId ?: NEW}"
            destination.hasRoute(CategoryRoute::class) ->
                "/category/${entry.toRoute<CategoryRoute>().categoryId ?: NEW}"
            destination.hasRoute(ReceiptRoute::class) ->
                "/receipt/${entry.toRoute<ReceiptRoute>().receiptId ?: NEW}"
            destination.hasRoute(ScheduleRoute::class) -> {
                val route = entry.toRoute<ScheduleRoute>()
                "/schedule/${route.scheduleId ?: NEW}" + (route.type?.let { "?type=$it" } ?: "")
            }
            else -> null
        }
    }

    /** Navigates to [path]; returns false when the path isn't recognised. */
    fun navigate(navController: NavHostController, path: String): Boolean {
        val (pathPart, query) = path.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val segments = pathPart.trim('/').split('/')
        val id = segments.getOrNull(1)?.takeIf { it.isNotBlank() && it != NEW }
        ShelfDestination.entries.firstOrNull { tab -> topLevel.any { it.first == pathPart && it.second == tab.route } }
            ?.let { navController.navigateToTab(it); return true }
        if (pathPart == "/settings") {
            navController.navigateToSettings()
            return true
        }
        val route: Any = when (segments.firstOrNull()) {
            "import" -> ImportRoute
            "transaction" -> TransactionRoute(id)
            "account" -> AccountRoute(id)
            "category" -> CategoryRoute(id)
            "receipt" -> ReceiptRoute(id)
            "schedule" -> ScheduleRoute(id, query?.removePrefix("type=")?.takeIf { it.isNotBlank() })
            else -> return false
        }
        navController.navigate(route)
        return true
    }
}
