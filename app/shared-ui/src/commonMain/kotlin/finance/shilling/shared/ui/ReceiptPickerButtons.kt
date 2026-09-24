package finance.shilling.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import io.github.vinceglb.filekit.PlatformFile

typealias ReceiptPickerButton = @Composable (onFile: (PlatformFile?) -> Unit) -> Unit

val LocalAutoLaunchCamera = compositionLocalOf { false }
