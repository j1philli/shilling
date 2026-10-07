# WebContent allocation investigation — October 6, 2026

This investigation starts from public main `8e957e8`, after the
[saved-import retention fix](desktop-import-session-retention-2026-10-06.md).
The diagnostic runs leave production Kotlin, Store5 and Tauri behavior unchanged.

**Most of the renderer's post-import footprint is in WebKit's general allocator,
not generated machine code or committed Wasm linear memory.** A native category
snapshot attributes 322 MiB of a 422 MiB WebContent footprint to `WebKit Malloc`.
This category includes runtime and application allocations; it is not a count
of live Kotlin objects and does not establish that WebKit itself is leaking.

A separate staged loader shows substantial allocation before the app entry
point runs. Two clean runs reach 86 MiB after compiling the Kotlin module,
107–108 MiB after loading its JS imports and Skia, and 123–129 MiB after Kotlin
instantiation. Starting the actual app on the synthetic 10,000-posting database
raises WebContent to 257–275 MiB. These are diagnostic stage measurements, not
independent costs that can be added together or a new memory reduction.

## Native categories after import

The existing `csv-session` fixture opens four 10,000-row reviews, switches tabs
and restores edits, cancels three reviews, imports the fourth, then idles for
three minutes. It finishes with 20,000 postings. This run ends at **421.5 MiB
WebContent / 478.2 MiB whole app**, with a **532.7 MiB WebContent lifetime peak**.
The app code is the same as PR #51. This observation is not an optimization
comparison with the earlier 467–472 MiB whole-app results.

A `footprint` snapshot during the final idle interval reports the following
charged dirty categories. Its total is 422.4 MiB; rounding and separate sampling
account for its difference from the last `vmmap` checkpoint.

| WebContent category | Charged MiB |
| --- | ---: |
| WebKit Malloc | 322.0 |
| Owned graphics | 36.1 |
| JS JIT generated code | 23.8 |
| Untagged | 19.3 |
| WebAssembly memory | 15.6 |
| Remaining categories | 5.6 |
| **Total** | **422.4** |

An additional **396.5 MiB is marked reclaimable**, including 371.5 MiB in the
WebKit allocator. It is separate from the charged footprint. Do not add it to
422.4 MiB or treat all resident pages as equally retained RAM. Likewise, `vmmap`
virtual reservations and the standard heap inspector's approximately 12.2 GiB
allocation tally include large Wasm address reservations; they are not RAM use.
Three approximately 4 GiB reservations appear in the heap inspection.

The [WebKit memory-inspection documentation](https://docs.webkit.org/Infrastructure/MemoryInspection.html)
distinguishes dirty and reclaimable memory and explains that detailed per-class
allocator labels require an instrumented WebKit build. The `footprint` category
breakdown is more useful here than summing `vmmap` resident/dirty columns or
interpreting virtual allocation sizes as committed memory.

The existing runtime probes again show unchanged main linear capacities of
0 and 18,808,832 bytes, SQL-worker capacity of 22,151,168 bytes, and small Skia CPU
caches. Linear capacity is not committed footprint, and excludes the Kotlin/Wasm
GC heap. The CPU Skia counters do not include GPU allocations. Those measurements
therefore cannot account for all the memory in `WebKit Malloc`.

## Startup without running the application

The new opt-in `bootstrap` workload keeps the packaged Wasm binaries and generated
import object intact. It renders a small HTML status label, compiles the Kotlin
module, loads its imports and waits for Skia, instantiates Kotlin (including its
Wasm start section), then calls the ordinary `_start` entry point. The app opens
Home using the normal fixture and Store5 graph. There are 15 seconds before and
after each early checkpoint, plus the normal cold-Home idle interval.

The early stages have no SQL requests. The ordinary allocation probe is disabled
because its eager Skia import would invalidate the experiment. Runtime and SQL
probes remain enabled. The ordering and extra pauses deliberately differ from
ordinary startup and allow different collection opportunities. Changes between
stages do not identify individual retained object types or each library's unique
production cost.

All values below are **WebContent physical footprint in MiB**. The clean runs
are `bootstrap-2` and `bootstrap-3`.

| Stage | Clean run 1 | Clean run 2 |
| --- | ---: | ---: |
| Empty HTML renderer | 16.3 | 16.6 |
| Kotlin module compiled | 85.8 | 86.6 |
| JS imports and Skia loaded | 107.4 | 108.0 |
| Kotlin instantiated, before app entry point | 129.0 | 123.1 |
| App on Home | 274.6 | 257.3 |
| Final Home idle | 274.4 | 257.2 |

At the empty-page checkpoint, the entire host/WebContent/GPU/Networking group
uses 64.2–65.1 MiB. Final whole-app totals are 314.0–329.0 MiB. These cold runs use
10,000 postings, whereas the long-session run ends with 20,000; subtracting their
totals would not be a matched estimate of session retention.

Startup produces 110 SQL requests and 6,348 returned rows in both clean runs.
Most are repeated reads of the 1,000 schedules; the bounded recent-posting query
returns only 12 rows across its calls. The counters do not change during the
final idle interval. Three persistence snapshots copy 24,940,544 bytes in total.
Returned-row and snapshot-byte counts are cumulative work, not resident memory.
This rules out continued SQL polling in this workload; it does not isolate the
remaining allocation into database, Compose and runtime ownership.

The [startup follow-up](desktop-startup-memory-2026-10-06.md) subsequently found
that this seed includes a first-session claim of its local finance space. These
startup counts include that migration; use an initialized seed to measure an
ordinary reopen. The matched comparisons above still use the same seed on both sides.

The control Kotlin module is 9,405,360 bytes with 26,490 defined functions,
19,504 globals and 2,884 recursion groups. Recursion-group count is not individual
GC-type count. Compiling that artifact increases footprint substantially before
user data is queried. Generated JIT code is less than 1 MiB at the early stages;
module compilation has runtime/metadata costs beyond executable pages.

## Allocation-stack limitation

A first staged run attempted an Instruments Allocations attachment. macOS rejected
it because the system WebContent process is restricted while System Integrity
Protection is enabled. No system protection settings were changed, and no
allocation stacks were obtained. That run is preserved as `bootstrap-1`, but is
excluded from the clean pair above. A short `heap` inspection in the long-session
run also did not classify most allocations beyond native allocator categories.

The evidence establishes the large allocator category and the startup stages
where process memory increases. It does not distinguish all live Kotlin objects
from compiler metadata, unused allocator capacity or garbage awaiting collection.

## Type-merging experiment

The existing release optimization is followed experimentally by Binaryen 132's
`--type-merging -Oz`, retaining the same feature flags, closed-world assumption
and function names. The [upstream pass](https://github.com/WebAssembly/binaryen/blob/version_132/src/passes/TypeMerging.cpp)
merges types when their distinctions have no observable effect. This experiment
keeps the JS/import object and Skia/SQL binaries identical to the clean controls;
only `perf-web.wasm` differs. Export names/kinds and loader dependencies validate.

The artifact falls from 9,405,360 to 9,203,175 bytes (**2.1%**); defined functions
fall from 26,490 to 25,804 and recursion groups from 2,884 to 1,943. The extra
optimization takes 38 seconds on this machine. A smaller file is not an equal
RAM saving.

| Stage | Clean controls | Type-merging candidate |
| --- | ---: | ---: |
| Kotlin compiled | 85.8–86.6 MiB | 82.3 MiB |
| Imports and Skia | 107.4–108.0 MiB | 104.5 MiB |
| Kotlin instantiated | 123.1–129.0 MiB | 119.0 MiB |
| Home | 257.3–274.6 MiB | 266.4 MiB |
| Final idle | 257.2–274.4 MiB | 254.5 MiB |

The compile-stage difference is small (3.5–4.3 MiB). Home falls within the control
range, and final idle is only 2.7 MiB below the lowest clean control. One candidate
run does not establish a dependable application-level saving against the observed
collection variability. **The production optimizer is unchanged.** This is a
screening experiment, not a demonstrated failure of type merging, nor a validated
production optimization. The candidate was not taken through the full navigation,
import and cross-target regression matrix.

## Verification and reproducibility

All five fresh-process native profiles complete: one long-session run, three
staged control runs (one with the rejected Instruments attachment), and one type-
merging candidate. All **44 READY checkpoints** have four-process measurements.
All **282 screen-lock samples** are unlocked. The 20 long-session checkpoints and
16 early bootstrap checkpoints have visible 1200 × 900/DPR 1 diagnostics. No
manual workload edits, forced collection, receipt-image decoding or actual P2P
data transfers occur. No build or optimizer pass overlaps a memory profile.
Other user applications remain open on the shared Mac.

The machine is a Mac15,6, 36 GiB RAM, macOS 27.0.1 (26A434), in dark appearance.
Whole-app totals sum sequential process snapshots; they are approximate phase
totals. Separate process lifetime peaks are never added together. The bootstrap
runs use the same restored 10,000-posting seed; the long-session run's final
10,000-row import is verified separately.

Every stopped database passes SQLite integrity checking. The long-session run
has 20,000 postings and 10,000 unique imported IDs with matching change-log
records; its financial-value hash matches the PR #51 runs. The four bootstrap
runs retain the same 10,000 postings and identical financial values. Production
data is untouched. The original benchmark WebKit sandbox's 18 files are restored
and hash-verified; owned app/server processes and temporary display-idle
assertions are stopped.

Both experimental unsigned macOS bundles build successfully. Architecture guard,
Python/JS/shell syntax, invalid-option rejection and diff checks pass. The native
runs exercise the new diagnostic paths. No application Kotlin or production
optimizer change is shipped, and the shared/mobile test suites were not rerun.

The [perf README](../scripts/perf/README.md) documents the new `bootstrap` workload,
`--vmmap-dir`, and `--footprint-dir`. The profiler also now preserves the existing
SQL row/request diagnostics instead of dropping them from the result JSON.

Evidence:

- [Long-session raw profile](../scripts/perf/results/desktop-webcontent-regions-1-2026-10-06.json)
  and [native category snapshot](../scripts/perf/results/desktop-webcontent-idle-footprint-2026-10-06.json).
- Staged controls: [initial diagnostic](../scripts/perf/results/desktop-webcontent-bootstrap-1-2026-10-06.json),
  [clean run 1](../scripts/perf/results/desktop-webcontent-bootstrap-2-2026-10-06.json),
  [clean run 2](../scripts/perf/results/desktop-webcontent-bootstrap-3-2026-10-06.json).
- [Type candidate](../scripts/perf/results/desktop-webcontent-bootstrap-type-1-2026-10-06.json),
  [Wasm sections](../scripts/perf/results/desktop-webcontent-wasm-sections-2026-10-06.json),
  and [source/asset provenance](../scripts/perf/results/desktop-webcontent-provenance-2026-10-06.json).
- [Region summary](../scripts/perf/results/desktop-webcontent-region-summary-2026-10-06.json),
  [checkpoint validation](../scripts/perf/results/desktop-webcontent-validation-2026-10-06.json),
  [database integrity](../scripts/perf/results/desktop-webcontent-integrity-2026-10-06.json),
  and [cleanup](../scripts/perf/results/desktop-webcontent-cleanup-2026-10-06.json).

The next useful distinction is within app startup: database-worker initialization,
Compose initialization, and Home projection. The new module-stage evidence does
not yet assign that remaining footprint to one of them. The larger post-import
allocator footprint also needs object-lifetime evidence before another fix can
be justified; a smaller Wasm file alone does not resolve it.
