package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.DEFAULT_SERVER_URL
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.HouseholdIdCallback
import finance.shilling.shared.data.ResetOnboardingCallback
import finance.shilling.shared.data.RestartHostedLoginCallback
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ServerUrlCallback
import finance.shilling.shared.data.SETTINGS_KEY_HOSTED_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_LOCAL_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.AuthState
import finance.shilling.shared.data.auth.Feature
import finance.shilling.shared.data.auth.FeatureGate
import finance.shilling.shared.data.auth.HostedBootstrapRetryCallback
import finance.shilling.shared.data.auth.HostedBootstrapState
import finance.shilling.shared.data.auth.HostedBootstrapStatus
import finance.shilling.shared.data.auth.resolveHostedAuthUiState
import finance.shilling.shared.data.auth.UserTier
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@Composable
fun SettingsView(
    selfHosted: Boolean,
    authService: AuthService,
    featureGate: FeatureGate
) {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scheduleRepo = koinInject<ScheduleRepository>()
    val windowUseCase = koinInject<ComputeWindowUseCase>()
    val settings = koinInject<Settings>()
    val serverUrlCallback = koinInject<ServerUrlCallback>()
    val householdIdCallback = koinInject<HouseholdIdCallback>()
    val hostedBootstrapState = koinInject<HostedBootstrapState>()
    val hostedBootstrapRetryCallback = koinInject<HostedBootstrapRetryCallback>()
    val resetOnboardingCallback = koinInject<ResetOnboardingCallback>()
    val restartHostedLoginCallback = koinInject<RestartHostedLoginCallback>()
    val scope = rememberCoroutineScope()

    val authState by authService.authState.collectAsState()
    val hostedBootstrapStatus by hostedBootstrapState.status.collectAsState()
    val hostedMode = !selfHosted
    val householdId = remember(authState.userId, hostedMode, hostedBootstrapStatus.phase) {
        when {
            hostedMode -> settings.getStringOrNull(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID)
                ?: settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID)
            else -> settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID)
        } ?: "unknown"
    }

    var urlText by remember { mutableStateOf(settings.getStringOrNull(SETTINGS_KEY_SERVER_URL) ?: DEFAULT_SERVER_URL) }
    var serverSaved by remember { mutableStateOf(false) }
    var householdText by remember(householdId) { mutableStateOf(householdId) }
    var householdSaved by remember { mutableStateOf(false) }
    var showDeveloperTools by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(
            "Settings",
            if (selfHosted) {
                "Manage your sync connection and developer tools."
            } else {
                "Manage your account, plan placeholders, and developer tools."
            }
        )

        if (selfHosted) {
            SelfHostedAccountSection(onResetOnboarding = resetOnboardingCallback.onReset)
        } else {
            HostedBootstrapStatusCard(
                status = hostedBootstrapStatus,
                onRetry = hostedBootstrapRetryCallback.onRetry,
                title = "Connection status"
            )
            ShillingDivider()
            HostedAccountSection(
                authState = authState,
                authService = authService,
                householdId = householdId,
                bootstrapStatus = hostedBootstrapStatus,
                onResetOnboarding = resetOnboardingCallback.onReset,
                onRestartHostedLogin = restartHostedLoginCallback.onRestart
            )
            ShillingDivider()
            HostedFeaturePlaceholderSection(featureGate = featureGate)
        }

        ShillingDivider()

        AddSectionToggle(
            label = "Developer tools",
            expanded = showDeveloperTools,
            onToggle = { showDeveloperTools = !showDeveloperTools }
        )

        if (showDeveloperTools) {
            ShillingCard {
                Text("Connection", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (selfHosted) {
                        "Self-hosted mode keeps households local to the server you operate."
                    } else {
                        "Managed mode gets the household namespace from the server. Changing the server URL may switch modes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Runtime mode: ${if (selfHosted) "Self-hosted" else "Managed"}", style = MaterialTheme.typography.bodySmall)
                Text("Device ID: ${authState.deviceId}", style = MaterialTheme.typography.bodySmall)
                if (!selfHosted) {
                    Text("User ID: ${authState.userId ?: "not signed in"}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Household ID: $householdId", style = MaterialTheme.typography.bodySmall)
                if (!selfHosted) {
                    Text(
                        "Managed sync only connects devices that are signed into the same hosted account and household.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (selfHosted) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = householdText,
                        onValueChange = { householdText = it; householdSaved = false },
                        label = { Text("Household ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val newId = householdText.trim()
                            if (newId.isNotBlank()) {
                                settings.putString(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID, newId)
                                householdIdCallback.onChange(newId)
                                householdSaved = true
                            }
                        }
                    ) {
                        Text("Save household")
                    }
                    if (householdSaved) {
                        Text("Household ID updated. Sync will reconnect.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                ShillingDivider()

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it; serverSaved = false },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val trimmed = urlText.trim()
                        settings.putString(SETTINGS_KEY_SERVER_URL, trimmed)
                        serverUrlCallback.onChange(trimmed)
                        serverSaved = true
                    },
                    enabled = urlText.trim().isNotEmpty()
                ) {
                    Text("Save & Reconnect")
                }
                if (serverSaved) {
                    Text("Reconnected to ${urlText.trim()}", style = MaterialTheme.typography.bodySmall)
                }
            }

            ShillingCard {
                Text("Dev utilities", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Use these tools for local testing. They are intentionally outside the standard product flow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        scope.launch {
                            seedDemoData(accountRepo, categoryRepo, scheduleRepo, windowUseCase)
                        }
                    }
                ) {
                    Text("Seed sample data")
                }
            }
        }
    }
}

@Composable
private fun SelfHostedAccountSection(
    onResetOnboarding: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()

    ShillingCard {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Text(
            "Self-hosted mode does not use a managed Shilling account.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = {
                scope.launch {
                    onResetOnboarding()
                }
            }
        ) {
            Text("Log out & restart")
        }
        Text(
            "This clears local data on this device and returns to onboarding.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HostedAccountSection(
    authState: AuthState,
    authService: AuthService,
    householdId: String,
    bootstrapStatus: HostedBootstrapStatus,
    onResetOnboarding: suspend () -> Unit,
    onRestartHostedLogin: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    val authUiState = remember(authService, bootstrapStatus) {
        resolveHostedAuthUiState(authService, bootstrapStatus)
    }

    ShillingCard {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Text("Plan: ${tierLabel(authState.tier)}", style = MaterialTheme.typography.bodySmall)
        if (authState.pendingEmailConfirmation) {
            Text(
                "Email confirmation is still pending for ${authState.pendingEmail ?: authState.email ?: "this account"}. Check your inbox to finish the upgrade.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (authState.isAnonymous) {
            Text(
                "You are using a guest account. Creating an account upgrades this session and keeps its household.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HostedCredentialsForm(
                authService = authService,
                enabled = authUiState.authAvailable,
                disabledReason = authUiState.disabledReason?.let {
                    "$it Current phase: ${bootstrapStatus.phase.name}."
                },
                initialMode = HostedCredentialsMode.CREATE_ACCOUNT,
                onSubmit = { mode, email, password ->
                    when (mode) {
                        HostedCredentialsMode.SIGN_IN -> {
                            authService.signIn(email, password).map {
                                HostedCredentialsSubmitResult(mode = mode)
                            }
                        }
                        HostedCredentialsMode.CREATE_ACCOUNT -> {
                            authService.signUp(email, password).map { signUp ->
                                HostedCredentialsSubmitResult(mode = mode, signUpResult = signUp)
                            }
                        }
                    }
                }
            )
        } else {
            Text("Signed in as ${authState.email ?: "unknown"}")
            Text("User ID: ${authState.userId ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
            Text("Household ID: $householdId", style = MaterialTheme.typography.bodySmall)
            Text(
                "Managed sync requires the same hosted account and household on every device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    if (authState.isAnonymous) {
                        onResetOnboarding()
                    } else {
                        onRestartHostedLogin()
                    }
                }
            }
        ) {
            Text(
                if (authState.isAnonymous) {
                    "Restart onboarding"
                } else {
                    "Log out & return to Welcome"
                }
            )
        }
    }
}

@Composable
private fun HostedFeaturePlaceholderSection(featureGate: FeatureGate) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Account roadmap", style = MaterialTheme.typography.titleMedium)
        FeaturePlaceholderCard(
            title = "Feedback inbox",
            description = "An in-app feedback surface for filing issues and product requests.",
            requiredTier = UserTier.FREE,
            enabled = featureGate.isEnabled(Feature.FEEDBACK)
        )
        FeaturePlaceholderCard(
            title = "Managed backup",
            description = "Server-backed backup controls and status for your synced household.",
            requiredTier = UserTier.PAID,
            enabled = featureGate.isEnabled(Feature.SERVER_BACKUP)
        )
        FeaturePlaceholderCard(
            title = "AI assistance",
            description = "AI budgeting help and workflow accelerators.",
            requiredTier = UserTier.PAID,
            enabled = featureGate.isEnabled(Feature.AI_FEATURES)
        )
    }
}

@Composable
private fun FeaturePlaceholderCard(
    title: String,
    description: String,
    requiredTier: UserTier,
    enabled: Boolean
) {
    ShillingCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (enabled) {
                "Included in your current plan. The product surface is still coming soon."
            } else {
                "Locked until ${tierLabel(requiredTier)}. Billing and upgrade flows are still coming soon."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun tierLabel(tier: UserTier): String = when (tier) {
    UserTier.ANONYMOUS -> "Guest"
    UserTier.FREE -> "Free"
    UserTier.PAID -> "Paid"
}

suspend fun seedDemoData(
    accountRepo: AccountRepository,
    categoryRepo: CategoryRepository,
    scheduleRepo: ScheduleRepository,
    windowUseCase: ComputeWindowUseCase
) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val account = Account(id = "checking", name = "Checking", balance = 1850.0)
    val savings = Account(id = "savings", name = "Savings", balance = 3200.0)
    accountRepo.upsert(account)
    accountRepo.upsert(savings)
    val housing = Category(id = "cat-housing", name = "Housing", color = "#EF5350")
    val groceries = Category(id = "cat-groceries", name = "Groceries", color = "#8BC34A")
    val subscriptions = Category(id = "cat-subscriptions", name = "Subscriptions", color = "#FF9800")
    val income = Category(id = "cat-income", name = "Income", color = "#4CAF50")
    val transfers = Category(id = "cat-transfers", name = "Transfers", color = "#9C27B0")
    listOf(housing, groceries, subscriptions, income, transfers).forEach { categoryRepo.upsert(it) }
    val startOfMonth = LocalDate(now.year, now.monthNumber, 1)
    val fridayWindow = windowUseCase.computeFridayWindow(now)
    scheduleRepo.upsert(
        Schedule(
            id = "rent",
            title = "Rent",
            amount = 1450.0,
            type = ScheduleType.EXPENSE,
            accountId = account.id,
            categoryId = housing.id,
            startDate = startOfMonth,
            freq = Frequency.MONTHLY_BY_DAY,
            byMonthDay = 1
        )
    )
    scheduleRepo.upsert(
        Schedule(
            id = "groceries",
            title = "Groceries",
            amount = 180.0,
            type = ScheduleType.EXPENSE,
            accountId = account.id,
            categoryId = groceries.id,
            startDate = fridayWindow.first,
            freq = Frequency.WEEKLY,
            byDayMask = setOf(DayOfWeek.SATURDAY).toDayMask()
        )
    )
    scheduleRepo.upsert(
        Schedule(
            id = "subscription",
            title = "Streaming",
            amount = 19.99,
            type = ScheduleType.EXPENSE,
            accountId = account.id,
            categoryId = subscriptions.id,
            startDate = fridayWindow.second.minus(2, DateTimeUnit.DAY),
            freq = Frequency.MONTHLY_BY_DAY,
            byMonthDay = fridayWindow.second.minus(2, DateTimeUnit.DAY).dayOfMonth
        )
    )
    scheduleRepo.upsert(
        Schedule(
            id = "paycheck",
            title = "Paycheck",
            amount = 2100.0,
            type = ScheduleType.INCOME,
            accountId = account.id,
            categoryId = income.id,
            startDate = fridayWindow.first.minus(7, DateTimeUnit.DAY),
            freq = Frequency.WEEKLY,
            byDayMask = setOf(DayOfWeek.FRIDAY).toDayMask()
        )
    )
    scheduleRepo.upsert(
        Schedule(
            id = "auto-save",
            title = "Auto-save",
            amount = 250.0,
            type = ScheduleType.TRANSFER,
            accountId = account.id,
            counterAccountId = savings.id,
            categoryId = transfers.id,
            startDate = startOfMonth,
            freq = Frequency.MONTHLY_BY_DAY,
            byMonthDay = 15
        )
    )
}
