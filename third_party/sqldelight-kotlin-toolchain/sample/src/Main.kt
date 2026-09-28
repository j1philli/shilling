package com.example.sample

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.sample.db.SampleDatabase

fun main() {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    SampleDatabase.Schema.create(driver)
    val queries = SampleDatabase(driver).playerQueries
    queries.insert(1, "Ada", 42)
    queries.insert(2, "Grace", 7)
    queries.topScorers(limit = 10).executeAsList().forEach { println("${it.name}: ${it.score}") }
}
