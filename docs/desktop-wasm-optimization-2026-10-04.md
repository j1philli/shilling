# Desktop Wasm optimization — October 4, 2026

Release packaging now optimizes the Kotlin Wasm application with Binaryen. Two
matched 10,000-row import comparisons reduced settled desktop physical footprint
from **603–609 MiB to 415–528 MiB**. The paired reductions were **80.5 MiB (13.2%)**
and **188.2 MiB (31.2%)**. Both optimized observations were below both controls,
but two runs per configuration do not establish a statistical estimate or a
universal saving. WebKit collection timing remains visibly variable.

This follows [the desktop/web comparison](desktop-web-memory-comparison-2026-10-04.md)
on public `main` at `5c56a5010f536a4b51fa9ac2ef609a61ead5bc6f`. Application Kotlin,
Store5, SQL.js, Skia and Tauri behavior were not edited for this reduction.

## What changed

The previous release script packaged Kotlin's output and trimmed debugger-only
names. It now also runs the lockfile-pinned Node build of **Binaryen 132.0.0**,
with size-focused optimization and global usage analysis. The same packaging
path serves web and Tauri desktop; Debug builds are unchanged. The
[Binaryen project](https://github.com/WebAssembly/binaryen) describes its Wasm
optimization passes, and the
[Node distribution](https://github.com/AssemblyScript/binaryen.js#command-line)
provides the portable CLI used here.

The flags preserve floating-point and trap semantics: neither `--fast-math` nor
`--traps-never-happen` is enabled. Closed-world optimization applies to the
single Kotlin application module. JS passes its GC references opaquely; Skia
and SQL.js do not inspect their layouts. This step is not suitable for arbitrary
separately linked Wasm libraries without reviewing that assumption.

Packaging trims unused debugger names before optimization, retains the
optimizer's updated function names for stack traces, validates the output,
checks exported names/kinds and rejects new loader dependencies. It replaces
the release file only after success. The original compiler package remains
under `build/tasks`. Binaryen is a build dependency, not a shipped app runtime.
Release builds take longer: the measured fixture optimization took about five
minutes with the portable Node CLI on this Mac. Development builds skip it.

The measured fixture shrank from **15,625,004 to 9,390,251 bytes (39.9%)**.
The normal production artifact shrank from **15,622,459 to 9,386,289 bytes
(39.9%)**. Defined functions in the fixture fell from **48,690 to 26,447 (45.7%)**. These are binary metrics,
not an equivalent percentage reduction in RAM. The original and optimized
fixture hashes, section sizes and optimizer flags are recorded with the results.

## Matched desktop import measurements

Mac15,6, 36 GiB RAM, macOS 27.0 (26A428), release Tauri fixture, 1200 × 900 CSS
pixels and device pixel ratio 1. Each fresh process restores the same synthetic
10,000-posting WebKit sandbox, with 1,000 schedules, 20 accounts, 40 categories
and 250 receipt metadata records. The workload opens CSV review, imports another
10,000 postings through Store5, visits Home/Activity/Home, then waits 45 seconds
before the final checkpoint. No forced garbage collection was used.

Runs occurred in control/optimized/control/optimized order. All four used the
same optional diagnostic JS and identical Skia/SQL.js binaries; only the Kotlin
Wasm artifact changed within each comparison. Startup creates run-specific
session metadata, so the resulting SQLite file sizes vary slightly despite
equivalent financial records. No release build or optimizer pass overlapped
these measurements.

All cells are MiB. The first four numeric columns sum sequential host,
WebContent, GPU and Networking `vmmap` physical-footprint snapshots. They are
approximate phase totals, not simultaneous maxima. The peak column is only
WebContent's process-lifetime peak; peaks from different processes are not added.

| Run | Initial Home total | CSV review total | Import-complete total | Settled idle total | Idle WebContent | WebContent peak |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Control 1 | 447.1 | 618.1 | 587.3 | 608.9 | 546.8 | 675.9 |
| Optimized 1 | 350.6 | 492.8 | 482.1 | 528.4 | 466.6 | 630.5 |
| Control 2 | 477.7 | 586.5 | 576.2 | 603.4 | 541.7 | 699.9 |
| Optimized 2 | 378.4 | 485.9 | 477.4 | 415.2 | 351.1 | 597.3 |

The second optimized process released more memory after Activity and during
idle. Do not present its 415 MiB result as the typical footprint. Initial host
memory also varied: control 1 started near 68 MiB and subsequently dropped near
31 MiB. Both controls and optimized runs ended with hosts near 30–33 MiB, so
that startup host difference does not explain the final savings.

Raw measurements:
[control 1](../scripts/perf/results/desktop-wasm-optimization-control-1-2026-10-04.json),
[optimized 1](../scripts/perf/results/desktop-wasm-optimization-optimized-1-2026-10-04.json),
[control 2](../scripts/perf/results/desktop-wasm-optimization-control-2-2026-10-04.json),
[optimized 2](../scripts/perf/results/desktop-wasm-optimization-optimized-2-2026-10-04.json).

## Cold reopening on the same completed database

A separate pair restored the identical completed 20,000-posting sandbox, opened
Home without visiting Import, and waited through the cold-idle workload. Both
reopened databases passed integrity checks and retained identical financial
values. This is one cold pair, not a repeat-run distribution.

| Configuration | Host | WebContent | GPU | Networking | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| Previous release packaging | 29.7 | 353.2 | 19.2 | 8.9 | 411.0 |
| Optimized Kotlin Wasm | 30.2 | 290.2 | 19.2 | 8.9 | 348.5 |

Cold total footprint fell **62.5 MiB (15.2%)**; WebContent fell **63.0 MiB
(17.8%)**. The similar host/GPU/network values make this a clearer baseline
comparison than attributing the varying startup totals from the CSV runs.
Raw data: [control](../scripts/perf/results/desktop-wasm-optimization-cold-control-2026-10-04.json),
[optimized](../scripts/perf/results/desktop-wasm-optimization-cold-optimized-2026-10-04.json).

## Allocation evidence and limits

Across the preserved control and repeated optimized CSV diagnostics:

- Main-thread linear memories remained at 0 and **18,808,832 bytes (17.94 MiB)**.
- The SQLite worker's Wasm linear capacity remained at **22,151,168 bytes (21.13 MiB)**.
- Skia's CPU font cache peaked at **82,791 bytes (0.079 MiB)**; its CPU resource
  cache reported zero bytes used. These counters exclude the GPU cache.
- The SQLite page-cache setting was unchanged at `-2000` (KiB), and the completed
  database snapshot was approximately 16.5 MiB.

These counters rule out growth in those particular capacities/caches as the
large import spike. They do **not** measure all worker memory: SQL.js also owns
its in-memory filesystem, query objects and temporary copies. Nor do the main
linear memories include Kotlin Wasm GC objects or compiled code. The observed
reduction demonstrates a benefit from optimizing the Kotlin module; it does
not precisely divide the saving between compiled code, runtime metadata,
smaller live objects and garbage-collection behavior.

The new opt-in allocation probe collects metadata and counters only. Its worker
requests are queued with the normal request stream; it does not export or retain
database snapshots. The desktop sampler now preserves these diagnostics in its
JSON output so Tauri log rotation cannot erase earlier phase counters.

## Verification

- **13 web tests pass**, including executable Wasm GC/JS round trips, string
  imports, integer traps, floating-point edge cases, package validation, and the
  existing SQL worker persistence/lifecycle checks.
- Production release web and unsigned macOS desktop builds pass. CI input
  fingerprints include the optimizer and name-trimming scripts for all four
  web/desktop targets. Linux and Windows were not run locally in this pass.
- Four fresh-process CSV workloads and two cold-reopen workloads complete. Every
  resulting database passes SQLite integrity checking, contains 20,000 postings,
  and retains all 10,000 imported IDs and matching change-log records.
- All financial columns of all 20,000 postings match between each control and
  optimized pair, excluding only generated record/space IDs. In the CSV runs,
  all 10,000 new rows are expenses of 15.75, as expected from the fixture input.
- Architecture guard, JavaScript/shell syntax, Python compilation and diff checks
  pass. No production Shilling database was used or modified.

There is no new standalone-browser RAM measurement in this pass, although the
production web build uses the same optimization pipeline.

## Full navigation verification after unlocking

The unlocked retry completed **all 28 expected checkpoints**: two passes through
Home, weekly/monthly Plan, Schedules, Categories, Accounts, Activity, Receipts
and Settings; transaction, schedule, category and account editors; CSV review
and import of 10,000 rows; Home/Activity/Home; and the final 45-second idle wait.
Editors were opened without saving changes. The initial attempt blocked by the
locked screen remains excluded.

This run used the same optimized Kotlin Wasm artifact and restored 10,000-posting
seed, with the committed allocation probes. All 28 READY checkpoints recorded
main-thread, worker and runtime diagnostics without errors; all reported a
visible 1200 × 900 viewport at DPR 1. Native visual inspection verified Home,
Receipts, Plan Categories, the schedule editor, CSV review, and Home after
completion. The final Home screen displayed the newly imported transactions.

| Full-workload checkpoint | Total physical footprint (MiB) |
| --- | ---: |
| Initial Home | 367.3 |
| Account editor, after both navigation passes | 400.6 |
| CSV review | 581.4 |
| Import complete | 626.9 |
| Final idle | 564.0 |

At final idle, the total comprised host **31.1**, WebContent **503.3**, GPU
**20.9**, and Networking **8.7 MiB**. WebContent's process-lifetime peak was
**749.1 MiB**; that is not the whole application's simultaneous peak. This is
one optimized-only coverage run, with no matched full-workload control. Its
broader navigation history is different from the CSV-only comparisons above,
so it does not supply another percentage reduction. It also shows that the
earlier 415–528 MiB observations are not a ceiling for a longer session.

Main/worker linear capacities remained unchanged at 17.94/21.13 MiB. The Skia
CPU font cache peaked at 140,430 bytes (0.134 MiB), with the CPU resource cache
still reporting zero. No forced collection, release build, or optimizer pass
ran during the measurement.

The saved database passed integrity checking and retained **20,000 postings**,
including **10,000 unique imported IDs and matching change-log records**.
All financial columns matched the earlier CSV control across all 20,000 rows,
excluding generated record/space IDs; all imported rows had the expected
expense amount of 15.75. The isolated sandbox was restored afterward.

Evidence: [full navigation profile](../scripts/perf/results/desktop-wasm-optimization-full-navigation-2026-10-04.json)
and [checkpoint, probe, and database verification](../scripts/perf/results/desktop-wasm-optimization-full-verification-2026-10-04.json).

Supporting artifacts:
[allocation counters](../scripts/perf/results/desktop-wasm-optimization-allocations-2026-10-04.json),
[database checks and financial-value hashes](../scripts/perf/results/desktop-wasm-optimization-integrity-2026-10-04.json),
[source/build fingerprints](../scripts/perf/results/desktop-wasm-optimization-source-2026-10-04.json),
[binary section sizes](../scripts/perf/results/desktop-wasm-optimization-sections-2026-10-04.json).
The first optimized run's detailed counter log rotated before archival; its
process measurements and database checks remain complete. The sampler now
preserves diagnostics directly in the profile JSON; both cold runs verified
that recording path.
