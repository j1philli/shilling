package finance.shilling.shared.presentation

import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.ScheduleType
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityProjectionTest {
    private fun item(id: String, pair: String? = null, income: Boolean = false, schedule: String? = null) =
        PostingWithDetails(
            Posting(id, schedule, if (income) ScheduleType.INCOME else ScheduleType.EXPENSE,
                if (income) "to" else "from", LocalDate(2026, 9, 29), 12.34, pair),
            id, if (income) "Savings" else "Checking", null, null
        )

    @Test
    fun pairsCreditFirstAndScheduledTransfersWithoutConsumingOrphans() {
        val rows = listOf(
            item("credit", "pair", income = true), item("debit", "pair"),
            item("scheduled_cr", "scheduled", income = true, schedule = "s"),
            item("scheduled_dr", "scheduled", schedule = "s"),
            item("orphan", "missing"), item("ordinary"),
            item("not-ad-hoc", "pair", schedule = "unrelated")
        )
        val actual = mergeTransferLegs(rows)
        assertEquals(listOf("debit", "scheduled_dr", "orphan", "ordinary", "not-ad-hoc"), actual.map { it.item.posting.id })
        assertEquals(listOf("Savings", "Savings", null, null, null), actual.map { it.toAccountName })
        assertEquals(listOf(ScheduleType.TRANSFER, ScheduleType.TRANSFER,
            ScheduleType.EXPENSE, ScheduleType.EXPENSE, ScheduleType.EXPENSE), actual.map { it.type })
    }

    @Test
    fun indexedProjectionMatchesOriginalAcrossMixedHistoryAndOrdering() {
        val rows = buildList {
            repeat(1_000) { i ->
                add(item("debit-$i", "pair-$i"))
                if (i % 7 != 0) add(item("credit-$i", "pair-$i", income = true))
                if (i % 13 == 0) add(item("extra-$i", "pair-$i", income = true))
                add(item("ordinary-$i"))
                add(item("s${i}_dr", schedule = "s$i"))
                add(item("s${i}_cr", income = true, schedule = "s$i"))
            }
        }
        repeat(5) { seed ->
            val shuffled = rows.shuffled(Random(seed))
            assertEquals(originalMerge(shuffled), mergeTransferLegs(shuffled))
        }
    }
}

/** Reference from public main, retained only to check behavior and measure the removed scans. */
internal fun originalMerge(postings: List<PostingWithDetails>): List<MergedRow> {
    val byId = postings.associateBy { it.posting.id }
    val consumed = mutableSetOf<String>()
    return postings.mapNotNull { item ->
        val p = item.posting
        if (p.id in consumed) return@mapNotNull null
        val partner = when {
            p.id.endsWith("_dr") -> byId[p.id.removeSuffix("_dr") + "_cr"]
            p.id.endsWith("_cr") -> byId[p.id.removeSuffix("_cr") + "_dr"]
            p.scheduleId == null && p.pairId != null ->
                postings.firstOrNull { it.posting.pairId == p.pairId && it.posting.id != p.id && it.posting.scheduleId == null }
            else -> null
        }
        if (partner == null) return@mapNotNull MergedRow(item, p.type, null)
        consumed += partner.posting.id
        val (debit, credit) = if (p.type == ScheduleType.EXPENSE) item to partner else partner to item
        MergedRow(debit, ScheduleType.TRANSFER, credit.accountName)
    }
}
