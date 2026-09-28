package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Arrow_drop_down
import com.composables.icons.materialicons.filled.Chevron_left
import com.composables.icons.materialicons.filled.Chevron_right

/**
 * `◀  Sep 25 – Oct 1 ▾  ▶  [This week]` — shared by week and month views. Tapping the
 * label opens a picker; the reset action appears only when away from the current period.
 */
@Composable
fun PeriodNavigator(
    label: String,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    resetLabel: String? = null,
    onReset: () -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        TooltipIconButton(onClick = onPrevious, tooltip = previousLabel) {
            Icon(MaterialIcons.Filled.Chevron_left, contentDescription = previousLabel)
        }
        TextButton(onClick = onPick) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Icon(
                MaterialIcons.Filled.Arrow_drop_down,
                contentDescription = "Choose period",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TooltipIconButton(onClick = onNext, tooltip = nextLabel) {
            Icon(MaterialIcons.Filled.Chevron_right, contentDescription = nextLabel)
        }
        if (resetLabel != null) {
            TextButton(onClick = onReset) { Text(resetLabel) }
        }
    }
}
