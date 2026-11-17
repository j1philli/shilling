package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlinx.browser.document

/** Native hover text avoids the extra Compose popup owner, which loses web semantics on close. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun PlatformTooltip(tooltip: String, enabled: Boolean, content: @Composable () -> Unit) {
    DisposableEffect(tooltip, enabled) {
        onDispose { if (document.body?.title == tooltip) document.body?.removeAttribute("title") }
    }
    Box(Modifier
        .onPointerEvent(PointerEventType.Enter) { if (enabled) document.body?.title = tooltip }
        .onPointerEvent(PointerEventType.Exit) { if (document.body?.title == tooltip) document.body?.removeAttribute("title") }
    ) { content() }
}
