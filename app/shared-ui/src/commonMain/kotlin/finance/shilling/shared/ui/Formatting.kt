package finance.shilling.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Add
import com.composables.icons.materialicons.filled.Expand_less
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTx
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

fun formatCurrency(amount: Double): String {
    val cents = (abs(amount) * 100).roundToLong()
    val whole = cents / 100
    val frac = cents % 100
    val sign = if (amount < 0) "-" else ""
    return "$sign\$$whole.${frac.toString().padStart(2, '0')}"
}

fun formatSigned(tx: ScheduledTx): String = formatSigned(tx.type, tx.amount)

fun formatSigned(type: ScheduleType, amount: Double): String {
    val prefix = when (type) {
        ScheduleType.INCOME -> "+"
        ScheduleType.EXPENSE -> "-"
        ScheduleType.TRANSFER -> "->"
    }
    return "$prefix${formatCurrency(amount)}"
}

fun Set<DayOfWeek>.toDayMask(): Int = fold(0) { acc, day -> acc or (1 shl day.ordinal) }

fun colorFromHex(hex: String?): Color? {
    hex ?: return null
    val cleaned = hex.trim().removePrefix("#")
    val long = cleaned.toLongOrNull(16) ?: return null
    val argb = when (cleaned.length) {
        6 -> 0xFF000000 or long
        8 -> long
        else -> return null
    }
    val alpha = ((argb shr 24) and 0xFF).toInt()
    val red = ((argb shr 16) and 0xFF).toInt()
    val green = ((argb shr 8) and 0xFF).toInt()
    val blue = (argb and 0xFF).toInt()
    return Color(alpha = alpha / 255f, red = red / 255f, green = green / 255f, blue = blue / 255f)
}

fun LocalDate.startOfMonth(): LocalDate = LocalDate(year, monthNumber, 1)

fun Int.toSetOfDays(): Set<DayOfWeek> {
    val set = mutableSetOf<DayOfWeek>()
    DayOfWeek.values().forEach { day ->
        if ((this and (1 shl day.ordinal)) != 0) {
            set += day
        }
    }
    return set
}

fun Schedule.firstWeekdayFromMaskFallback(): DayOfWeek {
    val mask = byDayMask ?: return startDate.dayOfWeek
    return DayOfWeek.values().firstOrNull { day -> (mask and (1 shl day.ordinal)) != 0 } ?: startDate.dayOfWeek
}

fun describeRecurrence(schedule: Schedule): String = when (schedule.freq) {
    Frequency.ONCE -> "Once on ${schedule.startDate}"
    Frequency.DAILY -> "Every ${schedule.interval} day(s)"
    Frequency.WEEKLY -> {
        val days = dayMaskLabel(schedule.byDayMask)
        "Weekly ${if (schedule.interval > 1) "every ${schedule.interval} weeks " else ""}on $days"
    }
    Frequency.BI_WEEKLY -> "Every 2 weeks"
    Frequency.MONTHLY_BY_DAY -> {
        if (schedule.lastDayFlag) "Last day of each month"
        else "Day ${(schedule.byMonthDay ?: schedule.startDate.dayOfMonth)} every ${schedule.interval} month(s)"
    }
    Frequency.MONTHLY_BY_NTH_WEEKDAY -> {
        val nth = schedule.nthWeekday ?: schedule.startDate.weekOfMonth()
        val weekday = schedule.byDayMask?.let { dayMaskFirst(it) }?.name?.lowercase()?.replaceFirstChar { it.titlecase() }
            ?: schedule.startDate.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }
        "${ordinal(nth)} $weekday every ${schedule.interval} month(s)"
    }
    Frequency.SEMI_MONTHLY -> "15th and last day each month"
    Frequency.YEARLY -> "Yearly on ${schedule.startDate.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${schedule.startDate.dayOfMonth}"
}

fun dayMaskLabel(mask: Int?): String {
    if (mask == null) return "start day"
    val days = DayOfWeek.values().filter { (mask and (1 shl it.ordinal)) != 0 }
    return days.joinToString(", ") { it.name.take(3) }.ifEmpty { "start day" }
}

fun dayMaskFirst(mask: Int): DayOfWeek? = DayOfWeek.values().firstOrNull { (mask and (1 shl it.ordinal)) != 0 }

fun ordinal(n: Int): String {
    if (n in 11..13) return "${n}th"
    return when (n % 10) {
        1 -> "${n}st"
        2 -> "${n}nd"
        3 -> "${n}rd"
        else -> "${n}th"
    }
}

fun LocalDate.weekOfMonth(): Int = ((dayOfMonth - 1) / 7) + 1

@Composable
fun AddSectionToggle(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (expanded) MaterialIcons.Filled.Expand_less else MaterialIcons.Filled.Add,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            if (expanded) "Hide" else label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
