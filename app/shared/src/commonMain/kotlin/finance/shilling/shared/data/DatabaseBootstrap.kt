package finance.shilling.shared.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import finance.shilling.shared.db.ShillingDatabase

private val requiredTables = listOf(
    "accounts",
    "categories",
    "schedules",
    "schedule_exceptions",
    "postings",
    "receipts",
    "receipt_files",
    "bookkeeping",
    "change_log"
)

suspend fun ensureLocalSchemaReady(
    driver: SqlDriver,
    logTag: String = "DatabaseBootstrap"
) {
    val log = Logger.withTag(logTag)
    val expectedVersion = ShillingDatabase.Schema.version
    val currentVersion = driver.executeQuery(
        null,
        "PRAGMA user_version;",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0
    ).await()
    val hasRequiredTables = requiredTables.all { table ->
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?;",
            { cursor ->
                cursor.next()
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            1
        ) {
            bindString(0, table)
        }.await()
    }

    if (currentVersion == expectedVersion && hasRequiredTables) return

    val existingTables = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%';",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0
    ).await()
    if (existingTables > 0L) {
        log.e { "Local schema requires migration (currentVersion=$currentVersion, expectedVersion=$expectedVersion, hasRequiredTables=$hasRequiredTables); existing data preserved" }
        error("Local database requires a data-preserving migration before this app version can open it")
    }

    ShillingDatabase.Schema.create(driver).await()
    driver.execute(null, "PRAGMA user_version = $expectedVersion;", 0)
}
