package finance.shilling.shared.ui

import kotlinx.serialization.Serializable

// Top-level destinations (one per ShelfDestination).
@Serializable object HomeRoute
@Serializable object PlanRoute
@Serializable object ActivityRoute
@Serializable object ReceiptsRoute
@Serializable object SettingsRoute

// Detail routes. A null id means "create new". These sit at the root of the graph so any
// tab can open them; the tab that opened them stays highlighted.
@Serializable data class TransactionRoute(val postingId: String? = null)
@Serializable data class AccountRoute(val accountId: String? = null)
@Serializable data class CategoryRoute(val categoryId: String? = null)
@Serializable data class ScheduleRoute(val scheduleId: String? = null, val type: String? = null)
@Serializable data class ReceiptRoute(val receiptId: String? = null)
@Serializable object ImportRoute
