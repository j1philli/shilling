package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStoreFacadeSnapshotTest {
    @Test
    fun snapshotUsesLatestRecordedVersionAndFallbackForUnversionedEntities() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        val db = ShillingDatabase(driver)
        db.accountQueries.upsert("versioned", "Checking", 100.0, "__local__").await()
        db.accountQueries.upsert("new", "Savings", 200.0, "__local__").await()
        db.changeLogQueries.insert(
            household_id = LOCAL_SPACE_ID,
            change_id = "older",
            entity_type = "ACCOUNT",
            entity_id = "versioned",
            op = "UPSERT",
            timestamp = 100L,
            payload_json = null
        )
        db.changeLogQueries.insert(
            household_id = LOCAL_SPACE_ID,
            change_id = "latest",
            entity_type = "ACCOUNT",
            entity_id = "versioned",
            op = "UPSERT",
            timestamp = 200L,
            payload_json = null
        )

        val snapshot = SyncStoreFacade(db).buildFullStateSnapshot("device-1")
        val changes = snapshot.changes.associateBy { it.entityId }

        assertEquals("latest", changes.getValue("versioned").id)
        assertEquals(200L, changes.getValue("versioned").timestamp)
        assertEquals("snapshot:device-1:ACCOUNT:new", changes.getValue("new").id)
        assertEquals(0L, changes.getValue("new").timestamp)
    }
}
