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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun String.isPreviewableImageName(): Boolean {
    val ext = substringAfterLast('.', "").lowercase()
    return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "gif" ||
        ext == "webp" || ext == "bmp"
}

internal fun String.isHeifFamilyName(): Boolean {
    val ext = substringAfterLast('.', "").lowercase()
    return ext == "heic" || ext == "heif"
}

private fun String.toImageMimeType(): String {
    val ext = substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun imageDataUri(fileName: String, bytes: ByteArray): String {
    val mimeType = fileName.toImageMimeType()
    val encoded = Base64.Default.encode(bytes)
    return "data:$mimeType;base64,$encoded"
}

@Composable
internal fun ReceiptImagePreviewDialog(
    fileName: String,
    imageBytes: ByteArray,
    onDismiss: () -> Unit
) {
    val model = remember(fileName, imageBytes) {
        imageDataUri(fileName, imageBytes)
    }

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
                    model = model,
                    contentDescription = fileName,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
