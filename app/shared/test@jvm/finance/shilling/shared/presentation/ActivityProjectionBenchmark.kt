package finance.shilling.shared.presentation

import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.ScheduleType
import kotlinx.datetime.LocalDate
import kotlin.system.measureNanoTime

/** Synthetic CPU benchmark; run with scripts/perf/bench_activity_projection.py after JVM tests. */
object ActivityProjectionBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val day = LocalDate(2026, 9, 29)
        for (count in listOf(1_000, 10_000, 20_000)) {
            val postings = List(count) { i ->
                val debit = i % 2 == 0
                PostingWithDetails(Posting("posting-$i", null,
                    if (debit) ScheduleType.EXPENSE else ScheduleType.INCOME,
                    if (debit) "checking" else "saving", day, 12.34, "pair-${i / 2}"),
                    "Synthetic transfer", if (debit) "Checking" else "Savings", null, null)
            }
            check(originalMerge(postings) == mergeTransferLegs(postings))
            repeat(5) { originalMerge(postings); mergeTransferLegs(postings) }
            repeat(15) { round ->
                val variants = if (round % 2 == 0) listOf("original", "indexed") else listOf("indexed", "original")
                for (variant in variants) {
                    var result: List<MergedRow> = emptyList()
                    val elapsed = measureNanoTime {
                        result = if (variant == "original") originalMerge(postings) else mergeTransferLegs(postings)
                    }
                    check(result.size == count / 2 && result.all { it.type == ScheduleType.TRANSFER })
                    println("{\"postings\":$count,\"variant\":\"$variant\",\"round\":$round,\"elapsedMs\":${elapsed / 1_000_000.0}}")
                }
            }
        }
    }
}
