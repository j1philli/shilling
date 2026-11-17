package finance.shilling.shared.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PlanPeriod(val label: String) { WEEK("Week"), MONTH("Month") }

enum class PlanSection(val label: String) {
    OVERVIEW("Overview"), SCHEDULES("Schedules"), CATEGORIES("Categories"), ACCOUNTS("Accounts")
}

/** Asks Plan to open a specific section (and period), e.g. from a Home tile. */
data class PlanRequest(val section: PlanSection, val period: PlanPeriod? = null)

/** The pending "open Plan at…" request; whichever Plan screen is showing (Compose or native) consumes it. */
class PlanRequests {
    private val _pending = MutableStateFlow<PlanRequest?>(null)
    val pending: StateFlow<PlanRequest?> = _pending

    fun request(request: PlanRequest) {
        _pending.value = request
    }

    fun consume() {
        _pending.value = null
    }
}
