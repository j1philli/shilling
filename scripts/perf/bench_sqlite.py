#!/usr/bin/env python3
"""Synthetic SQLite microbenchmarks for Shilling's read-path query shapes.

Run with: python3 scripts/perf/bench_sqlite.py
These measure SQLite query cost only, not Store5, SQLDelight, or UI time.
"""

import sqlite3
import statistics
import time


def timed(fn, runs=7):
    samples = []
    for _ in range(runs):
        start = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - start) * 1000)
    return statistics.median(samples)


db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE postings (id TEXT PRIMARY KEY, date INTEGER NOT NULL, title TEXT);
CREATE INDEX postings_date_idx ON postings(date);
CREATE TABLE schedule_exceptions (
  schedule_id TEXT NOT NULL, date INTEGER NOT NULL,
  PRIMARY KEY(schedule_id, date)
);
CREATE TABLE change_log (
  household_id TEXT NOT NULL, change_id TEXT NOT NULL,
  entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
  timestamp INTEGER NOT NULL,
  PRIMARY KEY(household_id, change_id)
);
CREATE INDEX idx_change_log_household_timestamp ON change_log(household_id, timestamp);
""")
db.executemany(
    "INSERT INTO postings VALUES (?, ?, ?)",
    ((f"p{i}", 20000 + (i % 3650), "Transaction") for i in range(100000)),
)
db.executemany(
    "INSERT INTO schedule_exceptions VALUES (?, ?)",
    ((f"s{i % 1000}", 20000 + i) for i in range(10000)),
)
db.executemany(
    "INSERT INTO change_log VALUES (?, ?, ?, ?, ?)",
    (("local", f"c{i}", "POSTING", f"p{i % 1000}", i) for i in range(10000)),
)
db.commit()
schedule_ids = [f"s{i}" for i in range(1000)]
placeholders = ",".join("?" for _ in schedule_ids)


def recent_current():
    return db.execute(
        "SELECT * FROM postings WHERE date >= 0 AND date < ? ORDER BY date ASC",
        (2**63 - 1,),
    ).fetchall()[-20:]


def recent_limited():
    return db.execute(
        "SELECT * FROM postings ORDER BY date DESC LIMIT 20"
    ).fetchall()


def exceptions_current():
    return [
        db.execute(
            "SELECT * FROM schedule_exceptions WHERE schedule_id = ?", (sid,)
        ).fetchall()
        for sid in schedule_ids
    ]


def exceptions_batch():
    return db.execute(
        f"SELECT * FROM schedule_exceptions WHERE schedule_id IN ({placeholders})",
        schedule_ids,
    ).fetchall()


for label, fn in (
    ("recent current full scan + materialize", recent_current),
    ("recent SQL LIMIT 20", recent_limited),
    ("exceptions 1000 individual queries", exceptions_current),
    ("exceptions one IN query", exceptions_batch),
):
    print(f"{label}: {timed(fn):.2f} ms median")

for label, sql, params in (
    ("recent current", "SELECT * FROM postings WHERE date >= 0 AND date < ? ORDER BY date ASC", (2**63 - 1,)),
    ("recent limited", "SELECT * FROM postings ORDER BY date DESC LIMIT 20", ()),
):
    plan = db.execute("EXPLAIN QUERY PLAN " + sql, params).fetchall()
    print(f"{label} plan: {plan}")

latest_sql = """SELECT * FROM change_log
WHERE household_id = ? AND entity_type = ? AND entity_id = ?
ORDER BY timestamp DESC, change_id DESC LIMIT 1"""


def latest_versions():
    return [
        db.execute(latest_sql, ("local", "POSTING", f"p{i}")).fetchone()
        for i in range(1000)
    ]


print(f"latest versions 1000 entities, current index: {timed(latest_versions, 3):.2f} ms median")
print("latest current plan:", db.execute("EXPLAIN QUERY PLAN " + latest_sql, ("local", "POSTING", "p0")).fetchall())
db.execute("""CREATE INDEX idx_change_log_entity_latest ON change_log
(household_id, entity_type, entity_id, timestamp DESC, change_id DESC)""")
print(f"latest versions 1000 entities, composite index: {timed(latest_versions, 3):.2f} ms median")
print("latest composite plan:", db.execute("EXPLAIN QUERY PLAN " + latest_sql, ("local", "POSTING", "p0")).fetchall())
