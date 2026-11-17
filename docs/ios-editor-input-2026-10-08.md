# iOS editor input and remaining hitches — October 8, 2026

Native title/amount fields reduce Transaction editor input work. Schedule opening
and Plan transitions remain unresolved: tested replacements regressed rendering
or failed to improve the measured workloads. Their production views are unchanged.

## Scope and method

Baseline is `fe34f24` (PR #57). Measurements use the isolated
`finance.shilling.perf` Release app on the physical iPhone SE (2nd generation),
iOS 18.7.8 (22H352), with synthetic Store5 data: 10,000 seeded postings,
1,000 schedules and 250 receipts. The normal Shilling app and personal data are
not used. Kotlin/data/Android sources are unchanged; Swift builds reuse the
verified Release Kotlin framework. Recorded thermal states are nominal.

Each editor cohort launches three fresh app processes, visits each of four
editors twice, types ten characters at 50 ms intervals, and includes 200 ms
settling after typing. The title publishes after 120 ms, so its final model
update is included. App-process CPU includes all app threads. Callback gaps
omit work before the first callback; that latency is recorded separately.
Neither measurement establishes when pixels were rendered.

Animation Hitches traces include Time Profiler and Points of Interest, aligned
to the same workloads. The earlier editor baseline trace from PR #57 is reused:
Editor and Plan production code was identical before and after that PR.
Inclusive stack counts overlap and must not be added into wall-time percentages.
Fresh app processes do not reset the separate system keyboard process.

## Retained implementation

`EditorNativeTextField` uses public UITextField first-responder and accessory
APIs. It creates its Done toolbar on first focus. Focus no longer changes the
Transaction editor's SwiftUI keyboard toolbar. Schedule retains all its existing
SwiftUI fields, focus handling and Done control.
Amount alignment follows SwiftUI's layout direction.

Titles still update the plain draft immediately and debounce model publication
for 120 ms. Save flushes the draft synchronously, as before. Amount changes go
through the existing screen-model setter immediately. Dismantling cancels pending
publication and releases the callback. Navigation, validation, Store5 writes,
delete, receipts and undo remain in the existing screens/models.

The native fields grow with their intrinsic font height, with a 30-point minimum.
The existing SwiftUI date controls are retained. A compact native
date-picker prototype initially collapsed to a 1×1 frame because UIDatePicker
reported `(-1, -1)` intrinsic size; those numbers are excluded. Its corrected
115.5×34.5-point version persisted dates correctly, but two traces showed a
repeat Schedule opening regression from 83.3 to 100 ms. It was discarded.

## Transaction comparison

Medians of three fresh processes, milliseconds. Schedule and Plan were restored
before measuring this final variant. Visit 1 is the first Transaction visit in
each process; visit 2 is the repeat.

| Workload | Baseline | Native fields |
| --- | ---: | ---: |
| Opening, visit 1 | 182.7 | 184.2 |
| Opening, visit 2 | 163.9 | 151.3 |
| Focus, visit 1 | 113.4 | 109.5 |
| Focus, visit 2 | 137.8 | 136.8 |
| Typing, visit 1 | 255.4 | 242.1 |
| Typing, visit 2 | 255.6 | 237.1 |

Transaction typing CPU fell 5.2%/7.2%; repeat opening CPU fell 7.7%. First
opening CPU was unchanged. Focus CPU changes were small. Median focus callback
gaps fell from 26.7/41.4 to 16.7/20.5 ms, while first-callback latency rose from
26.7/47.6 to 31.2/51.7 ms. Typing gaps were 16.7/16.7 ms in the baseline and
33.3/16.7 ms with native fields. These are mixed scheduling metrics, not a
claim that every responsiveness metric improved or that cold keyboard startup
is fixed.

The final Animation Hitches trace recorded Transaction opening at 66.7/50.1 ms,
versus 66.7/66.7 ms in the earlier baseline. Focus was 0/16.7 ms versus
16.7/16.7 ms; typing was 0/0 ms in both. The unchanged Schedule control was
116.7/83.4 ms versus 116.7/83.3 ms. These are single-trace observations, with
trace-to-trace variability, rather than a bound on future hitches.

## Plan investigation

The fresh production Plan trace recorded 50 ms hitches on the first Schedules,
Categories, Accounts, Overview and By-category transitions. Repeated Schedules
reached 83.3 ms, Accounts 66.7 ms; By-day stayed at 16.7 ms on both visits.
Repeated visits made no database reads. First Schedules had 40 inclusive
view-graph/collection-layout samples, 31 cell-creation and 23 cell-sizing
samples. First By-category had 48 view-graph samples. The remaining cost is
concentrated in presentation updates and list layout.

Six three-process experiments were discarded:

| Experiment | Reason |
| --- | --- |
| Reusable table rows with hosted controls | Overview and By-day CPU/gaps regressed. |
| Native table controls as well as rows | Some transitions improved, but By-day gaps stayed near 58 ms versus 16.7 ms. |
| Reload data on whole-presentation changes | Repeat Accounts/Categories improved, but By-day still regressed. |
| Faster reload and font-derived ordinary row heights | Repeat section CPU improved 13–32%; By-day gaps still reached 57.5 ms. |
| Preserve the Overview list with native entity sections | Repeat Schedules CPU rose from 100.0 to 127.9 ms. |
| Native category summary inside the existing list | Repeat By-category CPU rose from 93.0 to 99.1 ms. |

Schedule field trials also failed the rendering comparison. The natural-height
native-field trace retained a 116.7 ms first Schedule opening and increased its
repeat from 83.3 to 100 ms. That implementation was reverted. A separate native
weekday-button trial did not reduce opening CPU and increased some focus/typing
work. It was also discarded. No hidden list cache,
native Plan table, or weekday replacement is shipped.

## Regression checks and limitations

The physical fixture verifies native category/account/transfer menus, selection
refresh after changing an account, keyboard Done on both fields, direct title-to-
amount focus switching, and largest accessibility text sizes. All four editors
save within the title's debounce window; Schedule and Transaction additionally
persist changed amounts and dates, then restore the synthetic records. Twelve
editor lifetime/parent-rebuild checks release controllers and listeners without
extra reads. Six cycles across all five tabs release Activity, Plan and Receipt
lists plus their container. Post-close writes make exactly the expected twenty
Store5 validation reads, with no remaining listeners or Main-thread SQL.

The roughly 250 ms cold keyboard episode from the earlier sweep did not recur
in these cohorts. Earlier samples included iOS keyboard/sticker initialization
and dynamic-library loading. This pass does not claim to fix that system startup
cost. Schedule opening and Plan section transitions remain optimization targets.

The Release iOS 26.5 simulator build passed. A live T3 Device-panel review
confirmed Transaction layout, editable title/amount fields, direct switching
from the text keyboard to the decimal pad, and the native Done button dismissing
the keyboard. The physical iOS 18.7.8 fixture provides the persistence, large-text
and lifetime checks. No physical iPhone 16 Pro was used.

Raw measurements, rejected-candidate summaries and aligned traces are retained
in `scripts/perf/results/ios-editor-input-2026-10-08.json`. Raw Instruments exports
and app variants remain under `build/perf/ios-plan-editor-2026-10-08/` locally.
