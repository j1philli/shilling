package finance.shilling.shared.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseBootstrapTest {
    @Test
    fun androidMetadataAloneDoesNotMakeAFreshDatabaseRequireMigration() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE android_metadata (locale TEXT);", 0)
            ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")
            assertEquals(ShillingDatabase.Schema.version, userVersion(driver))
            assertTrue(tableExists(driver, "accounts"))
            assertTrue(tableExists(driver, "android_metadata"))
        } finally { driver.close() }
    }

    @Test
    fun emptyDatabaseIsInitialized() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")

        assertEquals(ShillingDatabase.Schema.version, userVersion(driver))
        assertTrue(tableExists(driver, "accounts"))
        assertTrue(tableExists(driver, "change_log"))
    }

    @Test
    fun validSchemaIsLeftIntact() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version};", 0)
        val db = ShillingDatabase(driver)
        db.accountQueries.upsert("acct-1", "Checking", 123.45, space_id = "__local__").await()

        ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")

        assertEquals(1L, rowCount(driver, "accounts"))
    }

    @Test
    fun missingPerformanceIndexIsAddedWithoutRebuildingData() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version};", 0)
        driver.execute(null, "DROP INDEX idx_change_log_entity_latest;", 0)
        driver.execute(null, "DROP INDEX postings_pair_idx;", 0)
        val db = ShillingDatabase(driver)
        db.accountQueries.upsert("acct-1", "Checking", 123.45, "__local__").await()

        ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")

        assertTrue(indexExists(driver, "idx_change_log_entity_latest"))
        assertTrue(indexExists(driver, "postings_pair_idx"))
        assertEquals(1L, rowCount(driver, "accounts"))
    }

    @Test
    fun versionMismatchPreservesExistingData() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version - 1};", 0)
        val db = ShillingDatabase(driver)
        db.accountQueries.upsert("acct-1", "Checking", 123.45, space_id = "__local__").await()

        assertFailsWith<IllegalStateException> {
            ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")
        }

        assertEquals(ShillingDatabase.Schema.version - 1, userVersion(driver))
        assertEquals(1L, rowCount(driver, "accounts"))
    }

    @Test
    fun missingRequiredTablePreservesExistingData() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version};", 0)
        val db = ShillingDatabase(driver)
        db.accountQueries.upsert("acct-1", "Checking", 123.45, space_id = "__local__").await()
        driver.execute(null, "DROP TABLE change_log;", 0)

        assertFailsWith<IllegalStateException> {
            ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")
        }

        assertEquals(1L, rowCount(driver, "accounts"))
    }

    @Test
    fun publishedLegacySchemaMigratesWithoutLosingRecordsOrReceiptBytes() = runBlocking {
        for (version in listOf(1L, ShillingDatabase.Schema.version)) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            legacySchemaSql.split(';').filter { it.isNotBlank() }.forEach { driver.execute(null, it, 0) }
            driver.execute(null, "INSERT INTO accounts VALUES ('account', 'Checking', 123.45);", 0)
            driver.execute(null, "INSERT INTO postings VALUES ('posting', NULL, 'EXPENSE', 'account', 1, 5.0, NULL, 'Coffee', NULL);", 0)
            driver.execute(null, "INSERT INTO receipts VALUES ('receipt', 'posting', 'legacy/path', 'receipt.jpg', 1, NULL, NULL, NULL);", 0)
            driver.execute(null, "INSERT INTO receipt_files VALUES ('receipt', X'010203', 3, 'image/jpeg');", 0)
            driver.execute(null, "PRAGMA user_version = $version;", 0)
            ensureLocalSchemaReady(driver)
            val db = ShillingDatabase(driver)
            assertEquals(123.45, db.accountQueries.selectById("account", "__local__").awaitAsOne().balance)
            assertEquals("posting", db.receiptQueries.selectById("receipt", "__local__").awaitAsOne().posting_id)
            assertEquals(3, db.receiptFileQueries.selectByReceiptId("receipt", "__local__").awaitAsOne().file_bytes.size)
            assertEquals(ShillingDatabase.Schema.version, userVersion(driver))
            ensureLocalSchemaReady(driver)
            driver.close()
        }
    }

    @Test
    fun invalidLegacyReferencesRollbackMigration() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        legacySchemaSql.split(';').filter { it.isNotBlank() }.forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA foreign_keys = OFF;", 0)
        driver.execute(null, "INSERT INTO receipts VALUES ('receipt', 'missing', 'file', 'file', 1, NULL, NULL, NULL);", 0)
        driver.execute(null, "PRAGMA user_version = 1;", 0)
        assertFailsWith<IllegalStateException> { ensureLocalSchemaReady(driver) }
        assertEquals(1L, rowCount(driver, "receipts"))
        assertEquals(1L, userVersion(driver))
        assertTrue(!tableExists(driver, "local_spaces"))
        driver.close()
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            null,
            "PRAGMA user_version;",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0
        ).value

    private fun tableExists(driver: SqlDriver, tableName: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?;",
            { cursor ->
                cursor.next()
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            1
        ) {
            bindString(0, tableName)
        }.value

    private fun indexExists(driver: SqlDriver, indexName: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?;",
            { cursor ->
                cursor.next()
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            1
        ) {
            bindString(0, indexName)
        }.value

    private fun rowCount(driver: SqlDriver, tableName: String): Long =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $tableName;",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0
        ).value
}
