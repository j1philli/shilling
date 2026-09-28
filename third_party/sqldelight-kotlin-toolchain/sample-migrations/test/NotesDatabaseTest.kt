package com.example.migrations

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.migrations.db.NotesDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class NotesDatabaseTest {
    @Test
    fun schemaIsDerivedFromMigrations() {
        assertEquals(3L, NotesDatabase.Schema.version)
    }

    @Test
    fun migratesExistingDatabase() {
        // A database created by 1.sqm, i.e. at version 2.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE note (id INTEGER NOT NULL PRIMARY KEY, body TEXT NOT NULL)", 0)
        driver.execute(null, "INSERT INTO note(id, body) VALUES (1, 'old')", 0)

        NotesDatabase.Schema.migrate(driver, oldVersion = 2, newVersion = NotesDatabase.Schema.version)

        val queries = NotesDatabase(driver).noteQueries
        queries.insert(2, "new", 1)
        assertEquals(listOf("new"), queries.pinned().executeAsList())
    }
}
