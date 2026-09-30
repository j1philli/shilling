> **Scope correction:** This audit was performed on the private-based worktree at
> `73ace52`, tracking `origin/main` (`j1philli/shilling-private`). It is **not** a
> benchmark of public `j1philli/shilling` main. Public main checked at `d12fe9a`
> retains several affected core implementations, but has different UI, startup
> wiring and Ktor dependencies. Port fixes selectively and rerun measurements
> on public main before using these numbers as public-release performance claims.

# Performance audit — 2026-09-28

Follow-up: [September 29 receipt transfer improvements](transfer-improvements-2026-09-29.md)
adds Ktor 3.6.0 and mandatory binary chunks, with paired physical measurements.

Follow-up: [September 29 server audit](server-performance-2026-09-29.md) measures
hosted authentication, slow/failing upstreams and signaling reconnects, and fixes
JWKS stampedes, blocking HTTP calls, retained household maps and stalled-peer
blocking. [Physical iPhone profiling](iphone-performance-2026-09-29.md) covers
the native receiver's connected-idle CPU and immediate-request delivery.

Follow-up: [September 29 Pixel populated-screen profile](pixel-ui-performance-2026-09-29.md)
measures cold History and Weekly rendering and scrolling with synthetic Store5
data, and retains the History projection dispatcher change.

Follow-up: [September 29 receipt peak-memory profile](receipt-memory-2026-09-29.md)
measures physical Pixel 50 MiB transfers in both directions and removes
intermediate chunk copies from the binary transfer path.

## Follow-up fixes

The [receipt-list projection profile](receipt-list-performance-2026-09-29.md)
replaces whole posting/schedule list materialization with an existing SQL join
inside Store5. It measures both the initial-list/posting-refresh gains and the
schedule/metadata edit tradeoff at 250 and 1,000 receipts on the Pixel.

The first three query and persistence findings have been addressed in this
worktree. The web worker now saves only after mutations and committed transactions,
keeps at most one IndexedDB write in flight, and waits for transaction completion.
Recent postings use a SQL `LIMIT` inside the Store5 source of truth. Full-state
snapshots perform one version lookup per entity, backed by a matching index that is
installed on existing native databases without rebuilding entity data. The
benchmarks below are the original audit baselines; end-to-end gains still need
release-build profiling.

## Latest: Pixel startup and synthetic workloads

### Startup diagnosis and fix

System tracing identified a 457 ms first frame in the debug app, with 255 ms in
Compose measurement. DEX loading/verification consumed another 150 ms before
activity startup. The debug APK contained about 57 MB of DEX without packaged
baseline profiles. The existing release build shrinks that to 5.4 MB and packages
baseline profiles. Using the optimized build reduced the five-run cold-process
launch median from **712 ms to 259 ms**, and the traced first frame to **96 ms**.
This is primarily a debug-versus-release comparison, not a code-change speedup.

The code change defers HTTP-client/plugin construction until networking is first
needed, allowing the welcome screen to open without eager Ktor setup. The first
five release launches after this change were 337/249/259/248/243 ms (median
**249 ms**). One system trace reduced `performCreate` from 60.4 ms to 45.4 ms;
the first frame remained about 96 ms. This small timing difference needs more
samples before claiming a stable improvement. No artificial splash-screen-only
"ready" milestone was introduced.

`scripts/perf/profile_android_startup.sh` now builds, locally signs, installs in
place, and measures the release app. It preserves existing application data. The
final script run recorded 288/244/244/251/256 ms, with a **251 ms median**.

### Physical Pixel synthetic workloads

Added `app/perf-android`, a separate offline application (`finance.shilling.perf`)
with its own disposable database and files. It uses the production Store5
repositories and the production Android SQLite driver, now shared under
`app/shared/src@android/.../data/store/`. It never reads or clears the user's
Shilling database. The fixture contains 11,000 postings, 1,000 weekly schedules,
and 1/10/50 MiB synthetic receipt files. Offline entity-version metadata is
included. Logging below Error is disabled consistently for baseline and fixed
runs. Results are data-layer/projection and receipt-processing measurements;
they do not measure scrolling, image preview, or actual WebRTC network throughput.

| Workload | Baseline run | Fixed runs | Result |
| --- | ---: | ---: | --- |
| Import 1,000 postings | 979 ms | 29.6 / 44.1 / 28.2 ms | Median 29.6 ms |
| Import 10,000 postings | 10,278 ms | 685 / 586 / 586 ms | Median 586 ms |
| 50 MiB receipt round trip | OutOfMemoryError at receive completion (256 MiB heap limit) | Completed in all three runs, byte-for-byte verified | Allocation failure resolved in this fixture |

For the fixed 50 MiB workload, prepare plus receive took approximately
389/366/438 ms. Lazy Base64 encoding now happens during iteration, so comparing
prepare time alone would be misleading. Sender, receiver, and verification bytes
coexist in this single-process stress test. Post-receive Java heap snapshots were
about 210/225/152 MiB; substantial memory remains in use. Snapshot measurements
are not peak-memory measurements. History and weekly query timings varied; no
query-speed improvement is claimed from these runs.

Changes driven by these measurements:

- Import through Store5 in batches of 200, with transactional posting writes and
  transactional version metadata. Each entity still gets its own P2P change;
  no database transaction spans network sending. Notify projections even when
  a later batch fails or the import is cancelled after earlier commits.
- Parse CSV and run imports off the native UI thread. UI state stays on the UI
  thread. Browser WASM remains single-threaded; bounded batches yield between
  writes. CSV header/preview scans now stop after the needed lines.
- Generate receipt messages lazily and encode directly from the source buffer,
  rather than retaining a list of all Base64 chunks.
- Receive into one validated buffer with a bounded total receive allocation,
  rather than retaining chunks and allocating another full file at completion.
- Apply WebRTC buffered-amount backpressure (256 KiB high-water mark, 30-second
  stall limit), reduce inbound file-message buffering, and abort interrupted
  payload sends instead of retaining whole encoded files in disconnected queues.
  Existing P2P request retries handle reconnection. These network changes compile
  on Android/iOS/WASM but still need a real two-device slow-link/reconnect profile.

Regression checks: 96 JVM tests pass, including batch contents, per-entity
broadcasts/version metadata, notification after partial failure, reordered and
duplicate receipt chunks, malformed chunks, and full transfer round trips.
Android, iOS ARM64 and WASM compilation passed; the optimized Android app and
benchmark APK were built and exercised. Architecture and web persistence checks
pass. Repeat commands and limitations are documented in `scripts/perf/README.md`;
raw synthetic records are in `scripts/perf/results/pixel6-2026-09-28.json`.

## Second pass: Store5 and native profiling

Reviewed production SQL query calls, file persistence, dependency wiring, UI
collectors, and sync entry points in addition to running the architecture guard.
Receipt bytes previously bypassed Store5 in all three platform implementations.
They now use `Store5ReceiptFileStore`: its source of truth owns reads, writes,
presence checks, deletion, and clear-all. Native backends are private and execute
on `Dispatchers.IO`; web/desktop SQL lives under `data/store/`. Blob caching is
disabled, and checking presence does not read the blob. Account, category,
schedule, exception, posting, receipt metadata, and sync snapshot/application
paths continue through their existing Store5 stores. Schema setup, Store5
bookkeeping/change-version metadata, authentication, and platform settings retain
their infrastructure APIs. File-picker input and external-viewer temporary files
are platform I/O, not additional entity persistence paths.

Additional fixes:

- Batch schedule exception reads in groups of 400 distinct IDs through Store5,
  avoiding one query per schedule and native SQLite parameter limits.
- Use the indexed category-by-ID query instead of reading every category.
- Remember Compose flow instances with their query inputs, avoiding subscription
  restarts caused by recomposition.
- Track receipt-transfer completion with the existing received-chunk count,
  avoiding a full chunk-array scan after each arrival.
- Replace iOS delay polling with a cancellable coroutine suspended on a GCD timer.
  This preserves the native timer workaround without repeated dispatcher hops.

Validation: `./scripts/ci/run-jvm-tests.sh` passed 92 tests;
`node app/web-app/test/worker-persistence.test.cjs` passed;
`just guard-architecture` and `git diff --check` passed. Android debug and iOS
ARM64 debug compilation and the WASM debug app build passed. These are build and
correctness checks, not post-fix device performance measurements.

### What has actually been profiled on phones

**iPhone SE, iOS 18.7.8:** captured a 15.5-second physical-device Time Profiler
launch trace of the already-installed debug app. This is a diagnostic trace,
not a controlled synthetic-data workflow or a build of these changes. Across
15,915 one-millisecond CPU samples, the main `iosDelay` coroutine frame appeared
in 3,276 inclusive samples. Other bridge frames overlap; their counts must not be
added. Source inspection confirmed the matching Default/Main polling loop still
existed in this checkout. It has been replaced by the native timer. No before/after
speedup, release-build frame-time improvement, or battery reduction is claimed.
Trace: `/tmp/shilling-iphone-baseline.trace`; exported stacks:
`/tmp/shilling-iphone-cpu.xml` (local, not committed).

**Pixel 6:** attempted a launch and collected diagnostic counters, but the device
remained on the lock screen (`NotificationShade`, `mDreamingLockscreen=true`) and
reported zero rendered app frames. Those counters are not a valid UI performance
baseline. Android synthetic workflow profiling remains pending an unlocked device.

Regression coverage now includes Store5 receipt read/write/overwrite/open/delete/
clear and cheap existence checks, plus a 450-schedule batch with duplicate and
missing IDs. Large receipt operations still materialize whole files; bounded
streaming and transfer backpressure remain open work. Native end-to-end synthetic
workflows and post-fix device profiles are still needed.

## Unlocked-device follow-up

Both physical devices were unlocked for the follow-up. Installed the current debug
builds in place, preserving application data. Required checkout setup was completed
with `just apply-icons dev` and `just setup-webrtc`; no generated icon source change
is included in the patch. These measurements cover launch and idle behavior, not
large synthetic transaction/import/sync workloads.

### Pixel 6

- Confirmed Shilling is the focused activity and the lock screen is inactive.
- Previously installed September 23 debug build: one cold launch at **790 ms**;
  four startup frames, one missed deadline, slow frame in the 450 ms histogram bucket.
- Current debug build: first launch after installation **771 ms**. Five subsequent
  force-stop/cold-process launches: **766, 729, 709, 712, 705 ms**; median **712 ms**.
  This is not a controlled before/after speedup estimate: the old build has one
  sample, caches differ, and these are debug builds.
- Current first-launch sample still has one missed deadline among four frames and
  a frame in the 450 ms bucket. Four frames cannot establish a stable jank rate.
- Captured an ART sampled startup trace (1 ms interval). It attributes approximately
  807 ms of CPU time to the main thread over the recording, with class loading and
  app/Compose initialization present. Logo Base64 decoding used approximately
  60 ms on `DefaultDispatcher-worker-1`, not the main thread. Sampled stack deltas
  include profiler overhead and are diagnostic estimates.
- The older build's idle ART trace showed the main thread in event polling and
  coroutine workers parked; it did not expose an iOS-style polling loop.
- First-launch PSS snapshots were 132,019 KiB (old) and 151,197 KiB (current).
  The samples are not matched by GC timing, so no memory improvement is claimed.

Local artifacts: `/tmp/shilling-android-baseline.atrace`,
`/tmp/shilling-android-baseline-methods.trace`,
`/tmp/shilling-android-fixed-startup.trace`,
`/tmp/shilling-android-fixed-launches.txt`, and the matching `*-frames.txt` and
`*-memory.txt` files. Android now has a valid native startup profile; full synthetic
workflow coverage remains open.

### iPhone SE

An unlocked 15-second launch trace of the previously installed build again found
`iosDelay`'s coroutine frame in **3,294 of 14,846** sampled stacks (inclusive).
The baseline is `/tmp/shilling-iphone-unlocked-baseline.trace`. The current ARM64
debug app was built, installed successfully, and profiled on the same device.

| Time Profiler measurement | Installed baseline | Current debug build |
| --- | ---: | ---: |
| CPU samples (1 ms weights) | 14,846 | 1,360 |
| Main-thread samples | 9,288 | 803 |
| Samples containing any `iosDelay` frame (deduplicated per stack) | 4,331 | 0 |
| First/last sample time within recording | 0.478–15.492 s | 0.749–15.745 s |

The polling hotspot disappeared from the post-fix sample. Total sampled CPU weight
was about 91% lower in these launch/idle captures. This is one pair of debug-build
traces with other code changes and cache differences, not a general application
speedup, release benchmark, or battery measurement. The current trace still
contains signaling-client work, so it did not simply omit all sync initialization.

Post-fix artifacts: `/tmp/shilling-iphone-fixed.trace`,
`/tmp/shilling-iphone-fixed-cpu.xml`, and
`/tmp/shilling-iphone-profile-summary.json`. Both apps were left launched after
profiling. No financial data was cleared or synthetic fixtures inserted into their
existing stores. Large synthetic workflow profiles remain outstanding.

## Original audit: scope and evidence

This is a first pass across the shared Store5 data layer, Compose UI, WebRTC sync,
web and desktop runtime, Android and iOS adapters, and the signaling/hosted server.
It uses synthetic data because no representative household export or slow workflow was
available. Findings below distinguish code-path risks from measurements. No app data
or hosted credentials were used.

| Check | Result | What it establishes |
| --- | --- | --- |
| `just guard-architecture` | Passed | No disallowed transport/data path detected by the guard. |
| `./scripts/ci/run-jvm-tests.sh` | 86 tests passed | JVM correctness baseline, not a performance gate. |
| `./kotlin task :web-app:buildWasmJsAppWasmJsDebug` | Passed, 105 s wall clock, 2.30 GB maximum resident set size reported by `/usr/bin/time -l` | One cold-ish local debug build, with other jobs on the host. |
| `python3 scripts/perf/bench_sqlite.py` | Results below | Host SQLite query-shape comparison only. |
| `node --expose-gc scripts/perf/bench_web_export.cjs` | Results below | sql.js export cost only; excludes IndexedDB commit and UI work. |
| `node scripts/perf/bench_signaling.cjs` | 200 offers relayed in 92.6 ms; p50 83.4 ms, p95 85.5 ms | Two peers on one loopback self-hosted server; no TLS, TURN, or hosted auth. An earlier run finished in 52.8 ms, showing host variability. |

The synthetic SQLite fixture has 100,000 postings, 1,000 schedule IDs, 10,000
exceptions, and 10,000 change-log rows. Seven-run medians varied with concurrent
host builds; use the magnitude and ranking, not the exact milliseconds. The latest
post-build run was:

| Operation | Current shape | Candidate shape |
| --- | ---: | ---: |
| Recent 20 postings | 144.14 ms full result materialization | 0.01 ms SQL `LIMIT 20` |
| Exceptions for 1,000 schedules | 8.90 ms, 1,000 queries | 6.04 ms, one `IN` query |
| Latest versions for 1,000 entities | 499.97 ms, current index | 4.73 ms, matching composite index |
| sql.js export of a database with 1/10/50 MiB blob | 0.44 / 1.54 / 4.97 ms | No alternative measured |

The posting microbenchmark omits Kotlin mapping and sorting done by the real path,
so it understates the total work. The exception comparison understates the cost of
1,000 worker round trips in web/desktop. No end-to-end device latency, frame time,
battery, or hosted join latency was measured in this pass.

## Highest-priority findings

### P0 — Web and desktop persist the whole database after reads

[`sqldelight.worker.js`](../app/web-app/sqldelight.worker.js) calls `scheduleSave()`
after every `exec`, even `SELECT` (lines 69–85). `persistDatabase()` exports the
entire sql.js database and stores it as one IndexedDB value (lines 43–55). Receipts
are BLOBs in that same database on web/desktop. Thus a small metadata change can
copy tens of MiB, and reads can cause identical writes. The 100 ms timer is reset
by every query, so continuous reads can also defer persistence of an earlier write.
This is a code-path finding; the export microbenchmark measures only one part of it.

**Fix direction:** Track dirty state from actual mutations, persist promptly after
committed transactions, and serialize persistence so the latest committed snapshot
wins. Keep entity reads and writes behind Store5. Before switching storage engines,
measure 1/10/50 MiB receipt libraries in Chrome and Tauri: write-to-durable latency,
worker busy time, IndexedDB bytes written, reload time, and memory peak.

### P0 — Full-state P2P sync does two poorly indexed version lookups per entity

[`SyncStoreFacade.buildFullStateSnapshot`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/store/SyncStoreFacade.kt)
reads every entity and calls `latestEntityVersion` twice while constructing each
change (lines 51–85 and 133–160). The query filters by household, entity type, and
entity ID, then orders by timestamp; [`ChangeLog.sq`](../app/shared/src/commonMain/sqldelight/finance/shilling/shared/db/ChangeLog.sq)
indexes only `(household_id, timestamp)`. The synthetic 1,000-lookup comparison
was about 500 ms versus 4.7 ms after adding a matching composite index. A snapshot
does twice that lookup count. It also allocates a full change list before sending.

**Fix direction:** Add an entity-version index, fetch each version once, and stream
or page the snapshot through the Store5 facade. Measure 100/1,000/10,000 entities:
snapshot preparation time, SQL query count, peak memory, time to first change, and
time to full peer convergence. Preserve WebRTC data-channel traversal only.

### P1 — “Recent postings” loads the entire table

[`PostingKey.Recent`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/store/ShillingStores.kt)
selects all postings in a huge date range, maps them, sorts descending, then takes
the requested count (around lines 318–327). [`PostingRepository.loadRecentPostings`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/store/PostingRepository.kt)
uses this path for the receipt attachment picker. `Posting.sq` already has a
`selectRecentWithDetails` query with SQL `LIMIT`, but it is not used by the Store5
reader. The synthetic SQL-only comparison was 144 ms versus 0.01 ms at 100,000
rows; the Kotlin sort adds more work.

**Fix direction:** Add a limited query under the Store5 source of truth for
`PostingKey.Recent`. Verify ordering, negative-date postings, and reactive updates.

### P1 — Bulk import and full sync apply one entity at a time

[`PostingRepository.bulkImport`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/store/PostingRepository.kt)
maps the full CSV, then invokes `store.write` once per posting (lines 100–130).
Each write can trigger SourceOfTruth, updater, change-log, and observable-query work.
[`IncomingChangeRouter`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/sync/IncomingChangeRouter.kt)
also applies each snapshot change and notifies projections one by one (lines 42–56,
96–105). Both paths can lead to repeated recomputation of the home and budget views.

**Fix direction:** Batch writes in bounded transactions within Store5/repository
APIs, and coalesce projection notifications. Keep per-entity P2P semantics where
needed, but measure 1,000- and 10,000-row imports and peer catch-up: total time,
query count, recomputations, memory, and UI frame stalls.

### P1 — Receipt transfer builds and queues the entire encoded file

[`FileTransferManager.prepareTransfer`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/sync/FileTransferManager.kt)
reads the whole file, copies every 16 KiB chunk, base64 encodes each, and returns a
list of all messages (lines 26–65). For a 50 MiB file, base64 payload alone is about
66.7 MiB before message, JSON, queue, and transport copies. The receive side keeps
all decoded chunks then allocates another full output array (lines 82–87, 126–139).
[`IncomingChangeRouter`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/sync/IncomingChangeRouter.kt)
sends chunks with a coroutine yield every five (lines 176–195), which does not
measure the data channel's buffered amount. This is especially relevant to iOS and
Android memory and web/desktop WASM memory.

**Fix direction:** Stream bounded chunks from the receipt store, cap in-flight
bytes based on data-channel backpressure, and write received chunks incrementally.
Measure 1/10/50 MiB files across browser ↔ Android, browser ↔ iOS, and desktop ↔
mobile: throughput, retries, peak memory, and UI responsiveness.

**Follow-up (September 29):** Binary wire frames, bounded backpressure, and
native Store5 range reads are implemented. A matched physical Pixel send
workload reduced peak RSS by 58.5 MiB across three 50 MiB transfers; the full
interruption/retry suite passed. See [native receipt streaming](receipt-streaming-2026-09-29.md)
for the implementation, measurements, and remaining receiver memory work.
The subsequent [staged native receive pass](receipt-staged-receive-2026-09-29.md)
removed the native assembly buffer and measured the Pixel receive peak.

## Other findings by target

| Target | Finding | Priority and next measurement |
| --- | --- | --- |
| Shared Store5 | [`ScheduleRepository.getExceptions`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/store/ScheduleRepository.kt) issues one query per schedule although `selectByScheduleIds` exists. Both window and budget projections call it. | P1. Count SQL calls and projection time at 100/1,000 schedules on each driver. |
| Shared Store5 | [`ReceiptRepository.watchAll`](../app/shared/src/commonMain/kotlin/finance/shilling/shared/data/store/ReceiptRepository.kt) combines all receipts, postings, and schedules, then rebuilds maps and the full result whenever one source emits. `PostingRepository.watchBetween` similarly combines and sorts full lists. | P2. Trace invalidation and allocation per posting edit. |
| Web and desktop | The debug web build used by [`build-web.sh`](../build-web.sh) and the Tauri `beforeBuildCommand` produces a 34.5 MB app WASM plus 8.6 MB Skiko WASM (8.23 MB and 3.33 MB gzip, respectively). The assembled `web-app-dist` is about 44 MiB on disk. | P1 for startup/download. Measure production artifact and cold/warm first interaction before choosing stripping or release-mode packaging. |
| Web and desktop | Web receipt opening and Compose image preview convert whole files to base64 data URLs, adding a full encoded copy before decoding for display. | P2. Measure 10/50 MiB preview peak memory and latency; consider object URLs or bounded thumbnail decoding. |
| Android | [`AndroidSqliteDriver`](../app/shared/src@android/finance/shilling/shared/data/store/AndroidSqliteDriver.kt) executes synchronous SQLite calls. CSV parsing starts in a button callback, and import writes run from a Compose coroutine scope. | P1. Profile the main thread during a 10,000-row CSV import on a physical device, then move parsing/DB work to a bounded worker context. |
| iOS | [`IosReceiptFileStore`](../app/ios-platform/src/IosPlatformServices.kt) reads a complete receipt into `NSData` and copies it into a `ByteArray`; writing copies bytes into `NSData` again. | P1 for large sync files. Record peak RSS and stalls at 10/50 MiB on device or simulator. |
| Server, hosted | [`SupabaseHouseholdMembershipLookup`](../server/src/finance/shilling/server/HostedMetadata.kt) makes a synchronous Java HTTP call on `Dispatchers.IO` for each hosted join and household metadata request, with no explicit request timeout. [`SupabaseTokenVerifier.currentJwks`](../server/src/finance/shilling/server/SupabaseTokenVerifier.kt) can start duplicate fetches on concurrent cache misses because network fetch is outside the cache mutex. | P1. Load test 10/100/500 concurrent hosted joins with a controlled Supabase stub, including slow/failing upstream and JWKS rotation. Track p50/p95/p99 and IO threads. |
| Server, signaling | [`SignalingHub`](../server/src/finance/shilling/server/SignalingHub.kt) sends peer notifications sequentially and retains empty household maps after disconnect. It also logs each relay at INFO in both route and hub. | P2 for normal household size; stress with many unique households, reconnects, and ICE bursts. Track heap, sockets, log throughput, and slow-peer latency. |

## Next performance gate

1. Establish a fixed synthetic fixture with 100/1,000 schedules, 10,000/100,000
   postings, 1/10/50 MiB receipts, and 2/5 P2P peers. Use the same data on Android,
   iOS, web, and desktop.
2. Capture cold start, first usable frame, home/weekly/history navigation, CSV
   import, receipt preview, offline-to-online sync, and full peer catch-up. Record
   p50/p95 latency, query counts, frame time, peak memory, battery where available,
   and persistent bytes written. Use release builds for user-facing targets.
3. For the server, separately load test self-hosted signaling and hosted joins with
   controlled upstream latency. Include slow clients and 1/10/100 households.
4. Convert measured baselines into CI or scheduled regression budgets only after
   device and build variability is understood. Keep the architecture guard in the
   gate for every sync, server, and data-flow change.

No user-facing latency target is claimed yet: the current measurements are local
microbenchmarks and one loopback signaling smoke test, not representative device or
hosted production traces.

## Follow-up: live P2P and production screens on Pixel 6

The separate release benchmark now drives the production WebRTC manager/router
and Store5 receipt persistence against a fresh headless Chrome protocol fixture.
Both peers use the local production signaling server, with host/host UDP ICE;
ADB forwarding carries signaling only. No real budgets are seeded or cleared.

- Initial verified transfers: 1 MiB 223 ms, 10 MiB 1.73 s, 50 MiB 9.26 s.
- Interrupting 50 MiB after 1 MiB, reconnecting, and requesting again succeeded:
  50 MiB in 7.83 s, with every byte checked. This is a complete retry, not resume.
- Browser upload/native Store5 persistence/readback: 1 MiB readback 334 ms.
  This reported interval starts at the readback request, excluding upload time.
- A verified throttled 1 MiB transfer took 20.0 s, versus 271 ms before throttling.
  Settings: 128 KiB/s each direction, 100 ms latency, 1% packet loss, queue 50.
  The peer connection must be recreated after applying the Chrome rule. Earlier
  attempts on an existing connection had no effect and are excluded.
- The 50 MiB result is **not a throughput success criterion**: 9.26 s is only
  5.4 MiB/s of useful data. Base64 adds approximately one third to wire payload.
  An instrumented repeat took 8.69 s; browser parsing/decoding/byte verification
  consumed 309 ms. Initial ICE round-trip time was 7 ms. More sender analysis follows.

Actual shared Compose screens were rendered against 10,000 synthetic postings
and 1,000 schedules. After visible data, graphics counters were reset and ten
350 ms upward swipes performed on the Pixel 6:

| Screen | Frames | Janky frames | p95 | p99 |
| --- | ---: | ---: | ---: | ---: |
| History | 315 | 1 (0.32%) | 6 ms | 7 ms |
| Weekly | 336 | 0 | 7 ms | 9 ms |

These are single scrolling runs, excluding cold composition and app navigation.
Cold screen frame maxima were 303 ms (History) and 131 ms (Weekly), so the smooth
scrolling result does not establish that opening a populated screen is fast.
This pass does not extend these measurements to iPhone, web UI, or desktop UI.
Raw results: `scripts/perf/results/pixel6-live-ui-2026-09-28.json`.

### Receipt throughput investigation

Increasing the sender queue cap from 256 KiB to 1 MiB yielded 8.52 s for 50 MiB
versus the instrumented baseline's 8.69 s. This single-pair difference is too
small to justify additional buffering; **the experiment was reverted**.
In that experiment, the native sender spent 8.20 s enqueueing the message
sequence, including 7.27 s inside `sendFileMessage`. Of that, 2.47 s was explicit
backpressure suspension. The remaining 4.80 s includes buffer/state JNI queries,
JSON serialization, UTF-8 conversion and native channel send; these counters do
not separate those costs. Browser processing was 268 ms. Waiting overlaps actual
network transmission and must not be added to the end-to-end time.

No throughput improvement is claimed in this pass. Further profiling should
separate native channel calls from serialization and measure an independent
link baseline before attributing the result to Wi-Fi. Binary framing is a
potential protocol change, not a validated fix; it needs cross-platform
compatibility coverage before adoption. The current transfer retains bounded
memory, byte correctness, reconnect recovery, and Store5 ownership.

Verification for this follow-up: release benchmark compilation/install, real
Pixel screen rendering, live browser/native transfers including impairment and
reconnect, JavaScript syntax checks, architecture guard, and `git diff --check`.

## Deeper receipt-throughput investigation

These results remain specific to the private-based checkout identified at the
start of this report. Public main requires selective porting and new benchmarks.

Temporary per-stage timers on a 9.995 s / 50 MiB transfer measured:

| Sender stage | Cumulative wall time |
| --- | ---: |
| Outbound channel lookup/state query | 516 ms |
| Native buffered-byte queries | 581 ms |
| JSON serialization | 1,003 ms |
| Text-send call (includes UTF-8 conversion and native calls) | 3,755 ms |
| Explicit backpressure suspension | 2,852 ms |

Backpressure suspension overlaps transmission. These are stage wall times, not
CPU samples, and do not measure independent Wi-Fi capacity. Detailed temporary
production timers were removed after diagnosis.

### Experiments

- Direct Android text send with a different UTF-8 conversion and no additional
  state assertion: median 10.36 s versus control 9.10 s (three runs each).
  Rejected for production despite a lower isolated send-call cost.
- Benchmark-only binary chunks: median 6.24 s (6.24 / 6.63 / 5.98 s).
  This still generates and decodes the Base64 chunks in the fixture, but avoids
  JSON/text framing on the channel. It is not a compatible production protocol
  and is not enabled in Shilling. It suggests a larger follow-up opportunity;
  capability negotiation and native/browser interoperability tests are required.
- Bounded concurrent preparation with unchanged wire format: initial same-build
  medians 8.34 s versus 9.27 s sequential. A subsequent run varied substantially
  (9.57 s median), so an alternating same-connection comparison was added below.

### Changes retained

`FileMessageEncoding` advances the lazy Base64 source and serializes on
`Dispatchers.Default`, with four queued messages. The current consumer and a
producer suspended at emit can account for two additional prepared messages.
Channel access remains on the caller's dispatcher. The buffer cap remains
256 KiB; file persistence stays behind Store5 and user data stays on WebRTC.
Transfer-data sends also avoid a redundant native state lookup; the send itself
still fails on a closed channel and causes request/retry recovery.

Tests cover wire order/escaping, bounded read-ahead and producer cancellation.
The final full live run passed the throttled 1 MiB case (22.86 s), interruption
at 1 MiB, reconnect and full 50 MiB retry (7.74 s), plus native Store5 upload and
readback. No browser errors were recorded. All 98 JVM tests passed; Android,
iOS ARM64 and WASM builds passed, as did architecture and whitespace guards.
The physical throughput verification is Pixel-to-browser, not iPhone throughput.

Raw stage results and all experiments are in
`scripts/perf/results/pixel6-throughput-2026-09-28.json`.

### Alternating same-connection confirmation

| Pair (execution order) | Sequential | Bounded pipeline | Time reduction |
| --- | ---: | ---: | ---: |
| 1 (sequential, pipeline) | 17.51 s | 10.61 s | 39.4% |
| 2 (pipeline, sequential) | 8.94 s | 7.82 s | 12.6% |
| 3 (sequential, pipeline) | 10.12 s | 8.73 s | 13.7% |

All six transfers verified 50 MiB / 3,200 chunks. Medians were 10.12 s sequential
and 8.73 s pipelined. The first pair shows substantial startup/ordering effects;
the later pairs support a modest 12–14% improvement, not the first pair's 39%.
This is still around 6 MiB/s of useful payload in the faster runs. Throughput
remains below a desirable fast-LAN result; the pipeline is an incremental fix,
not a claim that transfer performance is solved.

## Receiver-buffer and SCTP investigation

**The strongest newly identified bottleneck is receiver-side UDP buffering and
SCTP loss recovery.** Protocol encoding and native call overhead also contribute.
These are Pixel 6 → Chrome measurements on this private-based checkout, not
public-main or iPhone throughput results. The receiver was Chrome 154.0.8037.58
on macOS 27.0; the sender used Android 16, Ktor 3.5.2 and
`io.getstream:stream-webrtc-android:1.3.10` in the release benchmark APK.

### Independent link baseline and raw WebRTC

The selected WebRTC route was direct host/host UDP on the local Wi-Fi network.
A separate synthetic TCP stream from the Pixel to the Mac transferred 50 MiB in
1.097 / 1.121 / 1.483 s (median **1.121 s**, 44.6 MiB/s). The Android listener
streamed `/dev/zero`; the Mac counted 50 MiB. This establishes substantially more
available LAN capacity, but does not equate TCP with WebRTC's transport costs.
No TCP path was added to Shilling; ADB carried only signaling for the app fixture.

Raw WebRTC probes generated the known byte pattern without advancing the lazy
Base64 sequence, and verified every byte on receipt. The normal Store5 file read
still preceded the raw sender. Initial 50 MiB results were:

| Raw sender | Time |
| --- | ---: |
| Ktor binary, 16 KiB chunks | 8.54 s |
| Direct native binary, 16 KiB chunks | 6.38 s |
| Native binary, fewer buffered-byte queries, 16 KiB | 5.87 s |
| Same, polling every 1 ms instead of 10 ms | 5.91 s |
| Same, 64 KiB chunks | 5.58 s |

Repeated raw 64 KiB runs took 6.36 / 6.11 s; subsequent diagnostic runs varied up
to 7.64 s. Expanding the sender queue to 4 MiB took 7.55 / 6.76 s. Faster polling
increased sender CPU work without a clear throughput gain. Encoding removal and
fewer native calls help, but do not explain the remaining transport limit.

### Evidence of receive-side loss

- The selected Chrome data-only UDP socket had a **65,536-byte receive buffer**.
  Chromium explicitly initializes it to that size in
  [the source for the measured browser version](https://github.com/chromium/chromium/blob/154.0.8037.58/services/network/p2p/socket_udp.cc).
- During raw transfers the Mac recorded 231 and 62 UDP full-socket drops;
  the preceding two-second idle interval recorded zero. These are global counters,
  so they do not attribute every drop to the benchmark socket.
- A capture of the exact benchmark flow recorded 46,264 incoming encrypted DTLS
  records with no sequence gaps and zero capture-kernel drops. That supports
  delivery to the Mac capture point; it does not rule out drops in socket queues
  after capture, or losses on the return path to the phone.
- A separate 10 MiB native SCTP trace observed ten distinct missing sequence
  numbers reported in receiver SACKs, and subsequent sends for all ten. First
  repeated-send delay was a median 58.5 ms. Repeated log entries count send
  attempts, not confirmed wire retransmissions. Verbose logging perturbs timing.
- The SCTP advertised receive window remained above **4.3 MB**. That application
  transport window was not exhausted; it is separate from the small UDP socket
  buffer. The trace parser and summarized evidence are retained with the fixture.

### Controlled receive-buffer experiment

A benchmark-only receive-only video transceiver, without any track or media
capture, caused Chrome to request a 1 MiB UDP receive buffer and 256 KiB send
buffer. This matches WebRTC's
[video receive buffer setup](https://chromium.googlesource.com/external/webrtc/+/master/media/engine/webrtc_video_engine.cc)
and [buffer constants](https://github.com/webrtc-mirror/webrtc/blob/main/media/base/media_constants.cc).
The first normal-protocol trials improved from 10.49 / 9.65 s data-only to
6.10 / 6.39 s with that diagnostic negotiation. Since negotiation changes more
than one setting, a tighter comparison followed.

Both tighter-comparison groups used identical receive-only video negotiation,
the production text receipt pipeline and the same 256 KiB UDP send buffer.
Only the Chrome `WebRTC-ReceiveBufferSize` field trial changed. `netstat` confirmed
the requested receive sizes took effect:

| UDP receive buffer | 50 MiB elapsed times | Median | Full-socket drops per run |
| --- | --- | ---: | --- |
| 1 MiB | 6.084 / 5.382 / 5.332 s | **5.382 s** | 9 / 0 / 0 |
| 64 KiB | 7.921 / 10.083 / 10.035 s | **10.035 s** | 103 / 159 / 181 |

The larger buffer reduced median elapsed time **46.4%**. All six transfers
verified 50 MiB / 3,200 chunks. Browser receipt processing was only 143–175 ms.
An earlier 64 KiB field-trial group also took 9.598 / 9.090 s. These were separate
fresh connections, not randomized repeated sessions. Counter deltas are global;
idle intervals recorded 0 and 4 drops respectively. Nevertheless, the repeated
timing changes, verified socket settings and SCTP trace strongly support buffer
loss/recovery as a major cause of this benchmark's slow throughput.

With the larger receiver buffer, raw binary 64 KiB transfers took
3.85 / 4.18 / 4.69 s. Remaining costs include WebRTC transport, Base64 expansion,
JSON/UTF-8 conversion and thousands of native calls. These experiments do not
assign an exact share to each cost, or show that all remaining time is CPU work.

### Outcome and follow-up

This pass adds diagnostic tools and evidence; it does not ship unused video
negotiation as a production fix. No global network settings were changed. The
next transport work should address receive buffering through supported native
configuration/upstream changes, and assess sender pacing against browser
receivers. Binary framing remains a separate opportunity requiring capability
negotiation and Android/iOS/browser compatibility tests. Increasing only the
application send queue has not demonstrated a useful fix.

Release benchmark compilation/install, byte-verified physical transfers,
JavaScript syntax checks, SCTP parser replay, `just guard-architecture` and
`git diff --check` passed. No production code changed in this diagnostic pass;
the prior 98-test run remains the production verification baseline. Full
sanitized results are in
`scripts/perf/results/pixel6-transport-diagnosis-2026-09-28.json`; opt-in
reproduction commands are in `scripts/perf/README.md`.
