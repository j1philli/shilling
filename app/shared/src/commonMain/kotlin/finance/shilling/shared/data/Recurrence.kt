package finance.shilling.shared.data

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

sealed class Recurrence {
    data object Once : Recurrence()
    data class Daily(val interval: Int = 1) : Recurrence()
    data class Weekly(val interval: Int = 1, val dayMask: Int) : Recurrence()
    data class BiWeekly(val intervalWeeks: Int = 2) : Recurrence()
    data class MonthlyByDay(
        val interval: Int = 1,
        val dayOfMonth: Int,
        val lastDay: Boolean = false
    ) : Recurrence()

    data class SemiMonthly(val dayA: Int = 15) : Recurrence()

    data class MonthlyByNthWeekday(
        val interval: Int = 1,
        val weekday: DayOfWeek,
        val nth: Int
    ) : Recurrence()

    data class Yearly(val interval: Int = 1) : Recurrence()

    companion object {
        fun fromSchedule(schedule: Schedule): Recurrence = when (schedule.freq) {
            Frequency.ONCE -> Once
            Frequency.DAILY -> Daily(schedule.interval)
            Frequency.WEEKLY -> Weekly(schedule.interval, schedule.byDayMask ?: (1 shl schedule.startDate.dayOfWeek.ordinal))
            Frequency.BI_WEEKLY -> BiWeekly(maxOf(2, schedule.interval))
            Frequency.MONTHLY_BY_DAY -> MonthlyByDay(
                interval = schedule.interval,
                dayOfMonth = schedule.byMonthDay ?: schedule.startDate.dayOfMonth,
                lastDay = schedule.lastDayFlag
            )
            Frequency.MONTHLY_BY_NTH_WEEKDAY -> MonthlyByNthWeekday(
                interval = schedule.interval,
                weekday = schedule.firstWeekdayFromMask() ?: schedule.startDate.dayOfWeek,
                nth = schedule.nthWeekday ?: schedule.startDate.weekOfMonth()
            )
            Frequency.SEMI_MONTHLY -> SemiMonthly()
            Frequency.YEARLY -> Yearly(schedule.interval)
        }
    }
}

/**
 * Generate projected occurrences for the given schedule in [rangeStart, rangeEnd).
 * Excludes dates that already have postings or are marked skipped via exceptions, and
 * applies override amounts/accounts when provided.
 */
fun generateOccurrences(
    schedule: Schedule,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    exceptions: Map<LocalDate, ScheduleException> = emptyMap(),
    postedDates: Set<LocalDate> = emptySet()
): List<ScheduledTx> {
    if (rangeStart >= rangeEnd) return emptyList()
    val effectiveStart = maxOf(schedule.startDate, rangeStart)
    val endDateLimit = schedule.endDate
    if (endDateLimit != null && endDateLimit < effectiveStart) return emptyList()

    val recurrence = Recurrence.fromSchedule(schedule)
    val items = mutableListOf<ScheduledTx>()
    var cursor = effectiveStart
    while (cursor < rangeEnd) {
        if (endDateLimit != null && cursor > endDateLimit) break
        if (matches(schedule, recurrence, cursor)) {
            val exception = exceptions[cursor]
            val isSkipped = exception?.skip == true
            val alreadyPosted = postedDates.contains(cursor)
            if (!isSkipped && !alreadyPosted) {
                val amount = exception?.overrideAmount ?: schedule.amount
                val accountId = exception?.overrideAccountId ?: schedule.accountId
                val counterAccountId = exception?.overrideCounterAccountId ?: schedule.counterAccountId
                val pairId = "${'$'}{schedule.id}_${'$'}cursor"
                items += ScheduledTx(
                    scheduleId = schedule.id,
                    title = schedule.title,
                    date = cursor,
                    amount = amount,
                    type = schedule.type,
                    accountId = accountId,
                    counterAccountId = counterAccountId,
                    categoryId = schedule.categoryId,
                    autoPay = schedule.autoPay,
                    pairId = pairId
                )
            }
        }
        cursor = cursor.plus(1, DateTimeUnit.DAY)
    }
    return items
}

private fun matches(schedule: Schedule, recurrence: Recurrence, date: LocalDate): Boolean {
    if (date < schedule.startDate) return false
    if (schedule.endDate != null && date > schedule.endDate) return false

    return when (recurrence) {
        Recurrence.Once -> date == schedule.startDate
        is Recurrence.Daily -> {
            val days = schedule.startDate.daysUntil(date)
            days % recurrence.interval == 0
        }

        is Recurrence.Weekly -> {
            val dayMatches = recurrence.dayMask.hasDay(date.dayOfWeek)
            if (!dayMatches) return false
            val days = schedule.startDate.daysUntil(date)
            val weeks = days / 7
            weeks % recurrence.interval == 0
        }

        is Recurrence.BiWeekly -> {
            val days = schedule.startDate.daysUntil(date)
            val periodDays = 7 * recurrence.intervalWeeks
            days % periodDays == 0
        }

        is Recurrence.MonthlyByDay -> {
            val months = schedule.startDate.monthsUntil(date)
            if (months % recurrence.interval != 0) return false
            if (recurrence.lastDay) {
                isLastDayOfMonth(date)
            } else {
                date.dayOfMonth == recurrence.dayOfMonth
            }
        }

        is Recurrence.MonthlyByNthWeekday -> {
            if (date.dayOfWeek != recurrence.weekday) return false
            val months = schedule.startDate.monthsUntil(date)
            if (months % recurrence.interval != 0) return false
            date.weekOfMonth() == recurrence.nth
        }

        is Recurrence.SemiMonthly -> {
            date.dayOfMonth == recurrence.dayA || isLastDayOfMonth(date)
        }

        is Recurrence.Yearly -> {
            val years = date.year - schedule.startDate.year
            if (years < 0 || years % recurrence.interval != 0) return false
            val start = schedule.startDate
            date.monthNumber == start.monthNumber && date.dayOfMonth == start.dayOfMonth
        }
    }
}

private fun Schedule.firstWeekdayFromMask(): DayOfWeek? {
    val mask = byDayMask ?: return null
    return DayOfWeek.values().firstOrNull { mask.hasDay(it) }
}

private fun Int.hasDay(day: DayOfWeek): Boolean = (this and (1 shl day.ordinal)) != 0

private fun LocalDate.daysUntil(other: LocalDate): Int = (other.toEpochDays() - this.toEpochDays()).toInt()

private fun LocalDate.monthsUntil(other: LocalDate): Int {
    return (other.year - year) * 12 + (other.monthNumber - monthNumber)
}

private fun LocalDate.weekOfMonth(): Int = ((dayOfMonth - 1) / 7) + 1

private fun isLastDayOfMonth(date: LocalDate): Boolean {
    val nextDay = date.plus(1, DateTimeUnit.DAY)
    return nextDay.monthNumber != date.monthNumber
}
