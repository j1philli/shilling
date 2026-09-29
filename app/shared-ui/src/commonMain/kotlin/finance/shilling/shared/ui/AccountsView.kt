package finance.shilling.shared.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.store.AccountRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import finance.shilling.shared.presentation.formatAmountInput
import finance.shilling.shared.presentation.formatCurrency
import finance.shilling.shared.presentation.parseAmountInput
import finance.shilling.shared.presentation.AccountsViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AccountsView(
    onOpenAccount: (String?) -> Unit,
    title: String = "Accounts",
    headerBottom: (@Composable () -> Unit)? = null,
    viewModel: AccountsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    ListDetailLayout(
        selectedKey = selectedKey,
        onDismissDetail = { selectedKey = null },
        list = { twoPane ->
            val open: (String?) -> Unit = { id ->
                if (twoPane) selectedKey = id ?: NEW_ITEM_KEY else onOpenAccount(id)
            }
            ScreenScaffold(
                title = title,
                headerBottom = headerBottom,
                subtitle = state.subtitle,
                actions = { AddButton("Add", onClick = { open(null) }) }
            ) { padding ->
                val empty = state.empty
                if (empty != null) {
                    EmptyState(
                        title = empty.title,
                        message = empty.message,
                        actionLabel = empty.actionLabel,
                        onAction = { open(null) }
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                        items(state.rows, key = { it.id }) { account ->
                            EntityListItem(
                                title = account.name,
                                trailing = {
                                    Text(account.balance, style = MaterialTheme.typography.bodyLarge)
                                },
                                selected = twoPane && selectedKey == account.id,
                                onClick = { open(account.id) }
                            )
                        }
                    }
                }
            }
        },
        detail = { key ->
            AccountEditor(
                accountId = key.takeUnless { it == NEW_ITEM_KEY },
                navIcon = ScreenNavIcon.CLOSE,
                onClose = { selectedKey = null },
                onSaved = { selectedKey = null }
            )
        },
        emptyDetail = { EmptyState(title = "No account selected", message = "Choose an account to edit it.") }
    )
}

@Composable
fun AccountEditor(
    accountId: String?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val accountRepo = koinInject<AccountRepository>()
    if (accountId == null) {
        AccountForm(existing = null, navIcon = navIcon, onClose = onClose, onSaved = onSaved)
        return
    }
    val loadable = rememberLoadable(accountId) {
        accountRepo.watchAll().map { list -> list.firstOrNull { it.id == accountId } }
    }
    when (loadable) {
        Loadable.Loading -> EditorPlaceholder("Account", navIcon, onClose, loading = true, missingMessage = "")
        is Loadable.Ready -> loadable.value?.let { account ->
            AccountForm(existing = account, navIcon = navIcon, onClose = onClose, onSaved = onSaved)
        } ?: EditorPlaceholder("Account", navIcon, onClose, loading = false, missingMessage = "This account was deleted.")
    }
}

@Composable
private fun AccountForm(
    existing: Account?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val accountRepo = koinInject<AccountRepository>()
    val idGen = koinInject<IdGenerator>()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var balanceText by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.let { formatAmountInput(it.balance).let { v -> if (it.balance < 0) "-$v" else v } }.orEmpty())
    }
    val balance = if (balanceText.isBlank()) 0.0 else parseAmountInput(balanceText)

    EditorScaffold(
        title = existing?.name ?: "New account",
        navIcon = navIcon,
        onClose = onClose,
        saveEnabled = name.isNotBlank() && balance != null,
        onSave = {
            scope.launch {
                accountRepo.upsert(Account(id = existing?.id ?: idGen.newId(), name = name.trim(), balance = balance!!))
                snackbar.show(if (existing == null) "Account added" else "Account updated")
                onSaved()
            }
        },
        delete = existing?.let { account ->
            DeleteConfirmation(
                title = "Delete ${account.name}?",
                message = "All transactions recorded to this account will be deleted, and schedules that use it " +
                    "will need a new account. This can't be undone.",
                confirmLabel = "Delete account",
                onConfirm = {
                    scope.launch {
                        accountRepo.delete(account.id)
                        snackbar.show("${account.name} deleted")
                        onSaved()
                    }
                }
            )
        }
    ) {
        TextInputField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "e.g. Checking")
        AmountField(
            value = balanceText,
            onValueChange = { balanceText = it },
            label = if (existing == null) "Starting balance" else "Current balance",
            allowNegative = true,
            supportingText = "Balances are entered manually and aren't changed by recorded transactions."
        )
    }
}
