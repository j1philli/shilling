package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Chevron_right
import finance.shilling.shared.data.DEFAULT_SELF_HOSTED_SERVER_URL
import finance.shilling.shared.data.auth.AuthService
import kotlinx.coroutines.launch

private enum class FirstLaunchRoute {
    LANDING,
    LOGIN,
    SELF_HOSTED
}

private enum class PendingDestructiveAction {
    GET_STARTED,
    NON_MATCHING_AUTH
}

@Composable
fun FirstLaunchOnboardingView(
    initialSelfHostedUrl: String = DEFAULT_SELF_HOSTED_SERVER_URL,
    selfHostedOnly: Boolean = false,
    hasHeldLocalData: Boolean = false,
    welcomeNotice: String? = null,
    authService: AuthService? = null,
    authReady: Boolean = authService != null,
    authDisabledReason: String? = null,
    onGetStarted: suspend (wipeHeldData: Boolean) -> Unit,
    onAuthenticated: suspend (wipeHeldData: Boolean) -> Unit,
    onSubmitCredentials: suspend (
        mode: HostedCredentialsMode,
        email: String,
        password: String
    ) -> Result<HostedCredentialsSubmitResult>,
    onContinueSelfHosted: suspend (String) -> Result<Unit>,
    onCancelDestructiveAuth: suspend () -> Unit = {},
    topPadding: Dp = 0.dp
) {
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(if (selfHostedOnly) FirstLaunchRoute.SELF_HOSTED else FirstLaunchRoute.LANDING) }
    var selfHostedUrl by remember { mutableStateOf(initialSelfHostedUrl) }
    var selfHostedError by remember { mutableStateOf<String?>(null) }
    var isValidatingSelfHosted by remember { mutableStateOf(false) }
    var pendingDestructive by remember { mutableStateOf<PendingDestructiveAction?>(null) }
    val trimmedSelfHostedUrl = selfHostedUrl.trim()

    Surface(modifier = Modifier.fillMaxSize()) {
        when (route) {
            FirstLaunchRoute.LANDING -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .navigationBarsPadding()
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = 720.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        AppLogo()
                        Text("Welcome to Shilling", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Get started quickly, sign in to an existing account, or connect to your own server.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 540.dp)
                        )

                        if (hasHeldLocalData) {
                            HeldLocalDataCard(
                                notice = welcomeNotice,
                                onRelogin = { route = FirstLaunchRoute.LOGIN }
                            )
                        }

                        OnboardingActionCard(
                            title = if (hasHeldLocalData) "Start over" else "Get Started",
                            body = if (hasHeldLocalData) {
                                "Begin fresh as a guest. This replaces the budget saved on this device."
                            } else {
                                "Create a guest account and jump straight into the app."
                            },
                            onClick = {
                                if (hasHeldLocalData) {
                                    pendingDestructive = PendingDestructiveAction.GET_STARTED
                                } else {
                                    scope.launch { onGetStarted(false) }
                                }
                            }
                        )
                        OnboardingActionCard(
                            title = "Sign in",
                            body = if (hasHeldLocalData) {
                                "Use the same account to keep your current budget."
                            } else {
                                "Sign in to an existing account or create one with email and password."
                            },
                            onClick = { route = FirstLaunchRoute.LOGIN }
                        )
                    }

                    OutlinedButton(
                        onClick = { route = FirstLaunchRoute.SELF_HOSTED },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        Text(
                            text = "Self-hosted",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            FirstLaunchRoute.LOGIN -> {
                BackNavigationScaffold(
                    onBack = { route = FirstLaunchRoute.LANDING },
                    topPadding = topPadding
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            SectionHeader(
                                "Sign in",
                                if (hasHeldLocalData) {
                                    "Use the same account to keep the budget saved on this device."
                                } else {
                                    "Sign in or create an account to continue."
                                }
                            )
                            ShillingCard {
                                HostedCredentialsForm(
                                    authService = authService,
                                    enabled = authReady,
                                    disabledReason = authDisabledReason,
                                    signInLabel = "Sign in",
                                    onSubmit = { mode, email, password ->
                                        val result = onSubmitCredentials(mode, email, password)
                                        result.fold(
                                            onSuccess = {
                                                onAuthenticated(false)
                                                Result.success(it)
                                            },
                                            onFailure = { error ->
                                                if (error is NonMatchingAccountException) {
                                                    pendingDestructive =
                                                        PendingDestructiveAction.NON_MATCHING_AUTH
                                                }
                                                Result.failure(error)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            FirstLaunchRoute.SELF_HOSTED -> {
                BackNavigationScaffold(
                    onBack = { route = FirstLaunchRoute.LANDING },
                    showBackButton = !selfHostedOnly,
                    topPadding = topPadding
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ShillingCard {
                                Text(
                                    "Connect to a server you operate yourself.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = selfHostedUrl,
                                    onValueChange = {
                                        selfHostedUrl = it
                                        selfHostedError = null
                                    },
                                    label = { Text("Server URL") },
                                    supportingText = {
                                        Text(
                                            selfHostedError
                                                ?: "Enter the server URL for the instance you operate."
                                        )
                                    },
                                    isError = selfHostedError != null,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        selfHostedError = null
                                        isValidatingSelfHosted = true
                                        scope.launch {
                                            val result = onContinueSelfHosted(trimmedSelfHostedUrl)
                                            selfHostedError = result.exceptionOrNull()?.message
                                            isValidatingSelfHosted = false
                                        }
                                    },
                                    enabled = trimmedSelfHostedUrl.isNotEmpty() && !isValidatingSelfHosted
                                ) {
                                    if (isValidatingSelfHosted) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text("Continue")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDestructive?.let { action ->
        AlertDialog(
            onDismissRequest = {
                val dismissed = pendingDestructive
                pendingDestructive = null
                if (dismissed == PendingDestructiveAction.NON_MATCHING_AUTH) {
                    scope.launch { onCancelDestructiveAuth() }
                }
            },
            title = { Text("Replace your saved budget?") },
            text = {
                Text(
                    "The budget on this device will be erased so you can start fresh. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val confirmed = action
                        pendingDestructive = null
                        scope.launch {
                            when (confirmed) {
                                PendingDestructiveAction.GET_STARTED -> onGetStarted(true)
                                PendingDestructiveAction.NON_MATCHING_AUTH -> onAuthenticated(true)
                            }
                        }
                    }
                ) {
                    Text("Replace budget")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val dismissed = pendingDestructive
                        pendingDestructive = null
                        if (dismissed == PendingDestructiveAction.NON_MATCHING_AUTH) {
                            scope.launch { onCancelDestructiveAuth() }
                        }
                    }
                ) {
                    Text("Keep it")
                }
            }
        )
    }
}

/**
 * Thrown by welcome credential submit when auth succeeded but the account does not match
 * held local data. The Welcome screen prompts before wiping.
 */
class NonMatchingAccountException(
    message: String = "That account doesn't match the budget on this device."
) : Exception(message)

@Composable
private fun HeldLocalDataCard(
    notice: String?,
    onRelogin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                when (notice) {
                    "session_expired" -> "You're signed out, but your budget is still here."
                    "signed_out" -> "You're signed out, but your budget is still here."
                    else -> "Your budget is still saved on this device."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Sign in with the same account to pick up where you left off. Starting over or using a different account will replace what's here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onRelogin) {
                Text("Sign in to keep it")
            }
        }
    }
}

@Composable
private fun OnboardingActionCard(
    title: String,
    body: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Icon(
                imageVector = MaterialIcons.Filled.Chevron_right,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AppLogo() {
    val appLogoDataUrl = remember { "data:image/png;base64,${AppLogoAsset.base64Png}" }

    Card(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = appLogoDataUrl,
            contentDescription = "Shilling logo",
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentScale = ContentScale.Fit
        )
    }
}
