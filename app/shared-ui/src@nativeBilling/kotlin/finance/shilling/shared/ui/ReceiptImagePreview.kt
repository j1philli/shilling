package finance.shilling.shared.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun ReceiptImagePreviewDialog(fileName: String, imageBytes: ByteArray, onDismiss: () -> Unit) =
    DefaultReceiptImagePreviewDialog(fileName, imageBytes, onDismiss)
