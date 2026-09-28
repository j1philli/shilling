package finance.shilling.shared.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrencePairIdTest {

    @Test
    fun pairIdIsUniquePerScheduleAndDate() {
        val schedule = Schedule(
            id = "save",
            title = "Savings",
            amount = 100.0,
            type = ScheduleType.TRANSFER,
            accountId = "checking",
            counterAccountId = "savings",
            startDate = LocalDate(2024, 1, 1),
            freq = Frequency.DAILY
        )

        val pairIds = generateOccurrences(schedule, LocalDate(2024, 1, 1), LocalDate(2024, 1, 3)).map { it.pairId }

        assertEquals(listOf("save_2024-01-01", "save_2024-01-02"), pairIds)
    }
}
