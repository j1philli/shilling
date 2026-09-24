package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.DependencyReachability
import finance.shilling.shared.data.auth.HostedBootstrapPhase
import finance.shilling.shared.data.auth.HostedBootstrapStatus
import finance.shilling.shared.data.auth.SignUpResult
import kotlinx.coroutines.launch

enum class HostedCredentialsMode { SIGN_IN, CREATE_ACCOUNT }

data class HostedCredentialsSubmitResult(
    val mode: HostedCredentialsMode,
    val signUpResult: SignUpResult? = null
)

@Composable
fun HostedCredentialsForm(
    authService: AuthService?,
    enabled: Boolean = authService != null,
    disabledReason: String? = null,
    initialMode: HostedCredentialsMode = HostedCredentialsMode.SIGN_IN,
    signInLabel: String = "Sign in",
    onSubmit: suspend (mode: HostedCredentialsMode, email: String, password: String) -> Result<HostedCredentialsSubmitResult>,
    onMessage: (String) -> Unit = {},
    extraActions: @Composable (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(initialMode) }
    var emailText by remember { mutableStateOf("") }
    var passwordText by remember { mutableStateOf("") }
    var authMessage by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var emailConfirmationSheet by remember { mutableStateOf<EmailConfirmationSheetState?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (mode == HostedCredentialsMode.SIGN_IN) {
                "Sign in to an existing account."
            } else {
                "Create a new account with email and password."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        disabledReason?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = emailText,
            onValueChange = { emailText = it; authMessage = "" },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = passwordText,
            onValueChange = { passwordText = it; authMessage = "" },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                scope.launch {
                    isSubmitting = true
                    val result = onSubmit(mode, emailText.trim(), passwordText)
                    authMessage = result.fold(
                        onSuccess = { submit ->
                            val signUpResult = submit.signUpResult
                            if (signUpResult?.requiresEmailConfirmation == true) {
                                emailConfirmationSheet = EmailConfirmationSheetState(
                                    email = emailText.trim(),
                                    upgradedFromGuest = signUpResult.upgradedAnonymousSession
                                )
                            }
                            emailText = ""
                            passwordText = ""
                            when {
                                submit.mode == HostedCredentialsMode.SIGN_IN -> "Signed in."
                                signUpResult?.requiresEmailConfirmation == true ->
                                    "Check your email to confirm this account change."
                                else -> "Account created."
                            }
                        },
                        onFailure = { "Error: ${it.message ?: "Unknown error"}" }
                    )
                    onMessage(authMessage)
                    isSubmitting = false
                }
            },
            enabled = enabled && !isSubmitting && emailText.isNotBlank() && passwordText.isNotBlank()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                if (mode == HostedCredentialsMode.SIGN_IN) signInLabel else "Create account"
            )
        }
        TextButton(
            onClick = {
                mode = if (mode == HostedCredentialsMode.SIGN_IN) {
                    HostedCredentialsMode.CREATE_ACCOUNT
                } else {
                    HostedCredentialsMode.SIGN_IN
                }
                authMessage = ""
            },
            enabled = enabled && !isSubmitting
        ) {
            Text(
                if (mode == HostedCredentialsMode.SIGN_IN) {
                    "Create a new account instead"
                } else {
                    "Already have an account? Sign in"
                }
            )
        }
        extraActions?.invoke()
        if (authMessage.isNotBlank()) {
            Text(authMessage, style = MaterialTheme.typography.bodySmall)
        }
    }

    emailConfirmationSheet?.let { sheet ->
        EmailConfirmationBottomSheet(
            email = sheet.email,
            upgradedFromGuest = sheet.upgradedFromGuest,
            onDismiss = { emailConfirmationSheet = null }
        )
    }
}

@Composable
fun HostedBootstrapStatusCard(
    status: HostedBootstrapStatus,
    onRetry: () -> Unit,
    title: String = "Managed status"
) {
    ShillingCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("Phase: ${bootstrapPhaseLabel(status.phase)}", style = MaterialTheme.typography.bodySmall)
        Text("Server: ${reachabilityLabel(status.serverReachability)}", style = MaterialTheme.typography.bodySmall)
        Text("Supabase: ${reachabilityLabel(status.supabaseReachability)}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Sync: ${if (status.syncReady) "Ready" else "Not ready"}",
            style = MaterialTheme.typography.bodySmall
        )
        status.lastError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = onRetry,
            enabled = !status.isChecking
        ) {
            if (status.isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp
                )
                Text("Checking...")
            } else {
                Text("Retry now")
            }
        }
    }
}

private fun bootstrapPhaseLabel(phase: HostedBootstrapPhase): String = when (phase) {
    HostedBootstrapPhase.LOCAL_ONLY -> "Local-only"
    HostedBootstrapPhase.WAITING_FOR_SERVER -> "Waiting for server"
    HostedBootstrapPhase.WAITING_FOR_SUPABASE -> "Waiting for Supabase"
    HostedBootstrapPhase.WAITING_FOR_HOUSEHOLD -> "Waiting for household"
    HostedBootstrapPhase.READY -> "Ready"
    HostedBootstrapPhase.LOGIN_REQUIRED -> "Login required"
}

private fun reachabilityLabel(reachability: DependencyReachability): String = when (reachability) {
    DependencyReachability.UNKNOWN -> "Unknown"
    DependencyReachability.REACHABLE -> "Reachable"
    DependencyReachability.UNREACHABLE -> "Unreachable"
}
