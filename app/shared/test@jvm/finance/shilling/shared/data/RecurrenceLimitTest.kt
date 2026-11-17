package finance.shilling.shared.data

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.*

class RecurrenceLimitTest {
    @Test
    fun boundedGenerationPreservesEveryFrequencyExceptionsAndDateBoundaries() {
        val start = LocalDate(2024, 2, 29)
        for (frequency in Frequency.entries) {
            for (interval in listOf(1, 2, 5)) {
                val schedule = Schedule("s", "Synthetic", 12.5, ScheduleType.TRANSFER, "a",
                    counterAccountId = "b", startDate = start, freq = frequency, interval = interval,
                    byDayMask = 0b1010101, byMonthDay = 31, nthWeekday = 5)
                for (variant in listOf(schedule, schedule.copy(lastDayFlag = true),
                    schedule.copy(endDate = start.plus(60, DateTimeUnit.DAY)))) {
                    val end = start.plus(3, DateTimeUnit.YEAR)
                    val initial = generateOccurrences(variant, start, end)
                    val exceptions = initial.take(4).mapIndexed { i, tx ->
                        tx.date to ScheduleException("s", tx.date, skip = i % 2 == 0, overrideAmount = 99.0,
                            overrideAccountId = "c", overrideCounterAccountId = "d")
                    }.toMap()
                    val posted = initial.drop(4).take(2).map { it.date }.toSet()
                    for (from in listOf(start, start.plus(45, DateTimeUnit.DAY), end)) {
                        val full = generateOccurrences(variant, from, end, exceptions, posted)
                        for (limit in listOf(0, 1, 3, 2000)) {
                            assertEquals(full.take(limit),
                                generateOccurrences(variant, from, end, exceptions, posted, limit),
                                "$frequency / $interval / $from / limit=$limit")
                        }
                    }
                }
            }
        }
    }
}
