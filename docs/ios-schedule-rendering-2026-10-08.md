# iOS Schedule rendering and remaining hitches — October 8, 2026

Schedule opening and typing use less CPU with ready-state draft initialization,
deferred recurrence menus, and the existing native editor fields. Opening still
hitches. Plan experiments did not establish a reliable rendering improvement and
were discarded. A large keyboard pause recurred; a post-reboot comparison remains
blocked by the test phone being unavailable.

## Scope and method

Baseline is `77dbcd8` (PR #59). Measurements use the isolated
`finance.shilling.perf` Release app on an iPhone SE (2nd generation), iOS 18.7.8,
with synthetic Store5 data: 10,000 seeded postings, 1,000 schedules, 250 receipts,
and the fixture's fixed update probe. No personal data or physical iPhone 16 Pro
was used. Kotlin, Android, web, desktop, sync and server sources are unchanged.

Each editor cohort uses three fresh app processes and two visits per editor.
Focus lasts 400 ms; typing sends ten characters at 50 ms intervals followed by
200 ms settling, including the title's final debounced publication. CPU includes
all app threads. Display-link gaps and first-callback latency measure callback
scheduling, not rendered frames. Recorded thermal states were nominal.
The cohorts were sequential, not randomized or interleaved, so unrelated
variation remains possible. Fresh processes do not reset the system keyboard.

Animation Hitches traces include Time Profiler and Points of Interest. Hitches
are assigned by their start time; push ends when the field is ready, followed
by a separate settling interval. Single-trace results are observations, not
guarantees. Inclusive CPU stack counts overlap and are not additive.

## Shipped changes

The Schedule screen owns the same FlowModel, but creates its form after the
ready state arrives. Local drafts start with that snapshot, avoiding a second
form update from `onAppear` during opening. Name and Amount reuse
`EditorNativeTextField`, already used by Transaction. Their Done accessory is
created on first focus; month-day and multiline Notes retain their SwiftUI
focus handling. Name edits update the local draft immediately and publish after
120 ms. Save flushes the draft synchronously. Amount uses the existing immediate
setter. Existing date controls, validation, save/delete and Store5 paths remain.

Repeats, Week and Day reuse `EditorChoicePicker`. Their native menus are built
when opened rather than while the form opens. The choices, checked item and
setter are refreshed from the current state.

## Schedule measurements

Median app CPU across three processes, milliseconds. Visit 1 is the first
Schedule visit in each process, after Account and Category; visit 2 is its repeat.

| Workload | Baseline visit 1 / 2 | Candidate visit 1 / 2 |
| --- | ---: | ---: |
| Opening | 205.9 / 178.6 | 189.7 / 167.4 |
| Opening settle | 62.3 / 64.4 | 59.4 / 60.6 |
| Focus | 203.2 / 148.4 | 109.9 / 139.2 |
| Typing | 314.1 / 257.2 | 254.9 / 239.4 |
| Closing | 229.5 / 221.0 | 229.3 / 223.2 |

Opening CPU fell 7.9%/6.3%; typing fell 18.8%/6.9%. First-focus CPU fell sharply,
but keyboard initialization can land in that interval, so that entire difference
cannot be attributed to the field change. The unchanged Transaction control's
opening CPU also fell from 182.6/164.3 to 179.7/155.6 ms, showing cohort variation.

Median opening callback gaps were 107.1/92.5 ms before and 94.6/89.0 ms after;
first-callback latency was 44.5/33.3 versus 36.7/35.4 ms. Focus gaps fell from
38.3/43.1 to 18.7/28.0 ms, but first-callback latency rose from 28.9/56.5 to
33.8/62.0 ms. These mixed callback metrics do not prove hitch removal.

The candidate trace recorded Schedule opening hitches of 100.0/83.4 ms versus
116.7/83.4 ms in the prior pass's matched production baseline trace. Focus was
0/16.7 versus 16.7/33.3 ms; typing was 0/0 in both. Closing stayed near 50 ms.
First opening improved by one frame in this trace; repeat opening did not.

## Plan experiments discarded

| Experiment | Result |
| --- | --- |
| Recreate the list using section identity | Repeat section CPU increased roughly 25–35%; Overview gaps reached 100 ms. |
| Suppress list transaction animations | No consistent benefit; Overview worsened. |
| Replace all four segmented pickers | Some CPU reductions, without a consistent section-rendering improvement. |
| Replace only the Group picker | By-category CPU fell about 5%; By-day about 5–8%, but callback results were mixed. |
| Replace navigation-link rows with buttons | Small first Categories/Accounts CPU reductions; repeat and Schedule results did not establish a benefit. |

An older baseline had 50/33 ms By-category hitches. A fresh baseline instead
recorded 16.7/16.7 ms, matching the Group-only candidate. Thus the apparent hitch
improvement did not survive the fresh control. The planned interleaved CPU
comparison could not proceed after the device became unavailable. The extra
native picker is not shipped. Plan's production code remains unchanged.
Fresh baseline section transitions still reached roughly 50–67 ms, with
view-graph updates, collection layout and cell creation/sizing in the samples.

## Keyboard investigation and fixture changes

An intermediate Schedule variant with initialized drafts and deferred menus,
but SwiftUI Name/Amount fields, reproduced a 216.7 ms focus hitch and a 116.7 ms
typing hitch. Its first Schedule focus included 75 inclusive CPU samples under
`_UIRemoteKeyboards setStickerPrewarmingViewControllerEnabled` and `dlopen`.
In the largest hitch window, the app had 77 ms of sampled CPU and only 13
main-thread samples. These stacks locate overlapping iOS keyboard/library
initialization; they do not prove that one frame or all delay belongs to it.
The final native-field trace did not reproduce that large episode.

The fixture now records `becomeFirstResponder`, keyboard-will-show and
keyboard-did-show timing separately, and supports `--editor-first schedule`.
Notifications include system animation and do not establish pixel presentation.
Observation continues through typing without lengthening the existing focus
window. An earlier prototype waited for the notification and changed the focus
workload; its focus/typing cohort results are excluded from comparisons.

The SE was rebooted for a truly cold baseline, but did not reconnect to Xcode.
No post-reboot baseline/candidate pair was obtained. Cold-keyboard startup
remains unresolved; no speculative keyboard prewarming or private API is added.

The new recurrence fixture visits all eight frequency choices, verifies fresh
checked items, saves an nth-weekday recurrence, then restores the original draft.
The rapid-Save fixture also now restores a complete original Schedule draft:
restoring only its date could leave behind a derived weekly mask.

## Verification and remaining work

Physical editor cohorts passed navigation teardown and post-close mutation
checks: no surviving listeners, no main-thread SQL, and exactly the twenty
Store5 validation reads expected from ten writes. The Schedule performance
candidate also contained the experimental Plan Group control; that control was
not mounted by the editor workload and has since been reverted.

Release device and iOS 26.5 simulator builds passed using the previously verified
Release Kotlin framework. Simulator checks passed for rapid Save in all four
editors, recurrence persistence/restoration, refreshed category/account/transfer
menus, native Done, direct field switching, largest accessibility text, twelve
editor lifetime/rebuild cases, six cycles across all five tabs, scroll restoration,
Plan periods/filters and listener cleanup. After reverting Plan, its transitions
and the Schedule-first navigation/timing path were rechecked on the final build.

A live Device-panel review also created, saved, reopened and deleted a temporary
Schedule, and exercised month-day and multiline Notes keyboard Done. The temporary
record was removed. Simulator timing is not used as physical performance evidence.
`python3 -m py_compile`, `git diff --check` and `just guard-architecture` passed.
New recurrence and Save-restoration checks still need a physical iOS 18 rerun;
the final phone build is ready when the SE reconnects.

Remaining targets are Schedule's 83–100 ms opening, Plan's 50–67 ms section
transitions, and a controlled cold-keyboard comparison. Compact samples, aligned
trace summaries and regression evidence are in
`scripts/perf/results/ios-schedule-rendering-2026-10-08.json`. Full local traces,
exports and rejected app variants are under
`build/perf/ios-remaining-rendering-2026-10-08/`.
