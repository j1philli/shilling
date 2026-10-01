package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.Settings
import finance.shilling.core.auth.HostedSpaceCommand
import finance.shilling.core.auth.HostedSpacesResponse
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.DEFAULT_SERVER_URL
import finance.shilling.shared.data.sync.ServerApi
import finance.shilling.shared.session.SessionPhase
import finance.shilling.shared.session.SessionState
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SpaceSelectionCallback(val apply: suspend (HostedSpacesResponse) -> Unit)
data class SpaceAccountChoice(val spaceId: String, val accountId: String, val label: String)
data class HostedSpacesUiState(
    val data: HostedSpacesResponse = HostedSpacesResponse(),
    val busy: Boolean = false,
    val error: String? = null,
    val gold: Boolean = false,
    val accounts: List<SpaceAccountChoice> = emptyList(),
    val transfers: List<finance.shilling.shared.data.store.LinkedTransfer> = emptyList()
)
class HostedSpacesViewModel(
    private val settings: Settings,
    private val client: HttpClient,
    private val session: SessionState,
    private val selection: SpaceSelectionCallback,
    private val graphs: finance.shilling.shared.data.store.FinanceSpaceGraphs,
    private val transfers: finance.shilling.shared.data.store.LinkedTransferStore,
    private val ids: finance.shilling.shared.data.IdGenerator
) : ViewModel() {
    private val _state = MutableStateFlow(HostedSpacesUiState())
    val state: StateFlow<HostedSpacesUiState> = _state
    init { refresh() }
    private fun api(): ServerApi? {
        val ready = session.phase.value as? SessionPhase.Ready ?: return null
        if (ready.selfHosted) return null
        return ServerApi(client, settings.getStringOrNull(SETTINGS_KEY_SERVER_URL) ?: DEFAULT_SERVER_URL, ready.authService)
    }
    fun refresh() { viewModelScope.launch { load() } }
    private suspend fun load() {
        val api = api() ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        try { _state.value = details(api, api.fetchSpaces()) }
        catch (error: Exception) { if (error is CancellationException) throw error; _state.value = _state.value.copy(busy = false, error = "Finance spaces unavailable. Retry when connected.") }
    }
    suspend fun execute(command: HostedSpaceCommand): String {
        val api = api() ?: return "Sign in to manage hosted finance spaces."
        if (_state.value.busy) return "A space action is already in progress."
        _state.value = _state.value.copy(busy = true, error = null)
        return try {
            val data = api.spaceCommand(command)
            _state.value = details(api, data)
            if (command.action in setOf("select", "create", "accept", "leave", "remove")) selection.apply(data)
            if (data.invitationCode != null) "Invitation created. Share the code with the invited person." else "Finance spaces updated."
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val message = if (error is ResponseException) runCatching {
                Json.parseToJsonElement(error.response.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull() else null
            val text = message ?: "Could not update finance spaces. Refresh to check whether the action completed."
            _state.value = _state.value.copy(busy = false, error = text)
            text
        }
    }
    private suspend fun details(api: ServerApi, data: HostedSpacesResponse): HostedSpacesUiState {
        val gold = runCatching { api.fetchEntitlements().accountPlan == finance.shilling.core.auth.HostedPlan.GOLD }.getOrDefault(false)
        val accounts = if (gold) data.spaces.flatMap { space -> graphs.accountsInSpace(space.id).map { SpaceAccountChoice(space.id, it.id, "${space.name} · ${it.name}") } } else emptyList()
        return HostedSpacesUiState(data = data, gold = gold, accounts = accounts, transfers = if (gold) transfers.list(data.spaces.map { it.id }.toSet()) else emptyList())
    }
    val transferToday: String = today().toString()
    fun newTransferId(): String = finance.shilling.shared.data.store.SPACE_TRANSFER_PREFIX + ids.newId()
    suspend fun transferAction(linkId: String, fromSpace: String, fromAccount: String, toSpace: String, toAccount: String, title: String, amountText: String, date: String, delete: Boolean): String {
        val api = api() ?: return "Sign in to manage linked transfers."
        if (_state.value.busy) return "A space action is already in progress."
        _state.value = _state.value.copy(busy = true, error = null)
        return try {
            val data = api.fetchSpaces()
            check(api.fetchEntitlements().accountPlan == finance.shilling.core.auth.HostedPlan.GOLD) { "Linked space transfers require Gold." }
            val authorized = data.spaces.map { it.id }.toSet()
            check(fromSpace in authorized && toSpace in authorized && graphs.current.id in setOf(fromSpace, toSpace)) { "Open a transfer space and choose two spaces you belong to." }
            val key = finance.shilling.shared.data.store.LinkedTransferKey(linkId, fromSpace, toSpace)
            val existing = transfers.list(authorized).firstOrNull { it.key.linkId == linkId }
            check(existing == null || existing.key == key) { "A transfer's spaces cannot change. Delete the pair and create another transfer." }
            if (delete) transfers.delete(key) else {
                val amount = amountText.toDoubleOrNull()
                require(amount != null && amount.isFinite() && amount > 0 && title.isNotBlank()) { "Enter a title and positive amount." }
                val day = kotlinx.datetime.LocalDate.parse(date)
                val debit = finance.shilling.shared.data.Posting("$linkId:debit", null, finance.shilling.shared.data.ScheduleType.EXPENSE, fromAccount, day, amount, linkId, title.trim())
                val credit = debit.copy(id = "$linkId:credit", type = finance.shilling.shared.data.ScheduleType.INCOME, accountId = toAccount)
                transfers.save(finance.shilling.shared.data.store.LinkedTransfer(key, debit, credit))
            }
            _state.value = details(api, data)
            if (delete) "Linked transfer deleted from both spaces." else "Linked transfer saved in both spaces."
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val message = if (error is IllegalArgumentException || error is IllegalStateException) error.message ?: "Invalid transfer" else "Could not update linked transfer. Refresh both spaces before retrying."
            _state.value = _state.value.copy(busy = false, error = message)
            message
        }
    }

}

