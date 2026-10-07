# Desktop startup memory — October 6, 2026

This follows the [WebContent allocation audit](desktop-webcontent-allocation-2026-10-06.md)
on public main `a8412d0`. It separates application startup into database, Koin,
session, minimal Compose, Home projection and Home rendering stages.

The investigation found full-database exports during unchanged schema setup and
read-only Home/Activity queries.
An unchanged database still received `CREATE INDEX IF NOT EXISTS` requests and
`PRAGMA foreign_keys = ON`; the web worker conservatively classified them as
writes and scheduled persistence. The fix checks for the indexes before issuing
DDL and excludes the specific connection-only foreign-key assignments from
persistence. This removes one startup snapshot and lowers the observed
database-initialization peak by **17.8 MiB**, with no meaningful Home idle change.

A separate attempt to stop exports for read-only `WITH` queries reduced copying
but increased post-import memory in both trials. **That experiment is not shipped.**
The production worker retains its conservative CTE handling. The runtime's large
baseline allocation remains.

## What the stages show

All numbers below are WebContent physical footprint in MiB. The three diagnostic
runs use the same Wasm, imports, worker and probes; only the workload selector
changes. The first two restore the original seed. The third restores the first
run's stopped, initialized sandbox, with the same financial records.

| Checkpoint | Database first, initial seed | Compose first, initial seed | Database first, initialized seed |
| --- | ---: | ---: | ---: |
| Kotlin instantiated | 129.9 | 121.4 | 129.2 |
| App entry, no database or Compose | 130.2 | 121.6 | 129.4 |
| Database/schema ready | 143.9 | 165.1 | 155.0 |
| Koin configured | 144.4 | 166.0 | 155.4 |
| Session ready | 175.0 | 201.4 | 157.4 |
| Minimal Compose text | 195.6 | 145.6 (before database) | 180.9 |
| Home projection ready, not rendered | 224.6 | 213.5 | 221.2 |
| Home rendered | 244.3 | 228.7 | 240.9 |
| Final idle | 231.5 | 228.7 | 229.1 |

Minimal Compose increases footprint by 20.6–24.0 MiB relative to the preceding
stage in its actual execution order. Home projection increases it by
12.1–40.3 MiB, and Home rendering by 15.2–19.7 MiB. Collection timing matters:
footprint drops by roughly 12 MiB during two of the final idle intervals.
These differences are **not independent retained costs** for the named libraries
or lists, and they should not be summed into an allocation ownership model.

The diagnostic creates one Home view model, waits for its expected data, then
passes that same model to the production `HomeView`. It verifies 20 accounts,
40 categories, 1,000 schedules, 250 receipts, three recent activity rows,
1,000 budget lines and a nonempty upcoming window before the projection
checkpoint. It omits the regular navigation scaffold and app surface.
The visible Home totals were also checked in the native window.

A separate full-app control uses the initialized seed and the ordinary `idle`
workload after the module checkpoints. It opens Home, reselects the Home tab
once, then idles. It finishes at **238.9 MiB WebContent / 309.0 MiB whole app**.
Its layout, navigation and collection history differ from the isolated Home
runs; their difference is not a measured unique cost of the scaffold.

## First-session migration was part of the old seed

The original seed is captured immediately after `seed_complete_restart` and
still belongs to `__local__`. The first session's existing Store5 space activation
claims those rows into the active space. SQL diagnostics show the transaction
and scoped-key updates. The SQLite snapshot grows from 6,311,936 to 9,314,304
bytes, while financial values remain identical.

With that first-use work included, the session stage adds 30.6–35.4 MiB and
issues 14 additional SQL requests. With the initialized seed, it adds only
**2.0 MiB and zero SQL requests**. The final isolated Home footprints nonetheless
converge to 228.7–231.5 MiB. Migration changes the work and allocation sequence
more than the observed final idle total.

The initialized full-app control makes 47 SQL requests and returns 3,179 rows,
versus 110 requests and 6,348 rows in the earlier first-session full-app controls.
The large row counts are primarily schedule reads, not loading all 10,000
postings into Home. No additional SQL requests occur during idle.

Earlier matched optimization comparisons restored the same original seed on
both sides; this does not invalidate their matched workloads. It does mean
those startup measurements included first-session work. The perf README now
explains how to prepare and preserve an initialized seed for ordinary startup.

## Removing the schema snapshot

`DatabaseBootstrap` now queries index existence before issuing the same
race-safe `CREATE INDEX IF NOT EXISTS` commands. Existing indexes require no
DDL; missing indexes still get created. SQLite documents that the existing-index
case is a [no-op](https://www.sqlite.org/lang_createindex.html).

The SQL.js worker still executes `PRAGMA foreign_keys = ON/OFF/0/1`, but it no
longer marks the database dirty solely for these connection settings. Enforcement
remains enabled normally and after export. This follows SQLite's
[connection-level foreign-key behavior](https://www.sqlite.org/pragma.html#pragma_foreign_keys).
Other writes remain conservative. Parenthesized persistent pragma assignments,
such as `user_version(4)`, now also trigger persistence; multi-statement writes
are not exempted by an initial foreign-key pragma.

The shipped, schema-only candidate removes the schema export. In its matched
initialized-seed comparison, the peak through database initialization falls from
193.1 to 175.3 MiB. Settled database footprint stays at 155.0–155.3 MiB, final Home
idle stays at 228.4–229.1 MiB, and the later Home peak is unchanged at
264.5–265.2 MiB. Removing this copy helps the database-init peak, without a
material idle or overall-peak improvement in that comparison. The final source
worker is byte-identical to this measured candidate after removing fixture-only
instrumentation and restoring the production database name.

## Rejected CTE export experiment

A remaining export occurs when Home reads recent activity. That query begins with
`WITH`, which the conservative worker does not recognize as a read. The long-session
schema-only control performs eight full snapshots (71.1 MiB total) before any
import, then finishes with 30 snapshots totaling 360.1 MiB. Those counters count
exports, including copies associated with actual committed writes.

The first experimental worker checks `total_changes()` before and after a single
`WITH` statement. SQLite permits [CTEs before SELECT or DML](https://www.sqlite.org/lang_with.html),
so the worker must not classify every `WITH` as read-only. A second variant adds
a conservative DML guard before using the counter: any occurrence of
`INSERT`, `UPDATE`, `DELETE` or `REPLACE` as a word keeps the statement on the
conservative write path, even inside comments or literals. That can over-save
an unusual read, but it cannot exempt a CTE write. This matters because an ignored
`AUTOINCREMENT` insert can advance `sqlite_sequence` without increasing
`total_changes()`. A regression test verifies that this sequence update survives
persistence; see SQLite's [AUTOINCREMENT behavior](https://www.sqlite.org/autoinc.html).
A changed counter for a read candidate also requires persistence. SQLite's
[counter includes trigger and foreign-key changes](https://www.sqlite.org/c3ref/total_changes.html).
The before/after calls and the statement run synchronously on the worker's
serialized connection, so an export cannot reset the counter between them.
Uncertain counter values and multi-statement SQL still persist conservatively.
Two small internal counter reads are added per eligible CTE; these are not
separate SQLDelight worker requests in the request counters.

The CTE startup candidate removes the remaining read-triggered export: the
initialized database reaches Home and idle with **zero snapshots and zero
commits**, versus two snapshots / 17.8 MiB copied in the original initialized
baseline. Its Home peak falls to **246.3 MiB**, versus 264.5 MiB originally and
265.2 MiB with the schema-only change. Final idle is **228.4 MiB**, still within
the 228.4–229.1 MiB range of those controls. This is a transient-allocation
improvement, not evidence of a substantial idle reduction.

The longer `csv-session` comparison reverses the apparent benefit. It opens four
10,000-row reviews, verifies edits survive tab switches, cancels three, imports
the fourth, then idles for three minutes. Every run starts with the same stopped,
initialized seed. All fixture assets match except the worker.

| Variant | Exports | Exported MiB | Final WebContent MiB | WebContent lifetime peak MiB |
| --- | ---: | ---: | ---: | ---: |
| Schema-only, shipped | 30 | 360.1 | 422.1 | 516.3 |
| CTE counter experiment | 21 | 272.9 | 444.0 | 538.2 |
| CTE experiment with conservative DML guard | 21 | 272.7 | 446.7 | 564.8 |

Both experiments eliminate the eight pre-import snapshots and reduce cumulative
copying by **87.2–87.4 MiB / 24.2–24.3%**, but finish **21.9–24.6 MiB higher** in WebContent
footprint. Their import peaks are also higher. The difference is primarily in
`WebKit Malloc`; this does not identify retained objects or establish a leak.
Collection timing and allocator behavior may contribute, but these measurements
do not prove the mechanism. Neither experiment is accepted as a memory reduction.

The shipped worker remains the schema-only control. The CTE variants, their exact
fixture worker sources, and the guarded experiment's regression tests are
preserved as research evidence. No CTE classifier or row-counter calls are added
to production. The worker tests retain the persistent zero-row-count sequence
case to catch an unsafe future optimization.

Snapshot byte counts measure copying work, not retained or peak RAM. The shipped
change does not eliminate subsequent application writes or replace IndexedDB persistence.
User records continue through Store5, and device synchronization remains WebRTC
P2P. The production optimizer and runtime settings are unchanged.

## Verification and limits

All nine native runs complete with **126 visible, 1200 × 900 / DPR 1
checkpoints** and 731 unlocked-screen samples. Every stopped
database passes SQLite integrity and foreign-key checks. The six startup-only
runs retain 10,000 postings; all three session runs finish with 20,000, including
10,000 unique imported rows and their version records. Financial-value hashes
match within each group, and all retain 20 accounts, 40 categories,
1,000 schedules and 250 receipts. Each session verifies all four review models
close. The original benchmark sandbox is restored byte-for-byte afterward.

`npm run test:web` passes all 13 tests. The worker regression checks exercise
connection enforcement without exports, persisted schema-version assignments
in both SQL forms, a write after a connection pragma, transaction durability,
rollback, concurrent persistence, reopening, and snapshot-buffer cleanup.
The final regression checks also cover ordinary and recursive CTE results,
CTE INSERT/UPDATE/DELETE, INSERT RETURNING, replacement, INSTEAD OF trigger-backed
writes, ignored AUTOINCREMENT inserts, rollback, and a persistent pragma following a CTE read.
`just test` passes the architecture guard, 157 shared JVM tests and 33 server JVM
tests. The shared bootstrap tests verify no DDL on an up-to-date database,
restoration of missing indexes, fresh initialization and data-preserving migration.
The unsigned production `Shilling.app` and diagnostic bundles build successfully.
Mobile UI suites were not rerun.

The Mac is a Mac15,6 with 36 GiB RAM and macOS 27.0.1 (26A434), in dark appearance.
All compared runs use the same 1200 × 900 viewport at DPR 1. Each run starts fresh
host/WebContent/GPU/Networking processes. Build and test work finishes before
native measurement resumes. Other user applications remain open on the shared
Mac. Whole-app values sum sequential process snapshots, and host footprint varies
between runs; they are approximate totals. Process lifetime peaks are not summed.

Module loading alone still reaches approximately 122–130 MiB before application
entry. Home's final allocator category includes runtime and application objects,
unused capacity and collection effects. The measurements do not count live Kotlin
objects or prove a WebKit leak. Kotlin WasmGC objects are allocated on the engine's
GC heap, separate from Wasm linear memory; WebKit describes that distinction and
its allocation implementation in its [WasmGC discussion](https://webkit.org/blog/17899/introducing-the-jetstream-3-benchmark-suite/).
No forced collection, private runtime tuning or system-protection changes are used.

After the CTE session's clean measurement completed, the native Home screen was
checked visually. Command–Option–I and the context menu did not expose Web
Inspector in this unsigned release fixture. No heap snapshot was obtained.
The attempt occurred after `COMPLETE`, outside the benchmark interval, and the
fixture was stopped before the final build and repeat.

## Evidence

The [phase summary](../scripts/perf/results/desktop-startup-summary-2026-10-06.json)
contains all nine runs. The [validation record](../scripts/perf/results/desktop-startup-validation-2026-10-06.json)
contains screen/viewport checks, database integrity and financial-value hashes,
test counts, and sandbox-restoration checks. The [provenance record](../scripts/perf/results/desktop-startup-provenance-2026-10-06.json)
records source/artifact hashes, the baseline revision, and exact experimental
worker sources. The [native Inspector observation](../scripts/perf/results/desktop-startup-inspector-2026-10-06.json)
records the separate, unsuccessful heap-inspection attempt.

Full native profiles are saved alongside them as `desktop-startup-*-2026-10-06.json`:
`db-first-initial`, `compose-first-initial`, `db-first-initialized`,
`app-initialized`, `schema-startup`, `schema-session`, `cte-startup`,
`cte-session`, and `cte-guarded-session`. Each includes RSS samples, phase
footprints, native category breakdowns and lightweight SQL/runtime diagnostics.
