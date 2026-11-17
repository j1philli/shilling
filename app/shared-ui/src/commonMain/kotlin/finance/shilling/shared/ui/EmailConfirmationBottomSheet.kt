package finance.shilling.shared.ui

import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import finance.shilling.shared.presentation.CredentialsCopy

data class EmailConfirmationSheetState(
    val email: String,
    val upgradedFromGuest: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailConfirmationBottomSheet(
    email: String,
    upgradedFromGuest: Boolean,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ShillingCard {
            Text(CredentialsCopy.CONFIRM_EMAIL_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(
                CredentialsCopy.confirmEmailMessage(email, upgradedFromGuest),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                CredentialsCopy.CONFIRM_EMAIL_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    }
}
