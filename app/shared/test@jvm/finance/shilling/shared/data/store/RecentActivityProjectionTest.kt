package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.presentation.mergeTransferLegs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.datetime.*
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import kotlin.random.Random
import kotlin.test.*

@OptIn(ExperimentalStoreApi::class)
class RecentActivityProjectionTest {
    private val end = LocalDate(2026, 10, 1)
    private val start = LocalDate(2025, 10, 1)

    @Test
    fun targetedTransferLookupKeepsFirstSameDayPartnerAndSuffixBehavior(): Unit = runBlocking {
        database { db ->
            val f = Fixture(db)
            val date = LocalDate(2026, 9, 30)
            f.accounts.upsert(Account("a", "Checking", 100.0))
            f.schedules.upsert(Schedule("s", "Scheduled", 4.0, ScheduleType.TRANSFER, "a",
                startDate = date, freq = Frequency.ONCE))
            val source = Posting("source", null, ScheduleType.EXPENSE, "a", date, 5.0, "shared")
            f.postings.record(source)
            repeat(100) { i ->
                f.postings.record(Posting("unrelated-$i", null, ScheduleType.EXPENSE, "a", date, 1.0))
            }
            val priorDay = Posting("prior", null, ScheduleType.INCOME, "a",
                date.minus(1, DateTimeUnit.DAY), 5.0, "shared")
            f.postings.record(priorDay)
            val first = Posting("first", "s", ScheduleType.INCOME, "a", date, 5.0, "shared")
            f.postings.record(first)
            f.postings.record(first.copy(id = "second", scheduleId = null))
            assertEquals("first", f.postings.getTransferPartner(source)?.id)
            assertEquals("source", f.postings.getTransferPartner(first.copy(scheduleId = null))?.id)

            val scheduledDebit = Posting("scheduled_dr", "s", ScheduleType.EXPENSE, "a", date, 5.0)
            f.postings.record(scheduledDebit)
            f.postings.record(scheduledDebit.copy(id = "scheduled_cr", type = ScheduleType.INCOME,
                date = date.plus(1, DateTimeUnit.DAY)))
            assertEquals("scheduled_cr", f.postings.getTransferPartner(scheduledDebit)?.id)
            assertNull(f.postings.getTransferPartner(source.copy(id = "orphan", pairId = "missing")))
        }
    }

    @Test
    fun boundedCandidatesMatchFullHistoryAcrossPairsOrphansTiesAndDateLimits(): Unit = runBlocking {
        database { db ->
            val f = Fixture(db)
            f.accounts.upsert(Account("a", "Checking", 100.0))
            f.accounts.upsert(Account("b", "Savings", 50.0))
            f.categories.upsert(Category("c", "Bills", "#112233"))
            f.schedules.upsert(Schedule("s", "Scheduled", 4.0, ScheduleType.EXPENSE, "a",
                categoryId = "c", startDate = start, freq = Frequency.WEEKLY))
            repeat(10) { seed ->
                val random = Random(seed)
                f.store.clear(PostingKey.All)
                val rows = List(240) { i ->
                    val suffix = if (i % 8 < 2) if (i % 2 == 0) "_dr" else "_cr" else ""
                    Posting(
                        id = if (suffix.isEmpty()) "p-$i" else "s${i / 2}$suffix",
                        scheduleId = if (i % 5 == 0) "s" else null,
                        type = if (random.nextBoolean()) ScheduleType.INCOME else ScheduleType.EXPENSE,
                        accountId = if (i % 2 == 0) "a" else "b",
                        date = end.minus(random.nextInt(-1, 400), DateTimeUnit.DAY),
                        amount = i + 0.5,
                        pairId = if (i % 3 == 0) null else "pair-${i / 6}",
                        title = if (i % 7 == 0) null else "Transaction $i"
                    )
                }.shuffled(random)
                f.store.write(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.All, rows))
                val full = withTimeout(5_000) { f.postings.watchBetween(start, end).first() }
                for (limit in listOf(0, 1, 2, 3, 10, 200)) {
                    val bounded = withTimeout(5_000) { f.postings.watchRecentBetween(start, end, limit).first() }
                    assertTrue(bounded.size <= 4 * limit, "Unbounded result for seed=$seed, limit=$limit")
                    assertEquals(mergeTransferLegs(full).take(limit), mergeTransferLegs(bounded).take(limit),
                        "Changed transfer/order behavior for seed=$seed, limit=$limit")
                }
            }
            f.store.clear(PostingKey.All)
            assertTrue(withTimeout(5_000) { f.postings.watchRecentBetween(start, end, 3).first() }.isEmpty())
        }
    }

    @Test
    fun joinedProjectionsObserveWritesRenamesDeletionAndCategoryFallback(): Unit = runBlocking {
        database { db ->
            val f = Fixture(db)
            f.accounts.upsert(Account("a", "Checking", 100.0))
            f.categories.upsert(Category("inherited", "Scheduled category", "#112233"))
            f.categories.upsert(Category("override", "Direct category", null))
            val schedule = Schedule("s", "Schedule title", 4.0, ScheduleType.EXPENSE, "a",
                categoryId = "inherited", startDate = start, freq = Frequency.WEEKLY)
            f.schedules.upsert(schedule)
            var posting = Posting("p", "s", ScheduleType.EXPENSE, "a", end.minus(1, DateTimeUnit.DAY),
                4.0, categoryId = "override")
            f.postings.record(posting)
            val recent = Channel<List<PostingWithDetails>>(Channel.UNLIMITED)
            val single = Channel<PostingWithDetails?>(Channel.UNLIMITED)
            val jobs = listOf(
                launch { f.postings.watchRecentBetween(start, end, 3).collect { recent.send(it) } },
                launch { f.postings.watchById("p").collect { single.send(it) } }
            )
            suspend fun matching(predicate: (PostingWithDetails) -> Boolean): PostingWithDetails = withTimeout(5_000) {
                var a = recent.receive().single()
                while (!predicate(a)) a = recent.receive().single()
                var b = single.receive()
                while (b == null || !predicate(b)) b = single.receive()
                assertEquals(a, b)
                a
            }
            try {
                val first = matching { it.title == "Schedule title" }
                assertEquals("Direct category", first.categoryName)
                assertNull(first.categoryColor, "A direct category with no color must not inherit a schedule color")
                f.accounts.upsert(Account("a", "Renamed account", 100.0))
                matching { it.accountName == "Renamed account" }
                f.categories.upsert(Category("override", "Renamed category", "#445566"))
                matching { it.categoryName == "Renamed category" && it.categoryColor == "#445566" }
                f.schedules.upsert(schedule.copy(title = "Renamed schedule"))
                matching { it.title == "Renamed schedule" }
                posting = posting.copy(title = "", categoryId = "missing")
                f.postings.record(posting)
                matching { it.title == "" && it.categoryName == "Scheduled category" }
                f.postings.delete("p")
                withTimeout(5_000) {
                    while (recent.receive().isNotEmpty()) {}
                    while (single.receive() != null) {}
                }
                f.postings.record(posting.copy(title = "Recreated"))
                matching { it.title == "Recreated" }
            } finally { jobs.forEach { it.cancelAndJoin() }; recent.close(); single.close() }
        }
    }

    private class Fixture(db: ShillingDatabase) {
        val changes = ChangeNotifier()
        val store = createPostingStore(db)
        private val accountStore = createAccountStore(db)
        private val categoryStore = createCategoryStore(db)
        private val scheduleStore = createScheduleStore(db)
        val accounts = AccountRepository(changes, accountStore)
        val categories = CategoryRepository(changes, categoryStore)
        val schedules = ScheduleRepository(changes, scheduleStore, createScheduleExceptionStore(db))
        val postings = PostingRepository(object : IdGenerator { override fun newId() = "unused" },
            changes, store, accountStore, categoryStore, scheduleStore)
    }

    private suspend fun database(block: suspend (ShillingDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try { ShillingDatabase.Schema.create(driver).await(); block(ShillingDatabase(driver)) }
        finally { driver.close() }
    }
}
