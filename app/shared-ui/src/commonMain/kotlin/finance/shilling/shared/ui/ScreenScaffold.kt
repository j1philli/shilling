package finance.shilling.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Arrow_back
import com.composables.icons.materialicons.filled.Close

/** Spacing scale shared by every screen. */
/** Height of every screen's title row. */
val HeaderHeight = 64.dp

private val SubtitleTuck = 14.dp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

enum class ScreenNavIcon { NONE, BACK, CLOSE }

/** Horizontal gutter for screen content: tighter on phones, roomier on tablets/desktop. */
@Composable
fun screenGutter(): Dp =
    if (currentWindowAdaptiveInfoV2().windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    ) Spacing.xl else Spacing.lg

/**
 * Standard screen chrome: title row (optional back/close, title, subtitle, actions) above
 * content. Content receives padding that applies the screen gutter, so lists can pass it
 * straight to `contentPadding` and scroll edge-to-edge.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navIcon: ScreenNavIcon = ScreenNavIcon.NONE,
    onNavIcon: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    /** Caps content width (header stays full width) for single-list screens on wide windows. */
    maxContentWidth: Dp = Dp.Unspecified,
    /** Full-width content under the title row, e.g. a section tab row. */
    headerBottom: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val gutter = screenGutter()
    // Title start: after the nav icon (4dp inset + 48dp button + 4dp gap) or at the gutter.
    val titleStart = if (navIcon == ScreenNavIcon.NONE) gutter else Spacing.xs + 48.dp + Spacing.xs
    // Opaque so screens never show through each other during navigation transitions.
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Fixed-height title row holding only the title, so its position and size never change
        // between screens. The subtitle, when present, gets its own line underneath.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(HeaderHeight)
                .padding(start = if (navIcon == ScreenNavIcon.NONE) gutter else Spacing.xs, end = gutter - Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            when (navIcon) {
                ScreenNavIcon.NONE -> Unit
                ScreenNavIcon.BACK -> TooltipIconButton(onClick = onNavIcon, tooltip = "Back") {
                    Icon(MaterialIcons.Filled.Arrow_back, contentDescription = "Back")
                }
                ScreenNavIcon.CLOSE -> TooltipIconButton(onClick = onNavIcon, tooltip = "Close") {
                    Icon(MaterialIcons.Filled.Close, contentDescription = "Close")
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                content = actions
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = titleStart, end = gutter, bottom = Spacing.xs)
                    // Tuck up under the title; the title row keeps its fixed height.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val shift = SubtitleTuck.roundToPx()
                        layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
                            placeable.place(0, -shift)
                        }
                    }
            )
        }
        headerBottom?.invoke()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .wrapContentWidth(Alignment.Start)
                .let { if (maxContentWidth != Dp.Unspecified) it.widthIn(max = maxContentWidth + gutter * 2) else it }
        ) {
            content(
                PaddingValues(
                    start = gutter,
                    end = gutter,
                    top = Spacing.sm,
                    bottom = Spacing.xl
                )
            )
        }
    }
}
