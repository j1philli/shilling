package com.example.sample

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.sample.db.SampleDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class SampleDatabaseTest {
    @Test
    fun generatedQueriesRoundTrip() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SampleDatabase.Schema.create(driver)
        val queries = SampleDatabase(driver).playerQueries

        queries.insert(1, "Ada", 42)
        queries.insert(2, "Grace", 7)

        assertEquals(listOf("Ada", "Grace"), queries.topScorers(limit = 10).executeAsList().map { it.name })
        assertEquals(7L, queries.scoreByName("Grace").executeAsOne())
    }
}
