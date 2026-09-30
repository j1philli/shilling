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
import finance.shilling.shared.session.HostedCredentialsMode
import finance.shilling.shared.session.HostedCredentialsSubmitResult
import finance.shilling.shared.presentation.CredentialsCopy

@Composable
fun HostedCredentialsForm(
    authService: AuthService?,
    enabled: Boolean = authService != null,
    disabledReason: String? = null,
    initialMode: HostedCredentialsMode = HostedCredentialsMode.SIGN_IN,
    guestUpgrade: Boolean = false,
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
    val passwordRequired = mode != HostedCredentialsMode.CREATE_ACCOUNT || !guestUpgrade

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            CredentialsCopy.prompt(mode, guestUpgrade),
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
        if (passwordRequired) {
            OutlinedTextField(
                value = passwordText,
                onValueChange = { passwordText = it; authMessage = "" },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = {
                scope.launch {
                    isSubmitting = true
                    val result = onSubmit(mode, emailText.trim(), passwordText)
                    result.onSuccess { submit ->
                        if (CredentialsCopy.needsEmailConfirmation(result)) {
                            emailConfirmationSheet = EmailConfirmationSheetState(
                                email = emailText.trim(),
                                upgradedFromGuest = submit.signUpResult?.upgradedAnonymousSession == true
                            )
                        }
                        emailText = ""
                        passwordText = ""
                    }
                    authMessage = CredentialsCopy.resultMessage(result)
                    onMessage(authMessage)
                    isSubmitting = false
                }
            },
            enabled = enabled && !isSubmitting && emailText.isNotBlank() && (!passwordRequired || passwordText.isNotBlank())
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(CredentialsCopy.submitLabel(mode, signInLabel))
        }
        TextButton(
            onClick = {
                mode = CredentialsCopy.other(mode)
                passwordText = ""
                authMessage = ""
            },
            enabled = enabled && !isSubmitting
        ) {
            Text(CredentialsCopy.switchLabel(mode))
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
