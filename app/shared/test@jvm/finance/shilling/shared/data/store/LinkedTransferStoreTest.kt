package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PreferencesSettings
import finance.shilling.shared.data.*
import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class LinkedTransferStoreTest {
    private class Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = ShillingDatabase(driver)
        val preferences = Preferences.userRoot().node("shilling/tests/${UUID.randomUUID()}")
        val settings = PreferencesSettings(preferences)
        val ids = object : IdGenerator { private var next = 0; override fun newId() = "id-${next++}" }
        val notifier = ChangeNotifier()
        val files = object : ReceiptFileStore {
            override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {}
            override suspend fun read(receiptId: String): ByteArray? = null
            override suspend fun hasFile(receiptId: String) = false
            override suspend fun delete(receiptId: String) {}
            override suspend fun clearAll() {}
            override suspend fun openExternally(receiptId: String, originalName: String) {}
        }
        val graphs = FinanceSpaceGraphs(db, settings, DeviceIdentity(settings, ids), ids, notifier, ReceiptFileStoreFactory { _, _ -> files })
        val transfers = LinkedTransferStore(db, graphs, ids, notifier)
        suspend fun prepare(): Fixture {
            ShillingDatabase.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            graphs.activate("home")
            graphs.current.accounts.upsert(Account("a", "Home", 0.0))
            graphs.activate("business")
            graphs.current.accounts.upsert(Account("b", "Business", 0.0))
            graphs.activate("home")
            return this
        }
        fun transfer(amount: Double = 10.0, destination: String = "b"): LinkedTransfer {
            val key = LinkedTransferKey("space-transfer:test", "home", "business")
            val debit = Posting("${key.linkId}:debit", null, ScheduleType.EXPENSE, "a", LocalDate(2026, 10, 1), amount, key.linkId, "Funding")
            return LinkedTransfer(key, debit, debit.copy(id = "${key.linkId}:credit", type = ScheduleType.INCOME, accountId = destination))
        }
        fun close() { driver.close(); preferences.removeNode() }
    }
    @Test fun retryEditDeleteAndSnapshotsPreserveBothLegsAndSpacePrivacy() = runBlocking {
        val f = Fixture().prepare()
        try {
            val transfer = f.transfer()
            f.transfers.save(transfer)
            f.transfers.save(transfer)
            assertEquals(1, f.graphs.current.postings.getBetween(transfer.debit.date, LocalDate(2026, 10, 2)).size)
            assertEquals(transfer, f.transfers.get(transfer.key))
            f.transfers.save(f.transfer(25.0))
            assertEquals(25.0, f.transfers.get(transfer.key)?.debit?.amount)
            assertEquals(25.0, f.transfers.get(transfer.key)?.credit?.amount)
            val snapshot = f.graphs.current.facade.buildFullStateSnapshot("device")
            assertTrue(snapshot.changes.none { it.entityId == transfer.credit.id })
            assertFailsWith<IllegalArgumentException> { f.graphs.current.postings.delete(transfer.debit.id) }
            assertFailsWith<IllegalStateException> { f.graphs.current.accounts.delete("a") }
            f.transfers.delete(transfer.key)
            f.transfers.delete(transfer.key)
            assertNull(f.transfers.get(transfer.key))
            assertTrue(f.transfers.list(setOf("home", "business")).isEmpty())
        } finally { f.close() }
    }
    @Test fun missingDestinationRollsBackSourceAndSyncMetadata() = runBlocking {
        val f = Fixture().prepare()
        try {
            val invalid = f.transfer(destination = "missing")
            runCatching { f.transfers.save(invalid) }
            assertNull(f.graphs.current.postings.getById(invalid.debit.id))
            assertNull(f.transfers.get(invalid.key))
            assertNull(f.db.latestEntityVersion(finance.shilling.shared.data.sync.EntityType.POSTING, invalid.debit.id, "home"))
        } finally { f.close() }
    }
    @Test fun firstActivationClaimsLegacyDataAndLaterSwitchesRetireEditors() = runBlocking {
        val f = Fixture()
        try {
            ShillingDatabase.Schema.create(f.driver).await()
            f.driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            f.graphs.current.accounts.upsert(Account("old", "Existing books", 42.0))
            f.graphs.current.postings.record(Posting("legacy-post", null, ScheduleType.EXPENSE, "old", LocalDate(2026, 10, 1), 5.0))
            f.graphs.current.receipts.save(Receipt("legacy-receipt", "legacy-post", "legacy", "receipt.jpg", 1))
            f.db.receiptFileQueries.upsert("legacy-receipt", byteArrayOf(1, 2, 3), 3, "image/jpeg", "__local__").await()
            val old = f.graphs.current
            f.graphs.activate("home")
            assertEquals(42.0, f.graphs.current.accounts.getAll()["old"]?.balance)
            assertEquals("legacy-post", f.graphs.current.facade.receiptById("legacy-receipt")?.postingId)
            assertContentEquals(byteArrayOf(1, 2, 3), f.db.receiptFileQueries.selectByReceiptId("legacy-receipt", "home").awaitAsOne().file_bytes)
            assertEquals(1L, f.db.spaceQueries.selectById("home").awaitAsOne().legacy_files)
            assertTrue(old.accounts.getAll().isEmpty())
            assertFalse(old.scope.active)
            val home = f.graphs.current
            f.graphs.activate("business")
            runCatching { home.accounts.upsert(Account("old", "Stale", 9.0)) }
            assertTrue(f.graphs.current.accounts.getAll().isEmpty())
            f.graphs.activate("home")
            assertEquals("Existing books", f.graphs.current.accounts.getAll()["old"]?.name)
        } finally { f.close() }
    }
}
