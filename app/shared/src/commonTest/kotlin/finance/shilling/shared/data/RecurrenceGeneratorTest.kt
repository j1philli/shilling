package finance.shilling.shared.data

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceGeneratorTest {

    @Test
    fun weeklyMaskProducesSelectedDays() {
        val start = LocalDate(2024, 1, 1) // Monday
        val schedule = Schedule(
            id = "weekly",
            title = "Groceries",
            amount = 50.0,
            type = ScheduleType.EXPENSE,
            accountId = "checking",
            startDate = start,
            freq = Frequency.WEEKLY,
            byDayMask = mask(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        )
        val end = LocalDate(2024, 1, 15)
        val dates = generateOccurrences(schedule, start, end).map { it.date }

        assertEquals(
            listOf(
                LocalDate(2024, 1, 1),
                LocalDate(2024, 1, 3),
                LocalDate(2024, 1, 8),
                LocalDate(2024, 1, 10)
            ),
            dates
        )
    }

    @Test
    fun monthlyByDaySkipsMissingDates() {
        val schedule = Schedule(
            id = "monthly-31",
            title = "Rent",
            amount = 1200.0,
            type = ScheduleType.EXPENSE,
            accountId = "checking",
            startDate = LocalDate(2024, 1, 31),
            freq = Frequency.MONTHLY_BY_DAY,
            byMonthDay = 31
        )
        val rangeStart = LocalDate(2024, 1, 1)
        val rangeEnd = LocalDate(2024, 4, 1)
        val dates = generateOccurrences(schedule, rangeStart, rangeEnd).map { it.date }

        assertEquals(listOf(LocalDate(2024, 1, 31), LocalDate(2024, 3, 31)), dates)
    }

    @Test
    fun lastDayOfMonthHonorsLeapYear() {
        val schedule = Schedule(
            id = "last-day",
            title = "Statement",
            amount = 0.0,
            type = ScheduleType.EXPENSE,
            accountId = "checking",
            startDate = LocalDate(2024, 1, 31),
            freq = Frequency.MONTHLY_BY_DAY,
            lastDayFlag = true
        )
        val dates = generateOccurrences(schedule, LocalDate(2024, 1, 1), LocalDate(2024, 4, 1)).map { it.date }

        assertEquals(
            listOf(
                LocalDate(2024, 1, 31),
                LocalDate(2024, 2, 29),
                LocalDate(2024, 3, 31)
            ),
            dates
        )
    }

    @Test
    fun monthlyNthWeekdayMatchesCorrectDates() {
        val schedule = Schedule(
            id = "nth-weekday",
            title = "Team Lunch",
            amount = 40.0,
            type = ScheduleType.EXPENSE,
            accountId = "checking",
            startDate = LocalDate(2024, 5, 1),
            freq = Frequency.MONTHLY_BY_NTH_WEEKDAY,
            byDayMask = mask(DayOfWeek.TUESDAY),
            nthWeekday = 2
        )
        val dates = generateOccurrences(schedule, LocalDate(2024, 5, 1), LocalDate(2024, 7, 1)).map { it.date }

        assertEquals(listOf(LocalDate(2024, 5, 14), LocalDate(2024, 6, 11)), dates)
    }

    @Test
    fun yearlyLeapDayOnlyAppearsOnLeapYears() {
        val schedule = Schedule(
            id = "leap",
            title = "Leap Party",
            amount = 100.0,
            type = ScheduleType.EXPENSE,
            accountId = "checking",
            startDate = LocalDate(2024, 2, 29),
            freq = Frequency.YEARLY
        )
        val dates = generateOccurrences(schedule, LocalDate(2024, 1, 1), LocalDate(2029, 12, 31)).map { it.date }

        assertEquals(listOf(LocalDate(2024, 2, 29), LocalDate(2028, 2, 29)), dates)
    }

    @Test
    fun semiMonthlyHits15thAndLastDay() {
        val schedule = Schedule(
            id = "semi",
            title = "Semi",
            amount = 10.0,
            type = ScheduleType.EXPENSE,
            accountId = "checking",
            startDate = LocalDate(2024, 1, 15),
            freq = Frequency.SEMI_MONTHLY
        )
        val dates = generateOccurrences(schedule, LocalDate(2024, 1, 1), LocalDate(2024, 4, 1)).map { it.date }

        assertEquals(
            listOf(
                LocalDate(2024, 1, 15), LocalDate(2024, 1, 31),
                LocalDate(2024, 2, 15), LocalDate(2024, 2, 29),
                LocalDate(2024, 3, 15), LocalDate(2024, 3, 31)
            ),
            dates
        )
    }

    @Test
    fun biWeeklyRepeatsEvery14DaysFromStart() {
        val start = LocalDate(2024, 1, 3) // Wednesday
        val schedule = Schedule(
            id = "biweekly",
            title = "Alt Friday",
            amount = 100.0,
            type = ScheduleType.INCOME,
            accountId = "checking",
            startDate = start,
            freq = Frequency.BI_WEEKLY,
            interval = 2
        )
        val dates = generateOccurrences(schedule, start, start.plus(60, DateTimeUnit.DAY)).map { it.date }

        assertEquals(
            listOf(
                LocalDate(2024, 1, 3),
                LocalDate(2024, 1, 17),
                LocalDate(2024, 1, 31),
                LocalDate(2024, 2, 14),
                LocalDate(2024, 2, 28)
            ),
            dates
        )
    }

    @Test
    fun transferOccurrencesRetainCounterAccount() {
        val start = LocalDate(2024, 6, 1)
        val schedule = Schedule(
            id = "transfer",
            title = "Move to savings",
            amount = 200.0,
            type = ScheduleType.TRANSFER,
            accountId = "checking",
            counterAccountId = "savings",
            startDate = start,
            freq = Frequency.ONCE
        )
        val occurrences = generateOccurrences(schedule, start, start.plus(1, DateTimeUnit.DAY))
        assertEquals(1, occurrences.size)
        val transfer = occurrences.first()
        assertEquals("checking", transfer.accountId)
        assertEquals("savings", transfer.counterAccountId)
        assertEquals(ScheduleType.TRANSFER, transfer.type)
    }

    private fun mask(vararg days: DayOfWeek): Int = days.fold(0) { acc, day -> acc or (1 shl day.ordinal) }
}
