package finance.shilling.shared.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Chevron_right
import finance.shilling.shared.data.auth.AuthService
import org.jetbrains.compose.resources.painterResource
import shared_ui.generated.resources.Res
import shared_ui.generated.resources.app_logo
import finance.shilling.shared.presentation.OnboardingOption
import finance.shilling.shared.presentation.OnboardingRoute
import finance.shilling.shared.presentation.OnboardingViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FirstLaunchOnboardingView(
    authService: AuthService? = null,
    topPadding: Dp = 0.dp,
    viewModel: OnboardingViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        when (state.route) {
            OnboardingRoute.LANDING -> {
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
                        Text(state.welcomeTitle, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            state.welcomeMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 540.dp)
                        )

                        state.heldDataTitle?.let { title ->
                            HeldLocalDataCard(
                                title = title,
                                message = state.heldDataMessage,
                                action = state.heldDataAction,
                                onRelogin = { viewModel.open(OnboardingRoute.LOGIN) }
                            )
                        }

                        if (state.analyticsAvailable) {
                            Row {
                                Text("Share anonymous usage events", modifier = Modifier.weight(1f))
                                Switch(checked = state.analyticsConsent, onCheckedChange = viewModel::setAnalyticsConsent)
                            }
                        }

                        OnboardingActionCard(state.getStarted, onClick = viewModel::getStarted)
                        OnboardingActionCard(state.signIn, onClick = { viewModel.open(OnboardingRoute.LOGIN) })
                    }

                    OutlinedButton(
                        onClick = { viewModel.open(OnboardingRoute.SELF_HOSTED) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        Text(text = state.selfHostedLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OnboardingRoute.LOGIN -> {
                BackNavigationScaffold(onBack = viewModel::back, topPadding = topPadding) { innerPadding ->
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
                            SectionHeader(state.signIn.title, state.loginSubtitle)
                            ShillingCard {
                                HostedCredentialsForm(
                                    authService = authService,
                                    enabled = state.authReady,
                                    disabledReason = state.authDisabledReason,
                                    signInLabel = "Sign in",
                                    onSubmit = viewModel::submitCredentials
                                )
                            }
                        }
                    }
                }
            }

            OnboardingRoute.SELF_HOSTED -> {
                BackNavigationScaffold(
                    onBack = viewModel::back,
                    showBackButton = state.canLeaveSelfHosted,
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
                                Text(state.selfHostedMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(
                                    value = state.selfHostedUrl,
                                    onValueChange = viewModel::setSelfHostedUrl,
                                    label = { Text("Server URL") },
                                    supportingText = { Text(state.selfHostedHint) },
                                    isError = state.selfHostedError != null,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (state.analyticsAvailable) {
                                    Row {
                                        Text("Share anonymous usage events", modifier = Modifier.weight(1f))
                                        Switch(checked = state.analyticsConsent, onCheckedChange = viewModel::setAnalyticsConsent)
                                    }
                                }
                                Button(onClick = viewModel::continueSelfHosted, enabled = state.canContinueSelfHosted) {
                                    if (state.validatingSelfHosted) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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

    state.destructiveConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDestructive,
            title = { Text(confirm.title) },
            text = { Text(confirm.message) },
            confirmButton = { TextButton(onClick = viewModel::confirmDestructive) { Text(confirm.confirmLabel) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDestructive) { Text(state.keepLabel) } }
        )
    }
}

@Composable
private fun HeldLocalDataCard(
    title: String,
    message: String,
    action: String,
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
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRelogin) {
                Text(action)
            }
        }
    }
}

@Composable
private fun OnboardingActionCard(
    option: OnboardingOption,
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
                Text(option.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    option.body,
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
    Card(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Image(
            painter = painterResource(Res.drawable.app_logo),
            contentDescription = "Shilling logo",
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentScale = ContentScale.Fit
        )
    }
}
