# Desktop memory reduction — October 3, 2026

The final complete workload reduced the observed post-import idle footprint
from **1,049 to 879 MiB** across the four app processes (about **16%**). Normal
navigation finished at **553 MiB**, down from **699 MiB** (about **21%**).
This is a useful reduction, but heavy-import memory remains high.

| Phase | WebContent before | WebContent after | Four-process total before → after |
| --- | ---: | ---: | ---: |
| Initial seeded Home | 390.2 MiB | 382.1 MiB | 493.5 → 480.1 MiB |
| End of second navigation pass | 611.3 MiB | 458.5 MiB | 699.1 → 553.2 MiB |
| Review 10,000 CSV rows | 968.2 MiB | 579.6 MiB | 1,057.5 → 661.8 MiB |
| Immediately after import | 929.8 MiB | 841.9 MiB | 1,019.4 → 924.6 MiB |
| Final idle after returning Home | 959.1 MiB | 796.1 MiB | 1,049.0 → 879.0 MiB |

Totals sum host, WebContent, GPU and Networking footprints collected in sequence
at each phase. They approximate a phase total, not an exact simultaneous peak.
WebContent's reported lifetime peak was about 1.0 GiB before (rounded by `vmmap`)
and 940.4 MiB after. No precise peak-reduction percentage is claimed.

Raw measurements: [baseline](../scripts/perf/results/desktop-memory-baseline-2026-10-03.json)
and [final full workload](../scripts/perf/results/desktop-memory-reduced-2026-10-03.json).
Both completed the same navigation, editors, import and idle sequence using
the same restored synthetic database.

## Changes

The desktop memory investigation found a leak in the Wasm SQLDelight 2.4.0
worker transport. Each SQL request adds a `message` and an `error` listener.
The reply handler removes only the message listener. The error listener retains
the completed coroutine; it can keep request parameters and results reachable.
The leak also means a later worker error can try to resume completed requests.
See the pinned [upstream implementation](https://github.com/sqldelight/sqldelight/blob/2.4.0/drivers/web-worker-driver/src/wasmJsMain/kotlin/app/cash/sqldelight/driver/worker/WorkerWrapper.kt).

`createDatabaseWorker` now provides a request-scoped facade to the existing
SQLDelight driver. Three native listeners dispatch responses by request ID.
Completion, SQL errors, cancellation and failed `postMessage` calls release both
request callbacks. Worker failures clear the pending requests; termination removes
the native listeners. SQLDelight still owns bindings, transactions and query
invalidation. Store5 still owns all application data access. Sync protocols,
storage schemas and CSV parsing are unchanged.

The facade deliberately implements only the listener registration protocol used
by the pinned SQLDelight driver. Recheck it when upgrading that dependency.
Both the production web/desktop entry point and the isolated benchmark use it.

The first candidate removed listener accumulation and lowered navigation/review
footprints, but its complete import run ended at **1.1 GiB**, worse than the
baseline's 959 MiB. Its heap had 219 JavaScript event listeners instead of 43,662.
Fixing that leak alone was therefore insufficient to reduce the complete workload.
The [worker-only candidate measurements](../scripts/perf/results/desktop-memory-fixed-2026-10-03.json)
are retained as evidence, not presented as the final memory result.

The release entry point now filters Kermit debug messages before formatting or
forwarding them. Routine per-entity send/queue diagnostics are debug-level;
connection events and actual send failures retain their existing levels. The
desktop console bridge forwards `info` too and allows only one native IPC request
in flight, with a 128-message queue and an 8,192-character limit per message.
If the queue overflows, oldest entries are replaced and a dropped-count warning
is emitted. Browser console output remains available. These bounds apply to logs,
not sync messages: no entity changes or receipt bytes are dropped.

CSV imports now form batches lazily and write 1,000 rows per batch rather than
200. On web/desktop, each SQL.js transaction commit exports the entire database
to IndexedDB; each import batch has both a row transaction and a version-metadata
transaction. This reduces those import commits from 100 to 20 for 10,000 rows,
while keeping bounded transactions, durable acknowledgements and per-entity P2P
changes. Cancellation/failure can still leave earlier batches committed, as
before; their repository change notification still runs.

An optional SQL probe in the shorter import workload observed 26 database
snapshots totaling 329.7 MiB of cumulative copying, including startup and
non-import writes. That total is allocation work, not simultaneously resident
RAM. The shorter workload settled at 823.8 MiB, compared with 1,126.4 MiB in the
earlier 200-row candidate. These diagnostic runs are separate from the full
screen comparison. The [SQL probe output](../scripts/perf/results/desktop-memory-batch-sql-2026-10-03.json)
records the snapshot counts without retaining row data.

The [native heap count extracts](../scripts/perf/results/desktop-memory-retention-2026-10-03.json)
record the listener observations. The final build retained 226 event listeners
after completion, compared with 43,662 in the baseline. One intermediate bounded-logging run launched
two benchmark instances sharing the fixture database; its result is explicitly
marked invalid and excluded. A separate custom SQL driver experiment did not
improve import memory and was reverted. The earlier short SQL diagnostic also
contains extra phase records from a sampler parsing bug; the final sampler
matches only standalone benchmark markers.

## Measurement method

The screen benchmark uses the existing release `perf-web` fixture: 10,000
postings, 1,000 schedules, 20 accounts, 40 categories and 250 receipt metadata
records. It visits every main destination twice, opens four editors, imports
10,000 CSV rows, then returns Home and idles. A separate process seeds the data.
The same saved WebKit fixture directory is restored before the fixed run, so
both processes start with the same database and settings. Only the isolated
`finance.shilling.perf.memory` sandbox is restored.

The final benchmark uses the default full workload without the SQL probe.
[Source fingerprints](../scripts/perf/results/desktop-memory-reduction-source-2026-10-03.json)
identify the changed transport, batch, logging and fixture files used for it.

The primary metric is WebContent's `vmmap` physical footprint in MiB, including
compressed/swapped allocations. RSS alone cannot demonstrate memory release.
The sampler also records the host, GPU and Networking processes. Their lifetime
peaks must not be added together as a simultaneous peak. Native `heap` summaries
count `WebCore::JSEventListener` objects; they do not provide allocation stacks or
an exact byte breakdown for retained Kotlin objects.

Environment: Mac15,6 with 36 GiB RAM, macOS 27.0 (26A428), Kotlin 2.4.20,
SQLDelight 2.4.0. These are sequential runs on a working Mac, not laboratory
measurements; builds overlapped part of the baseline run. Collection, compression
and rendering affect individual samples. No build overlapped the final full run.
This comparison has one complete baseline and one complete final run, so its
percentages describe these observations rather than a repeat-run distribution.

The benchmark's receipts are metadata-only. It does not establish a reduction
for large image previews or receipt transfers. It also excludes the native CSV
file picker. The remaining WebKit/Compose/Wasm allocations need further
attribution; listener counts alone do not explain every retained byte.

## Regression verification

- Release production web package and isolated Tauri bundles built successfully.
- All 184 JVM tests passed, including multi-batch import completeness, entity
  versions, one P2P change per imported row, and notifications after a later
  batch fails.
- The transport regression tests execute the shipped JS boundary using the
  pinned driver's callback sequence. They cover 10,000 completed requests,
  concurrent/out-of-order responses, BLOB results, SQL errors, cancellation,
  worker errors, message deserialization errors, failed sends and termination.
- Worker persistence tests passed. Migration, rollback, failed durable commit,
  reload, scoped keys and foreign-key checks passed using the SQL.js harness.
- Logging tests cover a 10,000-message burst, queue/IPC bounds, overflow reporting,
  severity preservation, plugin failures and ordinary browser console behavior.
  All eight web tests passed. Run them with `npm run test:web`.
- `just guard-architecture` and `git diff --check` passed.

The benchmark process and owned loopback server were stopped afterward. The
previous isolated benchmark WebKit storage was restored; production Shilling
storage was not used for these runs.
