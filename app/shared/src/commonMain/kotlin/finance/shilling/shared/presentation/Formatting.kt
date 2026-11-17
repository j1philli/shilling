package finance.shilling.shared.presentation

import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.ScheduledTx
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock

// ─── Currency ────────────────────────────────────────────────────────────────

/** `$1,234.56` / `-$1,234.56`, using the user's currency symbol. */
fun formatCurrency(amount: Double): String {
    val cents = (abs(amount) * 100).roundToLong()
    val whole = (cents / 100).toString().reversed().chunked(3).joinToString(",").reversed()
    val frac = (cents % 100).toString().padStart(2, '0')
    val sign = if (amount < 0 && cents != 0L) "-" else ""
    return "$sign${DisplayPreferences.currencySymbol}$whole.$frac"
}

fun formatSigned(tx: ScheduledTx): String = formatSigned(tx.type, tx.amount)

/** Income is `+`, expenses are `-`, transfers carry no sign (money stays in the household). */
fun formatSigned(type: ScheduleType, amount: Double): String = when (type) {
    ScheduleType.INCOME -> "+${formatCurrency(amount)}"
    ScheduleType.EXPENSE -> "-${formatCurrency(amount)}"
    ScheduleType.TRANSFER -> formatCurrency(amount)
}

/** Plain editable representation of an amount, e.g. `1234.50`. */
fun formatAmountInput(amount: Double): String {
    val cents = (abs(amount) * 100).roundToLong()
    return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/** Parses user amount input, tolerating grouping commas and a leading currency symbol. */
fun parseAmountInput(text: String): Double? =
    text.trim().removePrefix(DisplayPreferences.currencySymbol.trim()).replace(",", "").trim()
        .takeIf { it.isNotEmpty() }?.toDoubleOrNull()

// ─── Dates ───────────────────────────────────────────────────────────────────

fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

val DayOfWeek.shortLabel: String get() = fullLabel.take(3)
val DayOfWeek.fullLabel: String get() = name.lowercase().replaceFirstChar { it.titlecase() }
val Month.shortLabel: String get() = fullLabel.take(3)
val Month.fullLabel: String get() = name.lowercase().replaceFirstChar { it.titlecase() }

/** `Fri, Sep 25`, with the year appended when it isn't the current year. */
fun formatDate(date: LocalDate, reference: LocalDate = today()): String {
    val base = "${date.dayOfWeek.shortLabel}, ${date.month.shortLabel} ${date.day}"
    return if (date.year == reference.year) base else "$base, ${date.year}"
}

/** `Sep 25, 2026` — used in form fields where the weekday is noise. */
fun formatDateMedium(date: LocalDate): String = "${date.month.shortLabel} ${date.day}, ${date.year}"

/** Day-group header: `Today`, `Tomorrow`, `Yesterday`, otherwise [formatDate]. */
fun formatDayHeader(date: LocalDate, reference: LocalDate = today()): String = when (date) {
    reference -> "Today"
    reference.plusDays(1) -> "Tomorrow"
    reference.plusDays(-1) -> "Yesterday"
    else -> formatDate(date, reference)
}

/** `Sep 25 – Oct 1`, with the year when the range isn't in the current year. */
fun formatDateRange(start: LocalDate, endInclusive: LocalDate, reference: LocalDate = today()): String {
    val startLabel = "${start.month.shortLabel} ${start.day}"
    val endLabel = if (start.month == endInclusive.month && start.year == endInclusive.year) {
        "${endInclusive.day}"
    } else {
        "${endInclusive.month.shortLabel} ${endInclusive.day}"
    }
    val range = "$startLabel – $endLabel"
    return if (start.year == reference.year && endInclusive.year == reference.year) range
    else "$range, ${endInclusive.year}"
}

fun formatMonthYear(date: LocalDate): String = "${date.month.fullLabel} ${date.year}"

fun formatTimestamp(epochMillis: Long): String {
    val dt = kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour12 = if (dt.hour % 12 == 0) 12 else dt.hour % 12
    val amPm = if (dt.hour < 12) "AM" else "PM"
    return "${formatDate(dt.date)}, $hour12:${dt.minute.toString().padStart(2, '0')} $amPm"
}

private fun LocalDate.plusDays(days: Int): LocalDate = LocalDate.fromEpochDays(toEpochDays() + days)

fun LocalDate.startOfMonth(): LocalDate = LocalDate(year, month, 1)

fun LocalDate.weekOfMonth(): Int = ((day - 1) / 7) + 1

// ─── Domain labels ───────────────────────────────────────────────────────────

val ScheduleType.label: String
    get() = when (this) {
        ScheduleType.EXPENSE -> "Expense"
        ScheduleType.INCOME -> "Income"
        ScheduleType.TRANSFER -> "Transfer"
    }

val ScheduleType.pluralLabel: String
    get() = when (this) {
        ScheduleType.EXPENSE -> "Expenses"
        ScheduleType.INCOME -> "Income"
        ScheduleType.TRANSFER -> "Transfers"
    }

/** Status shown once an occurrence has been recorded. */
val ScheduleType.postedLabel: String
    get() = when (this) {
        ScheduleType.EXPENSE -> "Paid"
        ScheduleType.INCOME -> "Received"
        ScheduleType.TRANSFER -> "Moved"
    }

/** Action that records an upcoming occurrence. */
val ScheduleType.markActionLabel: String
    get() = "Mark ${postedLabel.lowercase()}"

val Frequency.label: String
    get() = when (this) {
        Frequency.ONCE -> "Does not repeat"
        Frequency.DAILY -> "Daily"
        Frequency.WEEKLY -> "Weekly"
        Frequency.BI_WEEKLY -> "Every 2 weeks"
        Frequency.MONTHLY_BY_DAY -> "Monthly on a date"
        Frequency.MONTHLY_BY_NTH_WEEKDAY -> "Monthly on a weekday"
        Frequency.SEMI_MONTHLY -> "Twice a month (15th and last day)"
        Frequency.YEARLY -> "Yearly"
    }

/** Unit for the "every N …" control, or null when the frequency has a fixed cadence. */
fun Frequency.intervalUnit(count: Int): String? {
    val plural = count != 1
    return when (this) {
        Frequency.DAILY -> if (plural) "days" else "day"
        Frequency.WEEKLY -> if (plural) "weeks" else "week"
        Frequency.MONTHLY_BY_DAY, Frequency.MONTHLY_BY_NTH_WEEKDAY -> if (plural) "months" else "month"
        Frequency.YEARLY -> if (plural) "years" else "year"
        Frequency.ONCE, Frequency.BI_WEEKLY, Frequency.SEMI_MONTHLY -> null
    }
}

fun ordinal(n: Int): String {
    if (n % 100 in 11..13) return "${n}th"
    return when (n % 10) {
        1 -> "${n}st"
        2 -> "${n}nd"
        3 -> "${n}rd"
        else -> "${n}th"
    }
}

fun describeRecurrence(schedule: Schedule): String {
    val n = schedule.interval.coerceAtLeast(1)
    val every = { unit: String, singular: String -> if (n == 1) singular else "Every $n $unit" }
    return when (schedule.freq) {
        Frequency.ONCE -> "Once on ${formatDate(schedule.startDate)}"
        Frequency.DAILY -> every("days", "Daily")
        Frequency.WEEKLY -> "${every("weeks", "Weekly")} on ${dayMaskLabel(schedule.byDayMask, schedule.startDate)}"
        Frequency.BI_WEEKLY -> "Every 2 weeks on ${schedule.startDate.dayOfWeek.shortLabel}"
        Frequency.MONTHLY_BY_DAY -> {
            val on = if (schedule.lastDayFlag) "the last day"
            else "the ${ordinal(schedule.byMonthDay ?: schedule.startDate.day)}"
            "${every("months", "Monthly")} on $on"
        }
        Frequency.MONTHLY_BY_NTH_WEEKDAY -> {
            val nth = schedule.nthWeekday ?: schedule.startDate.weekOfMonth()
            val weekday = schedule.byDayMask?.let { dayMaskFirst(it) } ?: schedule.startDate.dayOfWeek
            "${every("months", "Monthly")} on the ${ordinal(nth)} ${weekday.fullLabel}"
        }
        Frequency.SEMI_MONTHLY -> "On the 15th and last day of each month"
        Frequency.YEARLY -> "${every("years", "Yearly")} on ${schedule.startDate.month.shortLabel} ${schedule.startDate.day}"
    }
}

// ─── Day masks ───────────────────────────────────────────────────────────────

fun Set<DayOfWeek>.toDayMask(): Int = fold(0) { acc, day -> acc or (1 shl day.ordinal) }

fun Int.toSetOfDays(): Set<DayOfWeek> =
    DayOfWeek.entries.filter { (this and (1 shl it.ordinal)) != 0 }.toSet()

fun dayMaskFirst(mask: Int): DayOfWeek? = DayOfWeek.entries.firstOrNull { (mask and (1 shl it.ordinal)) != 0 }

fun dayMaskLabel(mask: Int?, fallback: LocalDate): String {
    val days = mask?.toSetOfDays().orEmpty()
    if (days.isEmpty()) return fallback.dayOfWeek.shortLabel
    return DayOfWeek.entries.filter { it in days }.joinToString(", ") { it.shortLabel }
}

fun Schedule.firstWeekdayFromMaskFallback(): DayOfWeek =
    byDayMask?.let { dayMaskFirst(it) } ?: startDate.dayOfWeek

// ─── Misc ────────────────────────────────────────────────────────────────────

fun formatFileSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> {
        val tenths = (bytes.toLong() * 10 + 512 * 1024) / (1024 * 1024)
        "${tenths / 10}.${tenths % 10} MB"
    }
}
