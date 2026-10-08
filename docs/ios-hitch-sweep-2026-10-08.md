# iOS hitch sweep — October 8, 2026

Activity now renders its shared presentation snapshot with reusable UITableView
cells. In three fresh processes on the test iPhone SE, median scrolling CPU fell
37% and tab-resume list readiness improved 28%. Two new Animation Hitches traces
recorded 50.0 and 33.3 ms maximum Activity resume hitches, versus 83.3 ms in the
baseline trace. Schedule editor opening and Plan section changes remain targets.

## Method and scope

Baseline is `3af2851` (PR #56), including the Receipt table improvement. All
performance measurements use the isolated `finance.shilling.perf` Release app
on the physical iPhone SE (2nd generation), iOS 18.7.8 (22H352). The synthetic
Store5 fixture has 10,001 postings, 1,000 schedules and 250 receipts. Transfer
merging produces 7,501 Activity rows plus the history-range control.

Swift-only device builds reuse the same verified Release Kotlin framework.
A separate full Release simulator build provides visual review. No normal
Shilling app or personal data is used. All recorded thermal states were nominal.

App-process CPU includes all app threads. List readiness checks populated cells
in the hierarchy, not presented pixels. CADisplayLink callback gaps and Instruments
rendering hitches are different measurements. RSS values are checkpoints, not
peak or long-session memory measurements. Traces align Animation Hitches, Time
Profiler and Points of Interest; inclusive stack categories can overlap.

## Sweep findings

The initial sweep uses one fresh process per workload to locate follow-up work:

| Workload | Observation |
| --- | --- |
| Home | Load callback gap 197 ms; subsequent updates and scrolling stayed at about 16.7 ms. |
| Activity | Five-second scroll used 1,947 ms CPU and had four callback gaps over 25 ms. |
| Plan | Twelve section/grouping switches; most maximum callback gaps were 24–68 ms. Repeated visits made no database reads. |
| Editors | First Schedule push had a 139 ms callback gap; first Schedule focus reached 252 ms. Later focus was much smaller. |
| Tab resume | Activity list readiness 99 ms, Plan 75 ms, Receipts 64 ms. All preserved their rows and scroll position after seven seconds away. |

The fresh editor trace recorded a 116.7 ms first Schedule push hitch and 83.3 ms
on the second push. Its first push had 118 inclusive Main-thread view-graph
samples, including 83 collection-layout, 70 cell-creation and 62 cell-sizing
samples. Transaction pushes reached 66.7 ms. These point toward native form
construction/layout as remaining app work.

The large first-keyboard pause did not recur in that trace: Schedule focus
reached 16.7 and 18.4 ms rendering hitches. Its first focus still sampled
background keyboard/sticker loading and dynamic-library loading, consistent
with the earlier investigation. The untraced 252 ms callback gap is not a new
rendering-hitch measurement, and this pass does not claim to fix cold keyboard
startup. No Main-thread SQL was recorded in the measured sweep phases.

## Activity implementation

`ActivityTable` consumes `ActivityUiState`; it owns no repository or subscription.
`ActivityScreen` retains its existing model, cache-expiry guards, search binding,
SwiftUI NavigationStack, editors, Import route, toolbar and toast behavior.

The diffable data source identifies rows by transaction ID and sections by the
existing date header. Changed rows are reconfigured without replacing unchanged
visible cells. Self-sizing UIKit labels display the existing formatted strings
and category/type colors. Accessibility text sizes stack and wrap the amount
below the other text. The range control and loaded-empty presentation retain
their actions. Keyboard dismissal on drag preserves the documented SwiftUI
scrolling default ([Apple documentation](https://developer.apple.com/documentation/swiftui/view/scrolldismisseskeyboard(_:))).

An earlier `Equatable` row trial was discarded: its two scrolling runs used
1,916/1,934 ms CPU with 34–37 ms maximum callback gaps. That did not materially
improve the baseline. Initial UIKit pilots were excluded from retained results
until label compression, explicit large-title configuration and visual checks
were complete.

## Measured comparison

Three fresh processes per retained variant; medians:

| Activity workload | Baseline | Table |
| --- | ---: | ---: |
| Five-second scrolling CPU | 1,958.0 ms | 1,235.9 ms |
| Largest scrolling callback gap | 36.1 ms | 16.7 ms |
| Scrolling callback gaps >25 ms, each run | 4 / 4 / 4 | 0 / 0 / 0 |
| Scroll checkpoint RSS | 112.1 MiB | 106.7 MiB |
| Initial load CPU | 262.4 ms | 264.8 ms |

Initial-load CPU is essentially unchanged. Both variants have 7,502 list items
and eight visible cells at the initial checkpoint. Production uses automatic
row heights, not a fixed-height benchmark. The scroll advances eight points per
display callback for five seconds; final offsets differed by only eight points.

| Resume after seven seconds hidden | Baseline CPU | Table CPU | Baseline ready | Table ready |
| --- | ---: | ---: | ---: | ---: |
| Activity | 254.8 ms | 237.7 ms | 98.8 ms | 71.3 ms |
| Plan, unchanged control | 162.4 ms | 166.1 ms | 72.1 ms | 72.8 ms |
| Receipts, unchanged control | 141.5 ms | 143.8 ms | 66.2 ms | 67.3 ms |

Activity resume CPU fell about 7%. All eighteen tab resumes preserved exact row
counts and numeric scroll offsets. No resume performed SQL on Main, and each
process released its tab container and database listeners.

Separate Animation Hitches traces:

| Resume | Baseline maximum | Table trace 1 | Table trace 2 |
| --- | ---: | ---: | ---: |
| Activity | 83.3 ms | 50.0 ms | 33.3 ms |
| Plan | 66.7 ms | 33.3 ms | 66.7 ms |
| Receipts | 33.3 ms | 33.3 ms | 50.0 ms |

The unchanged controls show trace-to-trace variability. These are observations
from three traces, not worst-case bounds or a claim that Activity is hitch-free.

## Regression and visual checks

The physical `activity-table` fixture passed date-header and row-count checks,
visible title/amount updates, the real transaction editor's Save action,
preservation of the same visible cell and its screen position, search,
no-results presentation, every history range, moving an edited transaction to
an older date section, restoration, Add and Import navigation. It checks normal
label separation and verifies all three labels fit at the largest accessibility
text size. The edited visible cell stayed at screen Y=64 before and after update.

Six cycles through all five tabs released the tab controller, Activity table,
Receipt table and database listeners. Ten subsequent Store5 writes performed
exactly the expected twenty validation reads, with zero active listeners or
Main-thread queries.

The full simulator Release build passed. A live iOS 18.6 simulator screen capture
confirmed the large title, search field and populated rows. A separate iOS 26.5
live capture also verified the title, rows and system bottom search field. UIKit `drawHierarchy`
fixture snapshots sometimes omitted navigation content, despite visible view
frames and labels; those images alone were not treated as visual truth. Full-tree
XCTest interaction on the 7,500-row simulator list timed out, so search/edit
assertions above come from the physical fixture's public UIKit controls and
delegates, not a completed simulator typing or VoiceOver test.

`just guard-architecture`, Python syntax compilation and `git diff --check` pass.
No shared Kotlin, Android, desktop, server or transport code changes in this pass.

## Reproduction and evidence

Run the installed Release fixture with `scripts/perf/profile_ios_native_ui.py`
using `activity`, `tab-resume`, `activity-table` or `tabs`. For a live loaded-screen
visual check, launch the fixture with `--perf-ui --ui-screen activity --ui-hold
--ui-loaded-only`; this skips scrolling after loading and capturing the screen.

Record Animation Hitches with Time Profiler and Points of Interest, export
`OSSignpostIntervals`, `time-profile` and `hitches-summary`, then align them with
`scripts/perf/analyze_ios_transitions.py`.

Sanitized cohorts, sweep records, traces and regression checks are in
[`scripts/perf/results/ios-hitch-sweep-2026-10-08.json`](../scripts/perf/results/ios-hitch-sweep-2026-10-08.json).
Raw logs, signed variants, traces, XML exports and screenshots remain locally
under `build/perf/ios-hitch-sweep-2026-10-08/`.
