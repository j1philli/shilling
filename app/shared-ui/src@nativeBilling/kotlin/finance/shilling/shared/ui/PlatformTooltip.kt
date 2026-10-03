package finance.shilling.shared.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformTooltip(tooltip: String, enabled: Boolean, content: @Composable () -> Unit) {
    DefaultPlatformTooltip(tooltip, enabled, content)
}
