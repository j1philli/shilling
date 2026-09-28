package com.example.pg

import com.example.pg.db.EventQueries
import java.util.UUID

fun EventQueries.recentIds(limit: Long): List<UUID> = recent(limit).executeAsList().map { it.id }
