# iOS Receipt rendering — October 7, 2026

This follow-up isolates the cost of reattaching a SwiftUI list during a native
tab switch, then replaces the Receipt list with a self-sizing UITableView. The
existing SwiftUI navigation stack, editors, model observation and shared Store5
boundary remain in place. No extra view cache or hidden-screen subscription is
introduced.

## Attribution

All measurements use the isolated `finance.shilling.perf` Release app on the
physical second-generation iPhone SE, iOS 18.7.8 (22H352). The fixture seeds
10,001 postings, 1,000 schedules and 250 receipts through the synthetic Store5
graph. Baseline production code is `e490daf` (PR #55). Swift-only variants reuse
the same verified Release Kotlin framework. This audit uses no personal data.

The new `tab-isolation` fixture holds the outgoing Settings screen and native
transition constant while changing the incoming controller. Each controller
loads, scrolls where applicable, spends seven seconds hidden, then resumes. A
first pilot gave these app-process CPU times:

| Incoming screen | CPU | List readiness |
| --- | ---: | ---: |
| UIKit label | 89 ms | n/a |
| SwiftUI text | 90 ms | n/a |
| SwiftUI NavigationStack + text | 117 ms | n/a |
| Static SwiftUI List | 135 ms | 62 ms |
| Static List + NavigationStack | 158 ms | 89 ms |
| Production Receipts | 166 ms | 91 ms |

A UIKit outgoing-screen pilot reduced later transition CPU, but the list's
initial readiness remained around 62/89 ms. Buffered public hosting-controller
callbacks showed stable appearance, size classes and safe-area insets during
resume; this does not rule out internal SwiftUI environment invalidation.

A matched UITableView control inside the same SwiftUI NavigationStack used the
same 375×667 viewport, 64/49-point adjusted insets, 250 data rows, and 12 visible
60.5-point cells. Across three fresh processes its median CPU fell from 163.2 to
130.5 ms and list readiness from 89.5 to 61.7 ms. In a paired Animation Hitches
trace, the maximum fell from 83.3 to 33.3 ms. The native control has fixed heights
only as a benchmark; production uses automatic row sizing.

Earlier row-only UIKit wrappers were excluded after visual checks found clipped
titles and missing amounts. An initial whole-table pilot also had a smaller
viewport and truncated amount accessories; only the corrected matched cohort
above is used for the control comparison.

Other production pilots were reverted: disabling the hosting container's safe
area left Receipt readiness at 89.5 ms; a compact title gave 91.2 ms; removing
the tab animation reduced total CPU but worsened initial readiness to 96.1 ms.
Those single-process pilots do not justify retaining the changes.

## Production results

Three fresh processes per variant, including a new baseline reinstall after the
candidate cohort. Medians are app-process CPU time and list hierarchy readiness:

| Resume | Baseline CPU | Table CPU | Baseline ready | Table ready |
| --- | ---: | ---: | ---: | ---: |
| Activity | 253.8 ms | 253.9 ms | 95.9 ms | 96.6 ms |
| Plan | 164.5 ms | 169.2 ms | 75.9 ms | 71.5 ms |
| Receipts | 165.2 ms | 143.7 ms | 87.6 ms | 64.6 ms |

Receipt CPU decreased 13% and readiness 26%. Activity and Plan are controls, not
changed implementations; their small differences illustrate run-to-run variation.
Every resume preserved its exact row count and numeric scroll offset, with no
Main-thread SQL. All six processes released the container and database listeners.
All recorded thermal states were nominal.

Separate Animation Hitches traces, with Time Profiler and signpost alignment:

| Resume | Fresh baseline | Final trace 1 | Final trace 2 |
| --- | ---: | ---: | ---: |
| Activity | 83.3 ms | 83.3 ms | 83.3 ms |
| Plan | 66.7 ms | 66.7 ms | 66.7 ms |
| Receipts | 83.3 ms | 50.0 ms | 33.3 ms |

A preceding production-table pilot also measured 50.0 ms. Earlier baseline
traces in [PR #55's audit](ios-tab-rendering-followup-2026-10-07.md) reached
83.3/100.0 ms. The repeated improvement supports retaining the table, but the
remaining peaks still exceed one 60 Hz frame, and Activity/Plan hitches remain.

In two five-second scrolling runs per variant, median CPU fell from 1829.2 to
1102.7 ms (40%). The candidate recorded no display-callback gaps above 25 ms;
the baseline recorded five and three, with maxima of 35.6/35.8 ms versus
16.8/16.7 ms. The fixture advances eight points per callback, so final offsets
were 2312/2336 versus 2344/2344: the faster variant covered slightly more distance.
These callback measurements are not Instruments rendering-hitch measurements.

Median RSS at the Receipt resume checkpoint was 170.7 MiB for baseline and
162.3 MiB for the table. The Activity/Plan checkpoints were 142.1/159.7 versus
141.6/158.0 MiB. This small cohort suggests less retained UI work at that moment;
it does not establish a peak-memory or long-session improvement.

## Production implementation

`ReceiptTable` consumes `ReceiptsUiState` and dispatches filter/navigation
callbacks. Stable receipt IDs drive a diffable data source; changed visible rows
are reconfigured, and unchanged snapshots do no list work. Apple documents
[reconfiguration](https://developer.apple.com/documentation/uikit/nsdiffabledatasourcesnapshot-swift.struct/reconfigureitems(_:))
as preserving existing cells and allowing self-sizing updates. Section footer
changes are handled separately from receipt items.

UIKit labels render populated receipt rows without per-row SwiftUI hosting
views. Dynamic Type uses automatic row heights and a vertical amount layout at
accessibility sizes. The empty state retains SwiftUI ContentUnavailableView in a
single [UIHostingConfiguration](https://developer.apple.com/documentation/swiftui/uihostingconfiguration).
The segmented filter, Add action, pushed receipt editor, toast and camera route
remain available.

## Verification

The `receipt-table` fixture exercises both filters, updates a visible receipt via
the existing editor model, opens the correct editor, clears its amount using the
native field and Save action, verifies the displayed row position, switches to
the largest accessibility text size, removes/restores all synthetic receipts,
and opens the Add editor. It restores edited values and checks controller and
database-listener release. A separate six-cycle run visits all five tabs and
confirms that both the tab container and Receipt list are deallocated. Ten later
Store5 writes produce only their 20 expected validation reads, with no surviving
listeners or Main-thread SQL.

The update test checks the cell's actual screen position: UIKit can change its
numeric content offset when estimated section dimensions settle while preserving
the displayed position. In the passing run, the same cell stayed at y=64 points
while its offset changed from 1235 to 1252. The accessibility test caught and
fixed a clipped amount in the prototype; the final amount fits its full measured
multiline height. Screenshots cover populated, edited, empty and large-text views.

Release Swift builds, Python compilation, CLI argument validation, architecture
guard and diff checks pass. The Kotlin framework and Android sources are
unchanged; the earlier physical Pixel 6/shared regression coverage is recorded
in [the mobile audit](mobile-regression-ios-rendering-2026-10-07.md). The current
measurements apply to this SE/iOS combination and the stated synthetic workload.
They do not establish performance on every supported device or OS release.

## Measurement notes and reproduction

CPU milliseconds are total app-process CPU time, not utilization. List readiness
means populated cells were found in the hierarchy, not that pixels reached the
display. First-callback latency and later callback gaps both matter. Rendering
claims use separate Animation Hitches traces with Time Profiler and Points of
Interest, aligned by the fixture signposts. Inclusive CPU categories and hitch
contexts overlap and must not be summed as wall-time percentages. RSS checkpoints
are not a peak-memory or long-session measurement.

```sh
python3 scripts/perf/profile_ios_native_ui.py --device "$DEVICE_ID" \
  --screen tab-isolation --isolation-probe staticReceipts \
  --isolation-probe nativeTable --runs 3 --label controls --output /tmp/controls.json
python3 scripts/perf/profile_ios_native_ui.py --device "$DEVICE_ID" \
  --screen receipt-table --runs 1 --label interactions --output /tmp/interactions.json
python3 scripts/perf/profile_ios_native_ui.py --device "$DEVICE_ID" \
  --screen tab-resume --runs 3 --label candidate --output /tmp/resume.json
```

Raw local traces, signed variants, screenshots and rejected patches are under
`build/perf/ios-hosting-isolation-2026-10-07/` (ignored).

[Sanitized samples and aligned traces](../scripts/perf/results/ios-receipt-rendering-2026-10-07.json).
