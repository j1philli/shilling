package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage

internal fun String.isPreviewableImageName(): Boolean {
    val ext = substringAfterLast('.', "").lowercase()
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "gif" ||
        ext == "webp" || ext == "bmp"
}

internal fun String.isHeifFamilyName(): Boolean {
    val ext = substringAfterLast('.', "").lowercase()
    return ext == "heic" || ext == "heif"
}

private val receiptOpenLog = Logger.withTag("ReceiptOpen")

/**
 * Returns a function that opens a receipt: images preview in-app, everything else
 * (PDFs, HEIC, missing bytes) is handed to the platform's external viewer.
 */
@Composable
fun rememberReceiptOpener(): (Receipt) -> Unit {
    val fileStore = koinInject<ReceiptFileStore>()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }

    preview?.let { (name, bytes) ->
        ReceiptImagePreviewDialog(fileName = name, imageBytes = bytes, onDismiss = { preview = null })
    }

    return remember(fileStore, scope) {
        { receipt ->
            scope.launch {
                runCatching {
                    val bytes = if (receipt.originalName.isPreviewableImageName()) fileStore.read(receipt.id) else null
                    if (bytes != null) {
                        preview = receipt.originalName to bytes
                    } else {
                        fileStore.openExternally(receipt.id, receipt.originalName)
                    }
                }.onFailure { t ->
                    receiptOpenLog.e(t) { "Open failed: id=${receipt.id}, name=${receipt.originalName}" }
                    snackbar.show("Couldn't open ${receipt.originalName}")
                }
            }
        }
    }
}

@Composable
internal fun DefaultReceiptImagePreviewDialog(
    fileName: String,
    imageBytes: ByteArray,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(fileName, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                AsyncImage(
                    model = imageBytes,
                    contentDescription = fileName,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
internal expect fun ReceiptImagePreviewDialog(fileName: String, imageBytes: ByteArray, onDismiss: () -> Unit)
