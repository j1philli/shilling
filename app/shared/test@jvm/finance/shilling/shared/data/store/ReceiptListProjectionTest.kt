package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import kotlin.test.*

@OptIn(ExperimentalStoreApi::class)
class ReceiptListProjectionTest {
    private val day = LocalDate(2026, 1, 15)
    private val schedule = Schedule("s", "Schedule title", 10.0, ScheduleType.EXPENSE,
        "a", startDate = day, freq = Frequency.MONTHLY_BY_DAY)
    private val posting = Posting("p", "s", ScheduleType.EXPENSE, "a", day, 10.0)

    @Test
    fun joinedListPreservesOrderingMetadataFallbackAndUnattachedReceipts(): Unit = runBlocking {
        withDatabase { db ->
            val schedules = createScheduleStore(db)
            val postings = createPostingStore(db)
            val repo = ReceiptRepository(ChangeNotifier(), createReceiptStore(db))
            schedules.write(StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(ScheduleKey.All, listOf(schedule)))
            postings.write(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.All, listOf(
                posting, posting.copy(id = "direct", title = "Posting title"),
                posting.copy(id = "empty-title", title = ""), posting.copy(id = "no-schedule", scheduleId = "missing")
            )))
            val rows = listOf(
                Receipt("fallback", "p", "f", "scan.png", 1, 123, 9.5, "note"),
                Receipt("direct", "direct", "f", "scan.png", 2),
                Receipt("empty-title", "empty-title", "f", "scan.png", 3),
                Receipt("no-schedule", "no-schedule", "f", "scan.png", 4),
                Receipt("missing-posting", "missing", "f", "scan.png", 5),
                Receipt("unattached", null, "f", "scan.png", 6)
            )
            rows.forEach { repo.save(it) }
            val actual = withTimeout(5_000) { repo.watchAll().first() }
            assertEquals(rows.reversed(), actual.map { it.receipt })
            val byId = actual.associateBy { it.receipt.id }
            assertEquals("Schedule title", byId.getValue("fallback").postingTitle)
            assertEquals("Posting title", byId.getValue("direct").postingTitle)
            assertEquals("", byId.getValue("empty-title").postingTitle)
            assertNull(byId.getValue("no-schedule").postingTitle)
            assertEquals(day.toString(), byId.getValue("no-schedule").postingDate)
            assertNull(byId.getValue("missing-posting").postingDate)
            assertNull(byId.getValue("unattached").postingTitle)
        }
    }

    @Test
    fun activeListObservesAllJoinedTablesAndAttachmentChanges(): Unit = runBlocking {
        withDatabase { db ->
            val schedules = createScheduleStore(db)
            val postings = createPostingStore(db)
            val repo = ReceiptRepository(ChangeNotifier(), createReceiptStore(db))
            schedules.write(StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(ScheduleKey.All, listOf(schedule)))
            postings.write(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.All, listOf(posting)))
            repo.save(Receipt("r", "p", "f", "scan.png", 1))
            val changes = Channel<List<ReceiptWithPosting>>(Channel.UNLIMITED)
            val watcher = launch { repo.watchAll().collect { changes.send(it) } }
            suspend fun next() = withTimeout(5_000) { changes.receive() }
            try {
                assertEquals("Schedule title", next().single().postingTitle)
                schedules.write(StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(ScheduleKey.ById("s"), listOf(schedule.copy(title = "Renamed"))))
                assertEquals("Renamed", next().single().postingTitle)
                val nextDay = LocalDate(2026, 1, 16)
                postings.write(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.ById("p"), listOf(posting.copy(title = "Override", date = nextDay))))
                val updated = next().single()
                assertEquals("Override", updated.postingTitle)
                assertEquals(nextDay.toString(), updated.postingDate)
                repo.updateMetadata("r", "Edited", 456, 12.5)
                assertEquals("Edited", next().single().receipt.notes)
                repo.detach("r")
                assertNull(next().single().postingTitle)
                repo.attach("r", "p")
                assertEquals("Override", next().single().postingTitle)
                postings.clear(PostingKey.ById("p"))
                val orphan = next().single()
                assertNull(orphan.postingTitle)
                assertNull(orphan.postingDate)
                repo.delete("r")
                assertTrue(next().isEmpty())
            } finally { watcher.cancelAndJoin(); changes.close() }
        }
    }

    private suspend fun withDatabase(block: suspend (ShillingDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ShillingDatabase.Schema.create(driver).await()
            block(ShillingDatabase(driver))
        } finally { driver.close() }
    }
}
