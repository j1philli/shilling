# iOS cold keyboard and physical regression follow-up — October 9, 2026

The merged Schedule build still has a substantial first-keyboard delay. Both
builds took about 1.1–1.2 seconds to emit the first keyboard-did-show notification
after reboot; another fresh-process recording after prior keyboard use reached
1.41 seconds. The pending physical regression checks passed. This follow-up
changes the performance tooling and fixture; it adds no production UI changes.

## Method and limits

The isolated `finance.shilling.perf` Release app ran on the iPhone SE (2nd
generation), iOS 18.7.8. Baseline production sources are `77dbcd8` (PR #59),
with the same keyboard timing instrumentation as the merged Schedule build,
`2025415` (PR #60). Both use the previously verified Kotlin framework and the
synthetic Store5 fixture. No personal app data or physical iPhone 16 Pro was used.

Each build was installed before a separate full reboot. The user confirmed no
keyboard had been opened after either reboot. Schedule was the first editor,
followed by Account, Category and Transaction, then a repeat of all four. The
focus pause remains 400 ms after the synchronous focus request; typing remains
ten characters at 50 ms intervals plus 200 ms settling. Recorded thermal states
were nominal. Reboot-to-launch delays were not matched, the order was fixed,
and there was only one post-reboot run per build.

Keyboard notification times come from the app's own event stream and include
system animation. They do not establish pixel presentation. The focus-request
timer also includes the small measurement setup before `becomeFirstResponder`.
App CPU includes all its threads. The candidate's Instruments stream was
incomplete, as described below, adding another reason not to infer a causal
performance improvement from the pair.

## Observed Schedule timings

Milliseconds, one observation per build and visit.

| Measurement | Baseline | Merged build |
| --- | ---: | ---: |
| First opening, elapsed / app CPU | 1,053 / 318 | 1,994 / 301 |
| Repeat opening, elapsed / app CPU | 207 / 160 | 234 / 172 |
| First focus request | 894 | 446 |
| First keyboard will show | 1,018 | 508 |
| First keyboard did show | 1,216 | 1,093 |
| Repeat focus request | 57 | 60 |
| Repeat keyboard did show | 622 | 603 |

The merged build returned from its first focus request sooner, but still took
about a second to report the keyboard shown. Its first opening was slower in
elapsed time despite similar CPU. These observations do not establish an overall
cold-start improvement or a fix for cold-keyboard startup. The earlier warm
three-process CPU results should not be extrapolated to this workload.

Work also moves between phases: the baseline's first did-show arrived before
typing began, whereas the candidate's arrived during typing. First focus CPU
was 230/223 ms and typing CPU was 116/291 ms for baseline/candidate. Comparing
those typing intervals as pure text-entry work would be misleading. The
candidate's repeat focus and typing CPU were 124 and 199 ms respectively.

The baseline trace recorded a 333.3 ms first-focus rendering hitch. Its app
event stream recorded 1,231 ms before the first display-link callback, while
the largest callback-to-callback gap was only 18.6 ms. The latter omits the
initial synchronous wait and would badly understate this episode by itself.
First-focus Time Profiler samples included 89 inclusive main-thread samples
under `becomeFirstResponder`, 63 in keyboard delegate setup, and 17 in keyboard
image-cache display. These overlapping counts locate UIKit initialization;
they do not account for all elapsed time or establish exclusive causation.

## Rejecting the incomplete trace

The candidate app completed its workload and wrote all eight keyboard records
and cleanup checks. However, its exported Time Profiler samples span only
0.458–5.570 seconds; first focus starts at 8.014 seconds and the signposted
workload extends to 41.671 seconds. The former analyzer returned zero CPU and
zero hitches for those uncovered phases. Those zeros are rejected, and no
post-reboot rendering-hitch comparison is claimed for the candidate.

`analyze_ios_transitions.py` now rejects empty CPU exports and exports whose
observed sample span does not cover the workload, with a 250 ms boundary margin
for sparse sampling. The error reports both spans. This is a conservative outer
span check, not proof of continuous internal sampling or hitch-stream health.
Regression tests cover complete data, empty/truncated data, sparse boundaries,
and an idle interval bracketed by CPU samples. The real baseline export passes;
the real candidate export is rejected with its truncated span.

An additional recording with an already used system keyboard passed the new
coverage check. Its first Schedule focus request took 225 ms and did-show arrived
at 1,410 ms; the repeat was 59/607 ms. It recorded 21 inclusive background
sticker-initialization samples during focus and another 56 during typing.
First opening reached a 433.4 ms rendering hitch, versus 83.3 ms on repeat;
focus hitches were 33.3/16.7 ms. This recording verifies that useful trace data
can still be collected and that a fresh-process first-use delay can recur without
another reboot. It is not a replacement cold comparison. Opening Schedule first
in this fixture also includes framework initialization that earlier cohorts,
which opened Account and Category first, had already paid for.

## Physical regression checks

All seven physical suites passed using production UI from PR #60:

- All eight recurrence frequencies, refreshed checked menu items, nth-weekday
  persistence and complete original-draft restoration.
- Save within the title debounce interval in all four editors, including
  Schedule/Transaction amount and date persistence and restoration.
- Account/category/transfer choices, direct field switching, keyboard Done and
  the largest accessibility text size.
- Twelve editor lifetime and parent-rebuild checks, plus post-close writes.
- Six cycles through all five tabs and release of the native lists/controllers.
- Activity, Plan and Receipts scroll restoration after seven seconds hidden.
- Plan sections, grouping, periods, filters and final listener cleanup.

The original resume fixture first failed because it hard-coded a Plan overview
range of 1,000–1,009 rows. Today's seeded data rendered 1,364, including postings
in the current date range. The fixture now scopes list lookup to the selected
tab, records its loaded row count, and requires that exact count on return.
This also prevents the outgoing Activity list from satisfying the lookup.
The first five suites used the frozen merged app; resume and Plan were rerun
after rebuilding this fixture-only correction.

The corrected resume run preserved all rows and scroll offsets: Activity
7,502 rows at offset 1,304; Plan 1,364 at 1,252; Receipts 251 at 1,252.
No checks recorded main-thread SQL. Closed screens released their listeners,
and post-close mutations made exactly the twenty Store5 validation reads
expected for ten writes. Both post-reboot navigation event streams also passed
their cleanup and mutation checks.

## Reproduction and remaining work

Build the Release perf fixture, then run the existing physical runner with
`--screen editor-navigation --editor-first schedule`. For first-keyboard work,
install before reboot and unlock without opening another keyboard. A fresh app
process alone is not a cold keyboard. Record Animation Hitches with Time Profiler
and Points of Interest, then check the export coverage before interpreting zeros.

The fixture Release build, four analyzer tests, real-export acceptance/rejection,
the additional warm recording with complete sample coverage,
Python compilation, diff check and architecture guard passed. Compact evidence
is in `scripts/perf/results/ios-cold-keyboard-2026-10-09.json`; full traces and
exports remain local under `build/perf/ios-cold-keyboard-2026-10-09/`.

First-keyboard startup, Schedule opening and Plan section rendering remain
performance targets. This pass closes the pending physical regression checks
and records first-keyboard latency; it does not supply a valid candidate cold
rendering trace or establish an additional app-side optimization.
