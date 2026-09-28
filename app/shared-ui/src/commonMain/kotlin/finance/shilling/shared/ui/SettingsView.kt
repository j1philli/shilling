package finance.shilling.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.DEFAULT_SERVER_URL
import finance.shilling.shared.data.HouseholdIdCallback
import finance.shilling.shared.data.ResetOnboardingCallback
import finance.shilling.shared.data.RestartHostedLoginCallback
import finance.shilling.shared.data.SETTINGS_KEY_HOSTED_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_LOCAL_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.ServerUrlCallback
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.AuthState
import finance.shilling.shared.data.auth.FeatureGate
import finance.shilling.shared.data.auth.HostedBootstrapPhase
import finance.shilling.shared.data.auth.HostedBootstrapRetryCallback
import finance.shilling.shared.data.auth.HostedBootstrapState
import finance.shilling.shared.data.auth.HostedBootstrapStatus
import finance.shilling.shared.data.auth.UserTier
import finance.shilling.shared.data.auth.resolveHostedAuthUiState
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import org.koin.compose.koinInject

private const val DEVELOPER_UNLOCK_TAPS = 7

@Composable
fun SettingsView(
    selfHosted: Boolean,
    authService: AuthService,
    @Suppress("UNUSED_PARAMETER") featureGate: FeatureGate,
    developerToolsEnabled: Boolean
) {
    val settings = koinInject<Settings>()
    val hostedBootstrapState = koinInject<HostedBootstrapState>()
    val hostedBootstrapRetryCallback = koinInject<HostedBootstrapRetryCallback>()
    val resetOnboardingCallback = koinInject<ResetOnboardingCallback>()
    val restartHostedLoginCallback = koinInject<RestartHostedLoginCallback>()
    val snackbar = LocalSnackbarController.current

    val authState by authService.authState.collectAsState()
    val bootstrapStatus by hostedBootstrapState.status.collectAsState()
    val householdId = remember(authState.userId, selfHosted, bootstrapStatus.phase) {
        when {
            !selfHosted -> settings.getStringOrNull(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID)
                ?: settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID)
            else -> settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID)
        } ?: "unknown"
    }
    var aboutTaps by rememberSaveable { mutableIntStateOf(0) }
    val showDeveloperTools = developerToolsEnabled || aboutTaps >= DEVELOPER_UNLOCK_TAPS

    ScreenScaffold(title = "Settings") { padding ->
        ReadableColumn(padding = padding, maxWidth = 720.dp) {
            ListSectionHeader("Appearance")
            AppearanceSection()

            ListSectionHeader("Account")
            if (selfHosted) {
                SelfHostedAccountSection(onResetOnboarding = resetOnboardingCallback.onReset)
            } else {
                HostedAccountSection(
                    authState = authState,
                    authService = authService,
                    bootstrapStatus = bootstrapStatus,
                    onResetOnboarding = resetOnboardingCallback.onReset,
                    onRestartHostedLogin = restartHostedLoginCallback.onRestart
                )
                ListSectionHeader("Sync")
                SyncStatusSection(status = bootstrapStatus, onRetry = hostedBootstrapRetryCallback.onRetry)
            }

            ListSectionHeader("About")
            ShillingCard(
                modifier = Modifier.clickable {
                    if (aboutTaps < DEVELOPER_UNLOCK_TAPS) {
                        aboutTaps += 1
                        if (aboutTaps == DEVELOPER_UNLOCK_TAPS && !developerToolsEnabled) {
                            snackbar.show("Developer tools enabled")
                        }
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

            if (showDeveloperTools) {
                ListSectionHeader("Developer tools")
                DeveloperToolsSection(
                    selfHosted = selfHosted,
                    authState = authState,
                    householdId = householdId,
                    bootstrapStatus = bootstrapStatus,
                    onRetryBootstrap = hostedBootstrapRetryCallback.onRetry
                )
            }
        }
    }
}

@Composable
private fun AppearanceSection() {
    val settings = koinInject<Settings>()
    val snackbar = LocalSnackbarController.current
    ShillingCard {
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = DisplayPreferences.themeMode == mode,
                    onClick = { DisplayPreferences.updateThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                ) { Text(mode.label) }
            }
        }
        DropdownField(
            label = "Currency symbol",
            selected = DisplayPreferences.currencySymbol,
            options = DisplayPreferences.currencyOptions,
            optionLabel = { it.trim() },
            onSelect = { symbol -> symbol?.let(DisplayPreferences::updateCurrencySymbol) },
            supportingText = "Example: ${formatCurrency(1234.5)}"
        )
        DropdownField(
            label = "Week starts on",
            selected = DisplayPreferences.weekStart,
            options = DayOfWeek.entries,
            optionLabel = { it.fullLabel },
            onSelect = { day -> day?.let(DisplayPreferences::updateWeekStart) },
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
private fun SelfHostedAccountSection(onResetOnboarding: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }

    ShillingCard {
        Text("Self-hosted", style = MaterialTheme.typography.titleMedium)
        Text(
            "This device syncs through a server you operate. No Shilling account is used.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { confirmReset = true }) { Text("Sign out and reset") }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Sign out and reset?",
            message = "This deletes all budget data on this device and returns to setup. " +
                "Data already synced to your other devices is not affected.",
            confirmLabel = "Reset device",
            onConfirm = { scope.launch { onResetOnboarding() } },
            onDismiss = { confirmReset = false }
        )
    }
}

@Composable
private fun HostedAccountSection(
    authState: AuthState,
    authService: AuthService,
    bootstrapStatus: HostedBootstrapStatus,
    onResetOnboarding: suspend () -> Unit,
    onRestartHostedLogin: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    val authUiState = remember(authService, bootstrapStatus) {
        resolveHostedAuthUiState(authService, bootstrapStatus)
    }
    var confirmSignOut by remember { mutableStateOf(false) }

    ShillingCard {
        if (authState.isAnonymous) {
            Text("Guest", style = MaterialTheme.typography.titleMedium)
            Text(
                "Create an account to keep this budget and use it on your other devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HostedCredentialsForm(
                authService = authService,
                enabled = authUiState.authAvailable,
                disabledReason = authUiState.disabledReason,
                initialMode = HostedCredentialsMode.CREATE_ACCOUNT,
                onSubmit = { mode, email, password ->
                    when (mode) {
                        HostedCredentialsMode.SIGN_IN -> authService.signIn(email, password).map {
                            HostedCredentialsSubmitResult(mode = mode)
                        }
                        HostedCredentialsMode.CREATE_ACCOUNT -> authService.signUp(email, password).map { signUp ->
                            HostedCredentialsSubmitResult(mode = mode, signUpResult = signUp)
                        }
                    }
                }
            )
        } else {
            Text(authState.email ?: "Signed in", style = MaterialTheme.typography.titleMedium)
            Text(
                "${tierLabel(authState.tier)} plan",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (authState.pendingEmailConfirmation) {
            Text(
                "Check ${authState.pendingEmail ?: authState.email ?: "your inbox"} to confirm your email address.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = { confirmSignOut = true }) {
            Text(if (authState.isAnonymous) "Start over" else "Sign out")
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = if (authState.isAnonymous) "Start over?" else "Sign out?",
            message = if (authState.isAnonymous) {
                "Guest data on this device will be deleted. Create an account first if you want to keep it."
            } else {
                "You'll return to the welcome screen. Your budget stays in your account."
            },
            confirmLabel = if (authState.isAnonymous) "Delete and start over" else "Sign out",
            destructive = authState.isAnonymous,
            onConfirm = {
                scope.launch {
                    if (authState.isAnonymous) onResetOnboarding() else onRestartHostedLogin()
                }
            },
            onDismiss = { confirmSignOut = false }
        )
    }
}

@Composable
private fun SyncStatusSection(status: HostedBootstrapStatus, onRetry: () -> Unit) {
    val (title, detail) = when {
        status.syncReady -> "Connected" to "Changes sync directly to your other signed-in devices."
        status.isChecking -> "Connecting…" to "Checking the connection."
        status.phase == HostedBootstrapPhase.LOGIN_REQUIRED -> "Sign-in required" to "Sign in again to resume syncing."
        status.phase == HostedBootstrapPhase.LOCAL_ONLY -> "This device only" to "Create an account to sync with other devices."
        else -> "Offline" to "Changes are saved on this device and will sync when the connection returns."
    }
    ShillingCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!status.syncReady && status.phase != HostedBootstrapPhase.LOCAL_ONLY) {
            OutlinedButton(onClick = onRetry, enabled = !status.isChecking) { Text("Try again") }
        }
    }
}

@Composable
private fun DeveloperToolsSection(
    selfHosted: Boolean,
    authState: AuthState,
    householdId: String,
    bootstrapStatus: HostedBootstrapStatus,
    onRetryBootstrap: () -> Unit
) {
    val settings = koinInject<Settings>()
    val serverUrlCallback = koinInject<ServerUrlCallback>()
    val householdIdCallback = koinInject<HouseholdIdCallback>()
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()

    var urlText by rememberSaveable { mutableStateOf(settings.getStringOrNull(SETTINGS_KEY_SERVER_URL) ?: DEFAULT_SERVER_URL) }
    var householdText by rememberSaveable(householdId) { mutableStateOf(householdId) }

    ShillingCard {
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        Text(
            buildString {
                appendLine("Mode: ${if (selfHosted) "Self-hosted" else "Managed"}")
                appendLine("Device ID: ${authState.deviceId}")
                if (!selfHosted) appendLine("User ID: ${authState.userId ?: "not signed in"}")
                append("Household ID: $householdId")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selfHosted) {
            TextInputField(
                value = householdText,
                onValueChange = { householdText = it },
                label = "Household ID",
                capitalization = KeyboardCapitalization.None
            )
            OutlinedButton(
                onClick = {
                    val newId = householdText.trim()
                    settings.putString(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID, newId)
                    householdIdCallback.onChange(newId)
                    snackbar.show("Household ID updated. Sync will reconnect.")
                },
                enabled = householdText.isNotBlank()
            ) { Text("Save household ID") }
        }
        TextInputField(
            value = urlText,
            onValueChange = { urlText = it },
            label = "Server URL",
            capitalization = KeyboardCapitalization.None,
            supportingText = if (selfHosted) null else "Changing the server may switch between managed and self-hosted mode."
        )
        OutlinedButton(
            onClick = {
                val trimmed = urlText.trim()
                settings.putString(SETTINGS_KEY_SERVER_URL, trimmed)
                serverUrlCallback.onChange(trimmed)
                snackbar.show("Reconnecting to $trimmed")
            },
            enabled = urlText.isNotBlank()
        ) { Text("Save and reconnect") }
    }

    if (!selfHosted) {
        HostedBootstrapStatusCard(status = bootstrapStatus, onRetry = onRetryBootstrap, title = "Bootstrap status")
    }

    ShillingCard {
        Text("Sample data", style = MaterialTheme.typography.titleMedium)
        Text(
            "Adds demo accounts, categories and schedules for testing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = {
            scope.launch {
                seedDemoData(accountRepo, categoryRepo, scheduleRepo, windowUseCase)
                snackbar.show("Sample data added")
            }
        }) { Text("Add sample data") }
    }
}

private fun tierLabel(tier: UserTier): String = when (tier) {
    UserTier.ANONYMOUS -> "Guest"
    UserTier.FREE -> "Free"
    UserTier.PAID -> "Paid"
}
