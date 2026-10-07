# Mobile regression and iOS rendering follow-up — October 7, 2026

Tested public main `ff1599c` after the desktop memory changes on a physical
Pixel 6 (Android 16) and iPhone SE 2 (iOS 18.7.8). Both used Release builds of
the isolated `finance.shilling.perf` apps, production UI sources, production
SQLite drivers and Store5 repositories, with synthetic data. Normal Shilling
app sandboxes were not modified.

## Regression found and fixed

The shared view models now expire their replay snapshots after five seconds
without subscribers. The native Swift models already retain their last displayed
snapshot. On returning to a tab, they were publishing the shared flow's initial
placeholder, clearing the native list before the refreshed data arrived.

After scrolling and spending seven seconds in Settings, all nine baseline
returns lost their scroll position: Activity moved from offset 1304 to -64;
Plan and Receipts moved from 1252 to -64. The row counts remained correct.

Activity, Plan and Receipts now ignore that uninitialized placeholder and apply
the loaded result. Genuine empty results have explicit empty-state copy and
still apply; Plan Overview has a populated range label after loading. The native
models also compare immutable snapshot contents, avoiding a redundant publication
when a reload produces the same data. This uses the snapshot already held by the
native model and adds no separate cache.

The final build preserved the exact scroll position in all nine returns. Native
model checks also verified a no-match Activity search, an empty schedule filter,
temporarily empty receipt metadata, restoration of all three lists, and a posting
changed through Store5 while Activity was unsubscribed. All listeners were released
after closing.

## Repeated resume measurements

Three fresh processes per variant; each process resumes Activity, Plan and
Receipts after seven seconds hidden. The fixture has 10,000 postings plus one
update probe, 1,000 schedules and 250 receipts. Transfers collapse into 7,501
Activity entries. Timings include readiness and a one-second observation window.
CPU is application CPU time across threads, not CPU utilization. All measured
thermal states were nominal.

| Tab | Median CPU, before → final | Change | Median worst display-callback gap, before → final |
|---|---:|---:|---:|
| Activity | 325.3 → 255.0 ms | -22% | 40.5 → 41.1 ms |
| Plan | 209.7 → 161.3 ms | -23% | 61.1 → 40.7 ms |
| Receipts | 199.6 → 165.9 ms | -17% | 44.0 → 41.2 ms |

Ignoring the placeholder alone preserved scrolling, but Activity CPU rose to
350.0 ms and its median worst callback gap reached 103.8 ms. Comparing the
refreshed snapshot's contents removed that unnecessary redraw. The intermediate
variant is retained in the raw results so this tradeoff remains visible.

These are matched actions with different correct/incorrect outcomes: the baseline
returns to the top, while the final build keeps the intended viewport. Callback
gaps measure delivery of CADisplayLink callbacks, not rendering hitch duration.
Database reads still occur after resubscription and remain off Main.

Median resident memory at the three resume checkpoints was 140.6 → 140.5 MiB,
155.5 → 158.9 MiB and 168.1 → 169.9 MiB. This pass establishes a correctness and
CPU improvement, not an iOS memory reduction.

## Rendering traces and remaining stalls

Separate Animation Hitches + Time Profiler + Points of Interest recordings were
aligned to the three resume intervals. These are single paired traces, so their
largest intervals are observations, not statistical tail guarantees.

| Resume phase | Main-thread CPU samples, before → final | Hitch intervals, before → final | Largest rendering hitch, before → final |
|---|---:|---:|---:|
| Activity | 231 → 169 | 3 → 2 | 83.3 → 83.3 ms |
| Plan | 189 → 138 | 3 → 3 | 66.7 → 66.7 ms |
| Receipts | 192 → 164 | 3 → 2 | 66.7 → 100.0 ms |

Rendering hitch tails did **not** consistently improve despite lower CPU work.
The final 100 ms Receipt interval overlaps native tab transition, hosting-view
layout, SwiftUI graph updates and navigation-bar work. It contained 59 Main-thread
CPU samples and three background samples; this does not explain every millisecond
of the rendering delay. The fix is retained for scroll correctness and the repeated
CPU reduction, with this remaining rendering cost explicitly recorded.

The separate Plan section-switch workload also remains uneven. Across six visits
per section, median worst callback gaps were 77.7 → 74.6 ms for Schedules,
58.5 → 67.9 ms for Categories, and 61.1 → 55.9 ms for Accounts. The initial
synchronized Plan trace recorded up to 66.7 ms rendering hitches and substantial
collection layout, cell creation and sizing. Repeated switches made no database
reads. These results do not establish an improvement in rapid section switching.
Rendering-context intervals can overlap and must not be summed into a wall-time
hitch percentage.

## Mobile regression coverage

- The same 10,001-row import-review scenario passed on Android and Kotlin/Native:
  duplicates, unreadable rows, selection edits, per-row categories, category
  rename/color changes, replay-cache expiry and resume, account-dependent
  duplicates, file replacement, and importing selected income/expense rows.
  It verifies saved values through Store5 and cleans up its synthetic records.
- Pixel workload checks passed for 1,000- and 10,000-row imports (11,000 stored
  transactions), 1,000 schedules, repeated history/recent/projection reads and
  receipt transfers of 1, 10 and 50 MiB with byte-for-byte verification.
- Two fresh launches each of production Compose Home, Activity and Plan populated
  correctly. Steady PSS was 88–90, 105–106 and 93–94 MiB respectively; these are
  fixture observations, not a matched memory-improvement claim. Physical UI
  interactions checked Schedules, Categories and Accounts, an empty Income
  filter, retaining that filter after a longer hidden interval, and restoring All.
- iOS Home updates, all five tabs over six cycles, four rapid editor saves with
  Store5 readback/restoration, six receipt-preview closures, and Plan navigation
  after its hidden sections expired passed. Receipt previews released controllers
  and temporary files. Hidden Plan sections performed no reactive reads.
- The final native navigation workload pushed/popped four editors twice, exercised
  focus and typing, and verified all eight closures plus subsequent mutations.
  Final physical screenshots confirmed populated Activity, Plan and Receipts at
  their preserved scroll positions.

Two stale fixture assumptions were corrected. Android fixtures requested schema
version 1 after bootstrap set version 2, so repeated launches failed with a database
downgrade error. They now use `ShillingDatabase.Schema.version`, as the production
Android driver already does; missing Plan-list fixture bindings were also supplied.
The iOS lifetime runner expected zero reads during writes after closing screens.
The current repository validates linked-transfer identity through Store5 before
each write, producing one posting and one bookkeeping read. The check now requires
exactly those 20 reads for ten writes, zero Main-thread queries and zero listeners;
it rejects additional reads. This was checked against the captured run and four
simulated failures.

## Reproduction and limits

Build the Release fixtures with `:perf-android:buildAndroidRelease` and the
`app/perf-ios/module.xcodeproj` app scheme. The new shared import scenario lives
under `scripts/perf/fixtures/` and is symlinked into both fixture targets.

Use `scripts/perf/profile_ios_native_ui.py` with `--screen import-regression`,
`loaded-empty`, or `tab-resume`. Tab resume verifies row counts, scroll position,
Main-thread queries and controller/listener cleanup. For the known-bad baseline,
pass `--allow-scroll-reset`. Swift-only comparisons reused a verified Release
Kotlin framework; the expanded Kotlin fixture also received a full Release build.

The measurements use public UIKit control handlers and synthetic workloads;
they do not constitute a physical-touch or VoiceOver audit, a hosted-auth test,
or a new cross-device sync test. The approach follows Apple's guidance to
[correlate expensive SwiftUI updates with their triggering events](https://developer.apple.com/documentation/xcode/understanding-and-improving-swiftui-performance).

Validation includes 190 passing shared/server JVM tests, Release fixture builds,
the architecture guard, Python syntax checks and diff checks.

[Raw regression checks, repeated cohorts and aligned trace summaries](../scripts/perf/results/mobile-regression-ios-resume-2026-10-07.json).

The [native tab rendering follow-up](ios-tab-rendering-followup-2026-10-07.md)
reproduces the Receipt peak, tests two reverted prototypes, and adds timing for
work before the first display callback plus per-hitch CPU attribution.
