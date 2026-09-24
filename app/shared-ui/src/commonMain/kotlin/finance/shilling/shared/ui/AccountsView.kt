package finance.shilling.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Add
import com.composables.icons.materialicons.filled.Arrow_back
import com.composables.icons.materialicons.filled.Delete
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.store.AccountRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val NEW_ACCOUNT_KEY = "__new__"

@Composable
fun AccountsView() {
    val accountRepo = koinInject<AccountRepository>()
    val scope = rememberCoroutineScope()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    var selectedKey by remember { mutableStateOf<String?>(null) }

    @Suppress("DEPRECATION")
    val isCompact = currentWindowAdaptiveInfo()
        .windowSizeClass
        .windowWidthSizeClass == WindowWidthSizeClass.COMPACT

    val selectedAccount = accounts.firstOrNull { it.id == selectedKey }
    val isCreating = selectedKey == NEW_ACCOUNT_KEY

    LaunchedEffect(accounts, selectedKey) {
        if (selectedKey != null && selectedKey != NEW_ACCOUNT_KEY && selectedAccount == null) {
            selectedKey = null
        }
    }

    if (isCompact) {
        if (selectedKey == null) {
            AccountsListPane(
                accounts = accounts,
                selectedAccountId = null,
                onSelectAccount = { selectedKey = it.id },
                onAddAccount = { selectedKey = NEW_ACCOUNT_KEY }
            )
        } else {
            AccountsDetailHost(
                title = when {
                    isCreating -> "New account"
                    selectedAccount != null -> selectedAccount.name
                    else -> "Account"
                },
                showBack = true,
                onBack = { selectedKey = null },
                onDelete = selectedAccount?.let { account ->
                    {
                        scope.launch {
                            accountRepo.delete(account.id)
                            selectedKey = null
                        }
                    }
                }
            ) {
                if (isCreating || selectedAccount != null) {
                    AddAccountForm(
                        editingAccount = selectedAccount,
                        onSave = { account ->
                            scope.launch {
                                accountRepo.upsert(account)
                                selectedKey = account.id
                            }
                        },
                        onCancel = { selectedKey = null }
                    )
                }
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 360.dp)
                    .weight(0.38f)
                    .fillMaxHeight()
            ) {
                AccountsListPane(
                    accounts = accounts,
                    selectedAccountId = selectedAccount?.id,
                    onSelectAccount = { selectedKey = it.id },
                    onAddAccount = { selectedKey = NEW_ACCOUNT_KEY }
                )
            }
            VerticalDivider()
            Column(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
            ) {
                when {
                    isCreating || selectedAccount != null -> {
                        AccountsDetailHost(
                            title = if (isCreating) "New account" else selectedAccount!!.name,
                            showBack = false,
                            onBack = {},
                            onDelete = selectedAccount?.let { account ->
                                {
                                    scope.launch {
                                        accountRepo.delete(account.id)
                                        selectedKey = null
                                    }
                                }
                            }
                        ) {
                            AddAccountForm(
                                editingAccount = selectedAccount,
                                onSave = { account ->
                                    scope.launch {
                                        accountRepo.upsert(account)
                                        selectedKey = account.id
                                    }
                                },
                                onCancel = { selectedKey = null }
                            )
                        }
                    }
                    else -> AccountsEmptyDetail()
                }
            }
        }
    }
}

@Composable
private fun AccountsListPane(
    accounts: List<Account>,
    selectedAccountId: String?,
    onSelectAccount: (Account) -> Unit,
    onAddAccount: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scroll)
    ) {
        SectionHeader("Accounts", "Manage your balances and fund your scheduled items.")
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onAddAccount) {
            Icon(MaterialIcons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add account")
        }
        Spacer(modifier = Modifier.height(16.dp))
        ShillingDivider()
        Spacer(modifier = Modifier.height(12.dp))
        if (accounts.isEmpty()) {
            Text(
                "No accounts yet. Add one to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            accounts.forEach { account ->
                val selected = account.id == selectedAccountId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSelectAccount(account) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    tonalElevation = if (selected) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.name, fontWeight = FontWeight.Medium)
                            Text(
                                formatCurrency(account.balance),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountsDetailHost(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onDelete: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scroll)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showBack) {
                TooltipIconButton(onClick = onBack, tooltip = "Back") {
                    Icon(MaterialIcons.Filled.Arrow_back, contentDescription = "Back")
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (onDelete != null) {
                TooltipIconButton(onClick = onDelete, tooltip = "Delete account") {
                    Icon(
                        MaterialIcons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        ShillingCard {
            content()
        }
    }
}

@Composable
private fun AccountsEmptyDetail() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Select an account", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Choose one from the list, or add a new account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AddAccountForm(
    editingAccount: Account? = null,
    onSave: (Account) -> Unit,
    onCancel: () -> Unit
) {
    val idGen = koinInject<IdGenerator>()
    var name by remember { mutableStateOf(editingAccount?.name ?: "") }
    var balanceText by remember { mutableStateOf(editingAccount?.balance?.toString() ?: "0") }
    val balance = balanceText.toDoubleOrNull()
    val enableSave = name.isNotBlank() && balance != null
    val isEditing = editingAccount != null

    LaunchedEffect(editingAccount?.id) {
        editingAccount?.let { account ->
            name = account.name
            balanceText = account.balance.toString()
        } ?: run {
            name = ""
            balanceText = "0"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (isEditing) "Edit account" else "Add account", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            singleLine = true,
            maxLines = 1
        )
        OutlinedTextField(
            value = balanceText,
            onValueChange = { balanceText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (isEditing) "Balance" else "Starting balance") },
            singleLine = true,
            maxLines = 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (enableSave) {
                        val id = editingAccount?.id ?: idGen.newId()
                        onSave(Account(id = id, name = name.trim(), balance = balance!!))
                        if (!isEditing) {
                            name = ""
                            balanceText = "0"
                        }
                    }
                },
                enabled = enableSave
            ) { Text(if (isEditing) "Save changes" else "Save account") }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}
