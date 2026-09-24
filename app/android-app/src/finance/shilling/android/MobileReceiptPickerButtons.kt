package finance.shilling.android

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

@Composable
fun MobileCameraReceiptButton(onFile: (PlatformFile?) -> Unit) {
    val cameraLauncher = rememberCameraPickerLauncher(onResult = onFile)
    Button(onClick = { cameraLauncher.launch() }) {
        Text("Camera")
    }
}

@Composable
fun MobilePhotoLibraryReceiptButton(onFile: (PlatformFile?) -> Unit) {
    val photoLauncher = rememberFilePickerLauncher(type = FileKitType.Image, onResult = onFile)
    Button(onClick = { photoLauncher.launch() }) {
        Text("Photo Library")
    }
}
