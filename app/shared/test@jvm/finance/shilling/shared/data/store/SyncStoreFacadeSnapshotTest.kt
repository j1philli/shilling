package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.sync.ChangeOp
import finance.shilling.shared.data.sync.ChangePayload
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncStoreFacadeSnapshotTest {
    private suspend fun openDb(): ShillingDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        return ShillingDatabase(driver)
    }

    @Test
    fun entitiesWrittenBeforeVersionsUseTheirLastChangeLogTime() = runBlocking {
        val db = openDb()
        db.accountQueries.upsert("versioned", "Checking", 100.0, "__local__").await()
        db.accountQueries.upsert("new", "Savings", 200.0, "__local__").await()
        for ((id, timestamp) in listOf("older" to 100L, "latest" to 200L)) {
            db.changeLogQueries.insert(
                household_id = LOCAL_SPACE_ID, change_id = id, entity_type = "ACCOUNT",
                entity_id = "versioned", op = "UPSERT", timestamp = timestamp, payload_json = null
            )
        }

        val changes = SyncStoreFacade(db).buildFullStateSnapshot("device-1").changes.associateBy { it.entityId }

        assertEquals(200L, changes.getValue("versioned").timestamp)
        assertEquals(setOf(Hlc.fromTimestamp(200L, "")), changes.getValue("versioned").versions!!.values.toSet())
        assertEquals(0L, changes.getValue("new").timestamp)
        assertEquals(setOf(Hlc.ZERO), changes.getValue("new").versions!!.values.toSet())
    }

    @Test
    fun snapshotCarriesDeletesMadeWhileOffline() = runBlocking {
        val db = openDb()
        val sync = StoreSyncDeps(db, null, "device-1", object : IdGenerator { override fun newId() = "id" })
        val store = createAccountStore(db, sync)
        val repo = AccountRepository(ChangeNotifier(), store, sync)
        repo.upsert(Account("kept", "Checking", 1.0))
        repo.upsert(Account("gone", "Savings", 2.0))
        repo.delete("gone")

        val changes = SyncStoreFacade(db, accountStore = store, versions = sync.versions)
            .buildFullStateSnapshot("device-1").changes.associateBy { it.entityId }

        assertEquals(ChangeOp.UPSERT, changes.getValue("kept").op)
        val tombstone = changes.getValue("gone")
        assertEquals(ChangeOp.DELETE, tombstone.op)
        assertTrue(tombstone.deletedAt!! > tombstone.versions!!.values.max())
        assertEquals("Savings", (tombstone.payload as ChangePayload.AccountPayload).account.name)
    }

    @Test
    fun snapshotIdsChangeWithTheState() = runBlocking {
        val db = openDb()
        val sync = StoreSyncDeps(db, null, "device-1", object : IdGenerator { override fun newId() = "id" })
        val store = createAccountStore(db, sync)
        val repo = AccountRepository(ChangeNotifier(), store, sync)
        val facade = SyncStoreFacade(db, accountStore = store, versions = sync.versions)
        repo.upsert(Account("a", "Checking", 1.0))
        val first = facade.buildFullStateSnapshot("device-1").changes.single().id
        assertEquals(first, facade.buildFullStateSnapshot("device-1").changes.single().id)
        repo.upsert(Account("a", "Checking", 2.0))
        assertTrue(first != facade.buildFullStateSnapshot("device-1").changes.single().id)
    }
}
