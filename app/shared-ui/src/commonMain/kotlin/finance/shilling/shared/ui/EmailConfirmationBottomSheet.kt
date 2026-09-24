package finance.shilling.shared.ui

import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

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
            Text("Confirm your email", style = MaterialTheme.typography.titleMedium)
            Text(
                if (upgradedFromGuest) {
                    "We sent a confirmation email to $email. Open it to finish upgrading this guest account to Free."
                } else {
                    "We sent a confirmation email to $email. Open it to finish creating your account."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Until you confirm it, this account change is still pending in Supabase Auth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    }
}
