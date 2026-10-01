package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.*

class FinanceSpaceIsolationTest {
    private fun db(): ShillingDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
        runBlocking { ShillingDatabase.Schema.create(it).await() }
        ShillingDatabase(it)
    }
    private fun graph(db: ShillingDatabase, id: String) = FinanceSpaceGraph(db, id, "device", object : IdGenerator {
        private var counter = 0
        override fun newId() = "$id-${counter++}"
    }, ChangeNotifier(), object : ReceiptFileStore {
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {}
        override suspend fun read(receiptId: String): ByteArray? = null
        override suspend fun hasFile(receiptId: String) = false
        override suspend fun delete(receiptId: String) {}
        override suspend fun clearAll() {}
        override suspend fun openExternally(receiptId: String, originalName: String) {}
    })

    @Test fun equalIdsRemainIsolatedThroughReadsWritesDeletesAndSnapshots() = runBlocking {
        val db = db()
        val home = graph(db, "home")
        val business = graph(db, "business")
        home.accounts.upsert(Account("same", "Home", 10.0))
        business.accounts.upsert(Account("same", "Business", 20.0))
        assertEquals("Home", home.accounts.getAll()["same"]?.name)
        assertEquals("Business", business.accounts.getAll()["same"]?.name)
        assertEquals("Home", (home.facade.buildFullStateSnapshot("device").changes.single().payload as ChangePayload.AccountPayload).account.name)
        home.accounts.delete("same")
        assertTrue(home.accounts.getAll().isEmpty())
        assertEquals(20.0, business.accounts.getAll()["same"]?.balance)
        assertNotEquals(db.latestEntityVersion(EntityType.ACCOUNT, "same", "home"), db.latestEntityVersion(EntityType.ACCOUNT, "same", "business"))
    }

    @Test fun accountCascadesAndReceiptDetachmentStayInsideTheirSpace() = runBlocking {
        val db = db()
        val a = graph(db, "a")
        val b = graph(db, "b")
        val date = LocalDate(2026, 10, 1)
        for (g in listOf(a, b)) {
            g.accounts.upsert(Account("account", g.id, 0.0))
            g.postings.record(Posting("posting", null, ScheduleType.EXPENSE, "account", date, 1.0))
            g.receipts.save(Receipt("receipt", "posting", "file", "file", 1, null, null, null))
        }
        a.accounts.delete("account")
        assertNull(a.postings.getById("posting"))
        assertNotNull(b.postings.getById("posting"))
        assertNull(a.facade.receiptById("receipt")?.postingId)
        assertEquals("posting", b.facade.receiptById("receipt")?.postingId)
    }

    @Test fun receiptPathsRejectTraversalAcrossSpaces() {
        assertEquals("receipt-id", safeReceiptStorageId("receipt-id"))
        for (id in listOf("../other/receipt", "..", "/absolute", "folder\\file", "bad\u0000id")) assertFailsWith<IllegalArgumentException> { safeReceiptStorageId(id) }
    }

    @Test fun retiredEditorCannotWriteToEitherSpace() = runBlocking {
        val db = db()
        val old = graph(db, "old")
        val next = graph(db, "new")
        old.accounts.upsert(Account("account", "Saved", 1.0))
        old.scope.retire()
        runCatching { old.accounts.upsert(Account("account", "Stale editor", 2.0)) }
        assertEquals("Saved", old.accounts.getAll()["account"]?.name)
        assertTrue(next.accounts.getAll().isEmpty())
    }
}
