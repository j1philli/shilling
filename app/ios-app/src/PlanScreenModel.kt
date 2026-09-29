package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.presentation.AccountsUiState
import finance.shilling.shared.presentation.AccountsViewModel
import finance.shilling.shared.presentation.CategoriesUiState
import finance.shilling.shared.presentation.CategoriesViewModel
import finance.shilling.shared.presentation.ChangeAmountPrompt
import finance.shilling.shared.presentation.PlanGrouping
import finance.shilling.shared.presentation.PlanOverviewUiState
import finance.shilling.shared.presentation.PlanOverviewViewModel
import finance.shilling.shared.presentation.PlanPeriod
import finance.shilling.shared.presentation.PlanRequest
import finance.shilling.shared.presentation.PlanRequests
import finance.shilling.shared.presentation.SchedulesUiState
import finance.shilling.shared.presentation.SchedulesViewModel
import finance.shilling.shared.presentation.Undoable
import finance.shilling.shared.presentation.parseAmountInput
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import org.koin.mp.KoinPlatform

/**
 * Swift-facing Plan: Overview plus the Schedules, Categories and Accounts sections. Actions that
 * can be undone return their message and keep the undo for [undoLast]. Editors open in Compose.
 */
class PlanScreenModel : IosViewModelHost() {
    private val overview = viewModel<PlanOverviewViewModel>()
    private val schedules = viewModel<SchedulesViewModel>()
    private val categories = viewModel<CategoriesViewModel>()
    private val accounts = viewModel<AccountsViewModel>()
    private val planRequests = KoinPlatform.getKoin().get<PlanRequests>()
    private var lastUndo: Undoable? = null

    @NativeCoroutinesState
    val overviewState: StateFlow<PlanOverviewUiState> = overview.state

    @NativeCoroutinesState
    val schedulesState: StateFlow<SchedulesUiState> = schedules.state

    @NativeCoroutinesState
    val categoriesState: StateFlow<CategoriesUiState> = categories.state

    @NativeCoroutinesState
    val accountsState: StateFlow<AccountsUiState> = accounts.state

    /** "Open Plan at…" (Home tiles); call [consumeRequest] once applied. */
    @NativeCoroutinesState
    val pendingRequest: StateFlow<PlanRequest?> = planRequests.pending

    fun consumeRequest() = planRequests.consume()

    // Overview
    fun setPeriod(period: PlanPeriod) = overview.setPeriod(period)
    fun setGrouping(grouping: PlanGrouping) = overview.setGrouping(grouping)
    fun step(direction: Int) = overview.step(direction)
    fun resetToCurrent() = overview.resetToCurrent()
    fun jumpTo(year: Int, month: Int, day: Int) = overview.jumpTo(LocalDate(year, month, day).toEpochDays())
    fun toggleCategory(key: String) = overview.toggleCategory(key)
    fun changeAmountPrompt(key: String): ChangeAmountPrompt? = overview.changeAmountPrompt(key)
    fun isValidAmount(text: String): Boolean = parseAmountInput(text) != null

    @NativeCoroutines
    suspend fun markPosted(key: String): String? = remember(overview.markPosted(key))

    @NativeCoroutines
    suspend fun unmark(key: String): String? = remember(overview.unmark(key))

    @NativeCoroutines
    suspend fun skip(key: String): String? = remember(overview.skip(key))

    @NativeCoroutines
    suspend fun changeAmount(key: String, text: String): String? = remember(overview.changeAmount(key, text))

    /** Undoes the last action whose message was shown. */
    @NativeCoroutines
    suspend fun undoLast() {
        lastUndo?.undo?.invoke()
        lastUndo = null
    }

    // Schedules
    fun setScheduleFilter(type: ScheduleType?) = schedules.setFilter(type)

    // Navigation to editors (Compose for now)
    fun openTransaction(postingId: String) = NativeTabBridge.openTransaction(postingId)
    fun openSchedule(scheduleId: String?, type: ScheduleType?) = NativeTabBridge.openSchedule(scheduleId, type)
    fun openCategory(categoryId: String?) = NativeTabBridge.openCategory(categoryId)
    fun openAccount(accountId: String?) = NativeTabBridge.openAccount(accountId)

    private fun remember(result: Undoable?): String? {
        lastUndo = result
        return result?.message
    }
}
