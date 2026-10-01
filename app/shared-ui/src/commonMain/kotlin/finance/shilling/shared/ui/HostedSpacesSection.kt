package finance.shilling.shared.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import finance.shilling.core.auth.HostedSpaceCommand
import finance.shilling.shared.presentation.HostedSpacesViewModel
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun HostedSpacesSection(viewModel: HostedSpacesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    var name by remember { mutableStateOf("") }
    var business by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<HostedSpaceCommand?>(null) }
    fun submit(command: HostedSpaceCommand) { scope.launch { snackbar.show(viewModel.execute(command)) } }
    val active = state.data.spaces.firstOrNull { it.id == state.data.activeSpaceId }
    ShillingCard {
        if (state.data.requiresSpaceSelection) Text("Gold has ended. Choose one active space to resume hosted sync; your other local books are kept.", color = MaterialTheme.colorScheme.error)
        Text("Your spaces", style = MaterialTheme.typography.titleMedium)
        Text("Each space keeps its accounts, transactions and receipts separate. One paid member can sponsor the space.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(enabled = !state.busy, onClick = viewModel::refresh) { Text("Refresh spaces") }
        state.data.spaces.forEach { space ->
            Text("${space.name} · ${space.kind} · ${space.role}${if (space.id == active?.id) " · active" else ""}")
            if (space.id != active?.id) OutlinedButton(enabled = !state.busy, onClick = { submit(HostedSpaceCommand("select", spaceId = space.id)) }) { Text("Open ${space.name}") }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("New space name") }, singleLine = true)
        TextButton(onClick = { business = !business }) { Text(if (business) "Type: Business" else "Type: Home") }
        Button(enabled = name.isNotBlank() && !state.busy, onClick = { submit(HostedSpaceCommand("create", name = name.trim(), kind = if (business) "business" else "home")) }) { Text("Create space") }
        Text("Free and Silver include one space. Gold supports multiple spaces.")
        OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Invitation code") }, singleLine = true)
        Button(enabled = code.isNotBlank() && !state.busy, onClick = { submit(HostedSpaceCommand("accept", invitationCode = code.trim())) }) { Text("Accept invitation") }
        TextButton(enabled = code.isNotBlank() && !state.busy, onClick = { submit(HostedSpaceCommand("decline", invitationCode = code.trim())) }) { Text("Decline invitation") }
        if (active != null) {
            Text("Members of ${active.name}", style = MaterialTheme.typography.titleMedium)
            state.data.members.forEach { member ->
                Text("${member.email ?: member.userId} · ${member.role}")
                if (active.role == "owner") {
                    listOf("owner", "admin", "member").filter { it != member.role }.forEach { role ->
                        TextButton(enabled = !state.busy, onClick = { pending = HostedSpaceCommand("role", active.id, role = role, userId = member.userId) }) { Text("Make $role") }
                    }
                }
                if (active.role == "owner" || active.role == "admin" && member.role == "member") {
                    TextButton(enabled = !state.busy, onClick = { pending = HostedSpaceCommand("remove", active.id, userId = member.userId) }) { Text("Remove member") }
                }
            }
            if (active.role in setOf("owner", "admin")) {
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Invite email address") }, singleLine = true)
                Button(enabled = email.isNotBlank() && !state.busy, onClick = { submit(HostedSpaceCommand("invite", active.id, email = email.trim(), role = "member")) }) { Text("Create invitation") }
                state.data.invitationCode?.let { Text("Invitation code: $it") }
                state.data.invitations.forEach { invitation ->
                    Text("${invitation.email} · expires ${invitation.expiresAt}")
                    TextButton(enabled = !state.busy, onClick = { submit(HostedSpaceCommand("revoke_invitation", active.id, invitationId = invitation.id)) }) { Text("Revoke invitation") }
                }
            }
            TextButton(enabled = !state.busy, onClick = { pending = HostedSpaceCommand("leave", active.id) }) { Text("Leave ${active.name}") }
        }
    }
    if (state.gold) LinkedTransfersSection(state, viewModel)
    pending?.let { command ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Confirm membership change") },
            text = { Text("This changes access to the space. Leaving or removing a member stops future sync; copies already saved on devices remain. A space must retain an owner while other members remain.") },
            confirmButton = { TextButton(onClick = { pending = null; submit(command) }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}


@Composable
private fun LinkedTransfersSection(state: finance.shilling.shared.presentation.HostedSpacesUiState, viewModel: HostedSpacesViewModel) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    var link by remember { mutableStateOf(viewModel.newTransferId()) }
    var from by remember { mutableStateOf<finance.shilling.shared.presentation.SpaceAccountChoice?>(null) }
    var to by remember { mutableStateOf<finance.shilling.shared.presentation.SpaceAccountChoice?>(null) }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(viewModel.transferToday) }
    var deleting by remember { mutableStateOf<finance.shilling.shared.data.store.LinkedTransfer?>(null) }
    ShillingCard {
        Text("Linked space transfers", style = MaterialTheme.typography.titleMedium)
        Text("Open one of the spaces first. Sync both spaces to this device before editing a pair. Enter both sides in the same currency; currency conversion is not supported.")
        state.transfers.forEach { transfer ->
            Text("${transfer.debit.title} · ${transfer.debit.amount} · ${transfer.debit.date}")
            TextButton(enabled = !state.busy, onClick = {
                link = transfer.key.linkId
                from = state.accounts.firstOrNull { it.spaceId == transfer.key.fromSpace && it.accountId == transfer.debit.accountId }
                to = state.accounts.firstOrNull { it.spaceId == transfer.key.toSpace && it.accountId == transfer.credit.accountId }
                title = transfer.debit.title.orEmpty(); amount = transfer.debit.amount.toString(); date = transfer.debit.date.toString()
            }) { Text("Edit both sides") }
            TextButton(enabled = !state.busy, onClick = { deleting = transfer }) { Text("Delete both sides") }
        }
        Text("Transfer ID: $link")
        TextButton(onClick = { link = viewModel.newTransferId(); title = ""; amount = "" }) { Text("Start new transfer") }
        TransferAccountPicker("From", state.accounts, from) { from = it }
        TransferAccountPicker("To", state.accounts.filter { it.spaceId != from?.spaceId }, to) { to = it }
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Transfer title") }, singleLine = true)
        OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, singleLine = true)
        OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
        Button(enabled = !state.busy && from != null && to != null && from?.spaceId != to?.spaceId && title.isNotBlank() && amount.isNotBlank(), onClick = {
            val source = from ?: return@Button; val destination = to ?: return@Button
            scope.launch { snackbar.show(viewModel.transferAction(link, source.spaceId, source.accountId, destination.spaceId, destination.accountId, title, amount, date, false)) }
        }) { Text("Save both sides") }
    }
    deleting?.let { transfer -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete linked transfer?") }, text = { Text("Both postings will be deleted. Attached receipts are kept and become unattached.") },
        confirmButton = { TextButton(onClick = {
            deleting = null
            scope.launch { snackbar.show(viewModel.transferAction(transfer.key.linkId, transfer.key.fromSpace, transfer.debit.accountId, transfer.key.toSpace, transfer.credit.accountId, "", "", "", true)) }
        }) { Text("Delete both sides") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun TransferAccountPicker(label: String, accounts: List<finance.shilling.shared.presentation.SpaceAccountChoice>, selected: finance.shilling.shared.presentation.SpaceAccountChoice?, choose: (finance.shilling.shared.presentation.SpaceAccountChoice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: ${selected?.label ?: "Choose account"}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account -> DropdownMenuItem(text = { Text(account.label) }, onClick = { choose(account); expanded = false }) }
        }
    }
}
