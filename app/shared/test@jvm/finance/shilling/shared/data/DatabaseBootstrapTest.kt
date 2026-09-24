package finance.shilling.shared.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseBootstrapTest {
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
        db.accountQueries.upsert("acct-1", "Checking", 123.45)

        ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")

        assertEquals(1L, rowCount(driver, "accounts"))
    }

    @Test
    fun versionMismatchRebuildsSchema() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version - 1};", 0)
        val db = ShillingDatabase(driver)
        db.accountQueries.upsert("acct-1", "Checking", 123.45)

        ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")

        assertEquals(ShillingDatabase.Schema.version, userVersion(driver))
        assertEquals(0L, rowCount(driver, "accounts"))
    }

    @Test
    fun missingRequiredTableRebuildsSchema() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version};", 0)
        val db = ShillingDatabase(driver)
        db.accountQueries.upsert("acct-1", "Checking", 123.45)
        driver.execute(null, "DROP TABLE change_log;", 0)

        ensureLocalSchemaReady(driver, logTag = "DatabaseBootstrapTest")

        assertTrue(tableExists(driver, "change_log"))
        assertEquals(0L, rowCount(driver, "accounts"))
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
