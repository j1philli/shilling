package finance.shilling.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import finance.shilling.shared.data.ScheduleType

// Compose-only formatting helpers; text formatting lives in shared/presentation/Formatting.kt.

@Composable
@ReadOnlyComposable
fun amountColor(type: ScheduleType): Color = when (type) {
    ScheduleType.EXPENSE -> MaterialTheme.colorScheme.onSurface
    ScheduleType.INCOME -> MaterialTheme.colorScheme.tertiary
    ScheduleType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
@ReadOnlyComposable
fun netColor(amount: Double): Color =
    if (amount >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error

fun colorFromHex(hex: String?): Color? {
    hex ?: return null
    val cleaned = hex.trim().removePrefix("#")
    val long = cleaned.toLongOrNull(16) ?: return null
    val argb = when (cleaned.length) {
        6 -> 0xFF000000 or long
        8 -> long
        else -> return null
    }
    return Color(argb.toInt())
}
