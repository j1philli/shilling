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
import finance.shilling.shared.presentation.AccountEditorViewModel
import finance.shilling.shared.presentation.EditorLoad
import org.koin.core.parameter.parametersOf

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
    val viewModel = koinViewModel<AccountEditorViewModel>(key = "account-${accountId ?: "new"}") { parametersOf(accountId) }
    val state by viewModel.state.collectAsState()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    when (state.load) {
        EditorLoad.LOADING -> EditorPlaceholder("Account", navIcon, onClose, loading = true, missingMessage = "")
        EditorLoad.MISSING -> EditorPlaceholder("Account", navIcon, onClose, loading = false, missingMessage = state.missingMessage)
        EditorLoad.READY -> EditorScaffold(
            title = state.title,
            navIcon = navIcon,
            onClose = onClose,
            saveEnabled = state.saveEnabled,
            onSave = {
                scope.launch {
                    viewModel.save()?.let {
                        snackbar.show(it)
                        onSaved()
                    }
                }
            },
            delete = state.deleteConfirm?.let { copy ->
                DeleteConfirmation(
                    title = copy.title,
                    message = copy.message,
                    confirmLabel = copy.confirmLabel,
                    onConfirm = {
                        scope.launch {
                            viewModel.delete()?.let(snackbar::show)
                            onSaved()
                        }
                    }
                )
            }
        ) {
            TextInputField(value = state.name, onValueChange = viewModel::setName, label = "Name", placeholder = "e.g. Checking")
            AmountField(
                value = state.balanceText,
                onValueChange = viewModel::setBalanceText,
                label = state.balanceLabel,
                allowNegative = true,
                supportingText = state.balanceHint
            )
        }
    }
}
