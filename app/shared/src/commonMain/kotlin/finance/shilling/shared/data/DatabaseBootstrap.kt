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

private val tablesToDropOnReset = listOf(
    "receipt_files",
    "receipts",
    "postings",
    "schedule_exceptions",
    "schedules",
    "categories",
    "accounts",
    "expenses",
    "bookkeeping",
    "change_log"
)

private val indexesToDropOnReset = listOf(
    "postings_date_idx",
    "schedules_date_idx"
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
    ).value
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
        }.value
    }

    if (currentVersion == expectedVersion && hasRequiredTables) return

    log.w {
        "Rebuilding local schema (currentVersion=$currentVersion, expectedVersion=$expectedVersion, hasRequiredTables=$hasRequiredTables)"
    }
    for (table in tablesToDropOnReset) {
        driver.execute(null, "DROP TABLE IF EXISTS $table;", 0)
    }
    for (index in indexesToDropOnReset) {
        driver.execute(null, "DROP INDEX IF EXISTS $index;", 0)
    }
    ShillingDatabase.Schema.create(driver).await()
    driver.execute(null, "PRAGMA user_version = $expectedVersion;", 0)
}
