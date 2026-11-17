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
    driver.execute(null, "PRAGMA foreign_keys = ON;", 0).await()
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

    val hasSpaceColumn = driver.executeQuery(
        null, "SELECT COUNT(*) FROM pragma_table_info('accounts') WHERE name = 'space_id';",
        { cursor -> cursor.next(); QueryResult.Value((cursor.getLong(0) ?: 0L) == 1L) }, 0
    ).await()
    val hasSpaceRegistry = driver.executeQuery(null, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='local_spaces';",
        { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) == 1L) }, 0).await()
    if (currentVersion == expectedVersion && hasRequiredTables && hasSpaceColumn && hasSpaceRegistry) {
        installProjectionIndexes(driver)
        return
    }

    // Native drivers may update user_version before this bootstrap runs. Recognize
    // the exact legacy layout rather than interpreting that bump as a migration.
    if (hasRequiredTables && !hasSpaceColumn && currentVersion in 0L..expectedVersion) {
        val legacyColumns = mapOf(
            "accounts" to "id,name,balance", "categories" to "id,name,color",
            "schedules" to "id,title,amount,type,account_id,counter_account_id,category_id,start_date,end_date,freq,interval_,by_day_mask,by_month_day,nth_weekday,last_day_flag,auto_pay,notes",
            "schedule_exceptions" to "schedule_id,date,skip,override_amount,override_account_id,override_counter_account_id",
            "postings" to "id,schedule_id,type,account_id,date,amount,pair_id,title,category_id",
            "receipts" to "id,posting_id,file_path,original_name,added_at,receipt_date,amount,notes",
            "receipt_files" to "receipt_id,file_bytes,size_bytes,mime_type", "bookkeeping" to "entity_type,entity_id,timestamp",
            "change_log" to "household_id,change_id,entity_type,entity_id,op,timestamp,payload_json"
        )
        val recognized = legacyColumns.all { (table, columns) ->
            driver.executeQuery(null, "SELECT group_concat(name, ',') FROM pragma_table_info('$table');",
                { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0) == columns) }, 0
            ).await()
        }
        check(recognized) { "Unrecognized legacy database; existing data preserved" }
        driver.execute(null, "PRAGMA foreign_keys = OFF;", 0).await()
        try {
            ShillingDatabase(driver).transaction {
                ShillingDatabase.Schema.migrate(driver, 1L, expectedVersion).await()
                val validReferences = driver.executeQuery(null, "PRAGMA foreign_key_check;",
                    { cursor -> QueryResult.Value(!cursor.next().value) }, 0).await()
                check(validReferences) { "Legacy references require repair; existing data preserved" }
                driver.execute(null, "PRAGMA user_version = $expectedVersion;", 0).await()
            }
        } finally {
            driver.execute(null, "PRAGMA foreign_keys = ON;", 0).await()
        }
        installProjectionIndexes(driver)
        log.i { "Migrated existing finance data to space-scoped keys" }
        return
    }

    val existingTables = driver.executeQuery(
        null,
        // SQLiteOpenHelper creates android_metadata before opening a new app database.
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata';",
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
    driver.execute(null, "PRAGMA user_version = $expectedVersion;", 0).await()
}

private suspend fun installProjectionIndexes(driver: SqlDriver) {
    installIndexIfMissing(driver, "idx_change_log_entity_latest", "CREATE INDEX IF NOT EXISTS idx_change_log_entity_latest ON change_log(" +
        "household_id, entity_type, entity_id, timestamp DESC, change_id DESC);")
    installIndexIfMissing(driver, "postings_pair_idx", "CREATE INDEX IF NOT EXISTS postings_pair_idx ON postings(space_id, pair_id);")
}

private suspend fun installIndexIfMissing(driver: SqlDriver, name: String, sql: String) {
    // Avoid a no-op DDL request: the web worker conservatively persists writes,
    // so even CREATE INDEX IF NOT EXISTS would copy the whole SQLite database.
    val exists = driver.executeQuery(
        null, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?;",
        { cursor -> cursor.next(); QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L) }, 1
    ) { bindString(0, name) }.await()
    if (!exists) driver.execute(null, sql, 0).await()
}
