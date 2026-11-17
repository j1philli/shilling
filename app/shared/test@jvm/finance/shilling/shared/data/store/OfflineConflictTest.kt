package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.*

/**
 * Two devices edit the same household while disconnected, then reconnect and exchange
 * full-state snapshots, as IncomingChangeRouter does when a peer connects. Uses the
 * production graph wiring, so merges take the same space lock as the stores' writers.
 */
class OfflineConflictTest {
    private class Device(val name: String) {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: ShillingDatabase = ShillingDatabase(driver).also { runBlocking { ShillingDatabase.Schema.create(driver).await() } }
        val graph = FinanceSpaceGraph(db, "home", name, object : IdGenerator {
            private var counter = 0
            override fun newId() = "$name-${counter++}"
        }, ChangeNotifier(), object : ReceiptFileStore {
            override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {}
            override suspend fun read(receiptId: String): ByteArray? = null
            override suspend fun openReader(receiptId: String): ReceiptFileReader? = null
            override suspend fun hasFile(receiptId: String) = false
            override suspend fun delete(receiptId: String) {}
            override suspend fun clearAll() {}
            override suspend fun openExternally(receiptId: String, originalName: String) {}
        })

        suspend fun sendSnapshotTo(other: Device) {
            graph.facade.buildFullStateSnapshot(name).changes.forEach { other.graph.facade.applyIncomingChange(it) }
        }

        suspend fun account(id: String) = graph.accounts.getAll()[id]
        fun close() = driver.close()
    }

    private suspend fun reconnect(a: Device, b: Device) {
        a.sendSnapshotTo(b)
        b.sendSnapshotTo(a)
    }

    private fun withDevices(block: suspend (Device, Device) -> Unit) = runBlocking {
        val a = Device("phone")
        val b = Device("laptop")
        try { block(a, b) } finally { a.close(); b.close() }
    }

    /** Keeps "later" edits in a later millisecond so the test reads as wall-clock order. */
    private fun tick() = Thread.sleep(3)

    @Test
    fun editsToDifferentFieldsAreBothKept() = withDevices { a, b ->
        a.graph.accounts.upsert(Account("acct", "Checking", 100.0))
        reconnect(a, b)

        a.graph.accounts.upsert(Account("acct", "Joint checking", 100.0))
        b.graph.accounts.upsert(Account("acct", "Checking", 250.0))
        reconnect(a, b)

        assertEquals(Account("acct", "Joint checking", 250.0), a.account("acct"))
        assertEquals(a.account("acct"), b.account("acct"))
    }

    @Test
    fun theLaterEditToTheSameFieldWinsOnBothDevices() = withDevices { a, b ->
        a.graph.accounts.upsert(Account("acct", "Checking", 100.0))
        reconnect(a, b)

        b.graph.accounts.upsert(Account("acct", "Checking", 300.0))
        tick()
        a.graph.accounts.upsert(Account("acct", "Checking", 120.0))
        reconnect(a, b)

        assertEquals(120.0, a.account("acct")?.balance)
        assertEquals(120.0, b.account("acct")?.balance)
    }

    @Test
    fun anOfflineDeleteReachesThePeerOnReconnect() = withDevices { a, b ->
        a.graph.accounts.upsert(Account("acct", "Checking", 100.0))
        a.graph.categories.upsert(Category("cat", "Groceries"))
        reconnect(a, b)

        a.graph.categories.delete("cat")
        reconnect(a, b)

        assertNull(b.graph.categories.getAll().firstOrNull { it.id == "cat" })
        // The peer's stale copy in its own snapshot doesn't bring it back either.
        reconnect(a, b)
        assertTrue(a.graph.categories.getAll().none { it.id == "cat" })
    }

    @Test
    fun aDeleteBeatsOlderEditsButALaterEditRevivesTheEntity() = withDevices { a, b ->
        a.graph.accounts.upsert(Account("acct", "Checking", 100.0))
        reconnect(a, b)

        b.graph.accounts.upsert(Account("acct", "Checking", 90.0))
        tick()
        a.graph.accounts.delete("acct")
        reconnect(a, b)
        assertNull(a.account("acct"))
        assertNull(b.account("acct"))

        tick()
        b.graph.accounts.upsert(Account("acct", "Checking again", 10.0))
        reconnect(a, b)
        assertEquals(Account("acct", "Checking again", 10.0), a.account("acct"))
        assertEquals(a.account("acct"), b.account("acct"))
    }

    @Test
    fun cascadedDeletesAreNotRevivedByAStalePeer() = withDevices { a, b ->
        val date = LocalDate(2026, 10, 1)
        a.graph.accounts.upsert(Account("acct", "Checking", 0.0))
        a.graph.postings.record(Posting("p", null, ScheduleType.EXPENSE, "acct", date, 4.0, title = "Coffee"))
        reconnect(a, b)

        a.graph.accounts.delete("acct")
        b.sendSnapshotTo(a) // b still has the posting and hasn't heard about the delete
        assertNull(a.graph.postings.getById("p"))

        a.sendSnapshotTo(b)
        assertNull(b.account("acct"))
        assertNull(b.graph.postings.getById("p"))
    }

    @Test
    fun recurrenceRulesAreNeverMixedFromTwoDevices() = withDevices { a, b ->
        val rent = Schedule("rent", "Rent", 1500.0, ScheduleType.EXPENSE, "acct",
            startDate = LocalDate(2026, 1, 1), freq = Frequency.MONTHLY_BY_DAY, byMonthDay = 1)
        a.graph.schedules.upsert(rent)
        reconnect(a, b)

        a.graph.schedules.upsert(rent.copy(freq = Frequency.WEEKLY, byMonthDay = null, byDayMask = 1))
        tick()
        b.graph.schedules.upsert(rent.copy(byMonthDay = 15, amount = 1600.0))
        reconnect(a, b)

        val merged = a.graph.schedules.getAll().single()
        assertEquals(rent.copy(byMonthDay = 15, amount = 1600.0), merged)
        assertEquals(merged, b.graph.schedules.getAll().single())
    }

    @Test
    fun scheduleExceptionsWithUnderscoredScheduleIdsSyncAndDelete() = withDevices { a, b ->
        val schedule = Schedule("rent_home", "Rent", 1500.0, ScheduleType.EXPENSE, "acct",
            startDate = LocalDate(2026, 1, 1), freq = Frequency.MONTHLY_BY_DAY)
        val skip = ScheduleException("rent_home", LocalDate(2026, 2, 1), skip = true)
        a.graph.schedules.upsert(schedule)
        a.graph.schedules.upsertException(skip)
        reconnect(a, b)
        assertEquals(listOf(skip), b.graph.schedules.getExceptions(listOf("rent_home"))["rent_home"])

        b.graph.schedules.removeException("rent_home", skip.date)
        reconnect(a, b)
        assertNull(a.graph.schedules.getExceptions(listOf("rent_home"))["rent_home"])
    }

    @Test
    fun repeatedSnapshotsChangeNothing() = withDevices { a, b ->
        a.graph.accounts.upsert(Account("acct", "Checking", 100.0))
        a.graph.categories.upsert(Category("gone", "Old"))
        a.graph.categories.delete("gone")
        reconnect(a, b)
        val results = a.graph.facade.buildFullStateSnapshot("phone").changes.map { b.graph.facade.applyIncomingChange(it) }
        assertEquals(setOf<ApplyIncomingChangeResult>(ApplyIncomingChangeResult.IgnoredStale), results.toSet())
        assertTrue(a.graph.facade.buildFullStateSnapshot("phone").changes.any { it.entityId == "gone" && it.op == ChangeOp.DELETE })
    }
}
