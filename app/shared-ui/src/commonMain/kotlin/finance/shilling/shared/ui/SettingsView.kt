package finance.shilling.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import finance.shilling.shared.presentation.DeveloperInfo
import finance.shilling.shared.presentation.SettingsAccount
import finance.shilling.shared.presentation.SettingsUiState
import finance.shilling.shared.presentation.SettingsViewModel
import finance.shilling.shared.presentation.SyncStatusUi
import finance.shilling.shared.presentation.fullLabel
import finance.shilling.shared.presentation.label
import finance.shilling.shared.session.HostedCredentialsMode
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsView(
    developerToolsEnabled: Boolean,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbar = LocalSnackbarController.current
    val showDeveloperTools = developerToolsEnabled || state.developerToolsUnlocked

    ScreenScaffold(title = "Settings") { padding ->
        ReadableColumn(padding = padding, maxWidth = 720.dp) {
            ListSectionHeader("Appearance")
            AppearanceSection(state, viewModel)

            state.account?.let { account ->
                ListSectionHeader("Account")
                AccountSection(account, viewModel)
            }
            state.sync?.let { sync ->
                ListSectionHeader("Sync")
                SyncStatusSection(sync, onRetry = viewModel::retrySync)
            }

            ListSectionHeader("About")
            ShillingCard(
                modifier = Modifier.clickable {
                    if (viewModel.aboutTapped() && !developerToolsEnabled) {
                        snackbar.show("Developer tools enabled")
                    }
                }
            ) {
                Text("Shilling", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Household budgeting that syncs directly between your devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val developer = state.developer
            if (showDeveloperTools && developer != null) {
                ListSectionHeader("Developer tools")
                DeveloperToolsSection(developer, viewModel)
            }
        }
    }
}

@Composable
private fun AppearanceSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val settings = koinInject<Settings>()
    val snackbar = LocalSnackbarController.current
    ShillingCard {
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            viewModel.themeModes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.prefs.themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, viewModel.themeModes.size)
                ) { Text(mode.label) }
            }
        }
        DropdownField(
            label = "Currency symbol",
            selected = state.prefs.currencySymbol,
            options = viewModel.currencyOptions,
            optionLabel = { it.trim() },
            onSelect = { symbol -> symbol?.let(viewModel::setCurrencySymbol) },
            supportingText = state.currencyExample
        )
        DropdownField(
            label = "Week starts on",
            selected = state.prefs.weekStart,
            options = DayOfWeek.entries,
            optionLabel = { it.fullLabel },
            onSelect = { day -> day?.let(viewModel::setWeekStart) },
            supportingText = "Plan and Home show weeks starting on this day."
        )
        Text("Tabs", style = MaterialTheme.typography.labelLarge)
        Text(
            "Press and hold a tab, then drag it to reorder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = {
            resetTabOrder(settings)
            snackbar.show("Tab order reset")
        }) { Text("Reset tab order") }
    }
}

@Composable
private fun AccountSection(account: SettingsAccount, viewModel: SettingsViewModel) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var accountMessage by remember { mutableStateOf<String?>(null) }

    ShillingCard {
        when (account) {
            SettingsAccount.SelfHosted -> {
                Text(SettingsAccount.SelfHosted.TITLE, style = MaterialTheme.typography.titleMedium)
                Text(SettingsAccount.SelfHosted.BODY, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is SettingsAccount.Guest -> {
                Text(account.title, style = MaterialTheme.typography.titleMedium)
                Text(account.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HostedCredentialsForm(
                    authService = null,
                    enabled = account.authAvailable,
                    disabledReason = account.disabledReason,
                    initialMode = HostedCredentialsMode.CREATE_ACCOUNT,
                    guestUpgrade = true,
                    onSubmit = viewModel::submitCredentials,
                    onSendSignInLink = viewModel::sendSignInLink
                )
                account.pendingConfirmation?.let {
                    PendingConfirmation(it)
                    OutlinedButton(onClick = {
                        scope.launch {
                            accountMessage = viewModel.refreshAccountStatus().fold(
                                onSuccess = { "Account status checked." },
                                onFailure = { error -> error.message ?: "Could not check account status." }
                            )
                        }
                    }) { Text("Check confirmation") }
                }
            }
            is SettingsAccount.SignedIn -> {
                Text(account.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    account.planLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                account.pendingConfirmation?.let { PendingConfirmation(it) }
                if (account.needsPasswordSetup) {
                    Text("Email confirmed. Set a password to sign in on another device.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("New password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        scope.launch {
                            accountMessage = viewModel.setPassword(password).fold(
                                onSuccess = { password = ""; "Password set." },
                                onFailure = { error -> error.message ?: "Could not set password." }
                            )
                        }
                    }, enabled = password.isNotBlank()) { Text("Set password") }
                }
            }
        }
        accountMessage?.let { Text(it) }
        OutlinedButton(onClick = { confirming = true }) { Text(account.actionLabel) }
    }

    if (confirming) {
        ConfirmDialog(
            title = account.confirm.title,
            message = account.confirm.message,
            confirmLabel = account.confirm.confirmLabel,
            destructive = account.confirm.destructive,
            onConfirm = { scope.launch { viewModel.confirmAccountAction() } },
            onDismiss = { confirming = false }
        )
    }
}

@Composable
private fun PendingConfirmation(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SyncStatusSection(status: SyncStatusUi, onRetry: () -> Unit) {
    ShillingCard {
        Text(status.title, style = MaterialTheme.typography.titleMedium)
        Text(status.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.showRetry) {
            OutlinedButton(onClick = onRetry, enabled = status.retryEnabled) { Text("Try again") }
        }
    }
}

@Composable
private fun DeveloperToolsSection(developer: DeveloperInfo, viewModel: SettingsViewModel) {
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    var urlText by rememberSaveable { mutableStateOf(developer.serverUrl) }
    var householdText by rememberSaveable(developer.householdId) { mutableStateOf(developer.householdId) }

    ShillingCard {
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        Text(
            developer.connectionSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (developer.selfHosted) {
            TextInputField(
                value = householdText,
                onValueChange = { householdText = it },
                label = "Household ID",
                capitalization = KeyboardCapitalization.None
            )
            OutlinedButton(
                onClick = { snackbar.show(viewModel.saveHouseholdId(householdText)) },
                enabled = householdText.isNotBlank()
            ) { Text("Save household ID") }
        }
        TextInputField(
            value = urlText,
            onValueChange = { urlText = it },
            label = "Server URL",
            capitalization = KeyboardCapitalization.None,
            supportingText = if (developer.selfHosted) null else "Changing the server may switch between managed and self-hosted mode."
        )
        OutlinedButton(
            onClick = { snackbar.show(viewModel.saveServerUrl(urlText)) },
            enabled = urlText.isNotBlank()
        ) { Text("Save and reconnect") }
    }

    if (developer.bootstrapLines.isNotEmpty()) {
        ShillingCard {
            Text("Bootstrap status", style = MaterialTheme.typography.titleMedium)
            developer.bootstrapLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            developer.bootstrapError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = viewModel::retrySync, enabled = !developer.bootstrapChecking) {
                if (developer.bootstrapChecking) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Checking...")
                } else {
                    Text("Retry now")
                }
            }
        }
    }

    ShillingCard {
        Text("Sample data", style = MaterialTheme.typography.titleMedium)
        Text(
            "Adds demo accounts, categories and schedules for testing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = { scope.launch { snackbar.show(viewModel.addSampleData()) } }) { Text("Add sample data") }
    }
}
