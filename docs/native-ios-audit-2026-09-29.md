# Native iOS performance audit — September 29, 2026

## Baseline and rebase

The audit branch now descends from **public `j1philli/shilling` main `2abf1db`**
(native SwiftUI migration). It tracks `public/main`; `origin` still names the
archived private repository. Nothing was pushed.

The prior uncommitted audit was checkpointed at `286f758` and retained on
`backup/performance-audit-before-public-rebase`. Because public and private have
separate histories, only the audit checkpoint was rebased onto public main.
Main's shared session, Koin graph, view models, native UI, and SQLDelight plugin
remain in place. Removed Compose iOS and old History/Weekly/Budget screens were
not restored. Applicable data, transfer, server, worker and native storage fixes
were carried forward. The synthetic Android UI fixture now uses Activity/Plan.

Earlier Pixel/iPhone reports describe the private-based implementation. They do
not establish performance of public main's new UI. Main already uses Ktor 3.6.0.

## Changes in this pass

### Native editor model lifetime

Seven editor/onboarding/import view initializers constructed their Kotlin screen
host before passing a `FlowModel` to `StateObject`. SwiftUI can rebuild these view
values while keeping the original state object, leaving the newly constructed
hosts unused. Several shared editors start eager collectors, so this also risks
retaining database subscriptions until explicitly cleared.

`FlowModel` now constructs the Kotlin host inside the deferred `StateObject`
initializer. Its existing destructor still clears the retained ViewModelStore.
This follows Apple's documented [StateObject initialization behavior](https://developer.apple.com/documentation/swiftui/stateobject/init(wrappedvalue:)).
This finding was established by code inspection and compilation; allocation counts
have not yet been measured on the SE.

### UI dispatcher work

- Activity, Home and Receipts state projections now run upstream on
  `Dispatchers.Default`, with their StateFlows still consumed by native SwiftUI
  and Compose on the UI side.
- CSV decoding, header/date guessing, parsing, duplicate review, row formatting,
  and import orchestration run off the UI dispatcher. The parsed-row stream is
  shared so review and duplicate detection do not independently parse the file.
  Its subscriptions stop when the import screen is no longer observed.
- The native CSV picker keeps its security-scoped URL access active across an
  asynchronous Kotlin file read on `Dispatchers.IO`. Reading/copying the selected
  file no longer happens synchronously in the SwiftUI callback. Selection/import
  buttons are disabled while loading; read failure is reported.
- Native Quick Look preview creation now uses a suspend bridge and bounded
  Store5 receipt reads (256 KiB maximum per read) on `Dispatchers.IO`. It no
  longer bypasses Store5 or copies the entire file synchronously on Main.

The rebased iOS session also uses the previously profiled native WebRTC receiver
and suspending timer through `WebRtcPlatform`; the old dispatcher polling delay
was removed from `IosKoin`.

### Activity transfer pairing

The shared Activity/Home projection searched the entire posting list for every
ad hoc transfer partner. It now indexes candidates by pair id once, preserving
scheduled `_dr`/`_cr` pairing, orphan handling, row order, and debit-side display.

Synthetic host-JVM comparison, 15 alternating samples per variant and size:

| Posting rows | Original median | Indexed median |
| ---: | ---: | ---: |
| 1,000 | 0.634 ms | 0.609 ms |
| 10,000 | 77.867 ms | 0.771 ms |
| 20,000 | 266.065 ms | 1.516 ms |

Every pair consists of two ad hoc transfer legs. Each size had five warmups per
variant; early cohorts include JIT compilation effects. The benchmark verifies
identical results. These are CPU projection timings on macOS arm64/OpenJDK
25.0.4.1, **not physical iPhone latency or frame measurements**. Separate tests
compare mixed, shuffled histories, credit-first pairs and unmatched legs.

[Raw samples and methodology](../scripts/perf/results/public-native-activity-2026-09-29.json)
and [runner](../scripts/perf/bench_activity_projection.py).

## Validation

- JVM suite: **131 tests passed**, including Store5, binary/staged receipt transfer,
  server/authentication, transfer-pairing equivalence, and CSV review/import
  preserving duplicate exclusion, selection and categories.
- Full unsigned native arm64 iOS app: **Xcode Debug build succeeded**, including
  SwiftUI, generated async bridges, Kotlin framework linking and widget extension.
- Android app and isolated performance app: **Release builds succeeded**.
- Web: **Wasm debug application build succeeded**.
- Separate iOS performance fixture: **arm64 Kotlin compilation succeeded**.
- Web worker persistence regression passed.
- `just guard-architecture` and `git diff --check` passed.

The first web build encountered stale pre-rebase Compose resource accessors.
Regenerating only the local shared-UI resource cache fixed the build; no generated
sources or icon files were changed in git.

## Next profiling workloads

1. Native SE launch and populated Activity/Receipts scrolling, using a separate
   synthetic app sandbox; measure frame time, allocations and main-thread stalls.
2. Repeated editor navigation: verify live Kotlin hosts/collectors return to their
   baseline after dismissal and parent updates.
3. Plan currently observes overview, schedules, categories and accounts together,
   even while only one section is visible. Measure hidden-section work before
   changing subscription lifetimes.
4. Home still reads a year of posting details to display three merged rows. A
   bounded Store5 projection must preserve transfer partner information.
5. CSV duplicate detection still scans existing postings per row; benchmark and
   index matching while preserving its case-insensitive and half-cent tolerance.
6. Native startup still initializes/checks the database through `runBlocking`
   before showing the root view. Trace that separately from network bootstrap.

The rebase pass above did not include physical iPhone UI or Pixel frame measurements.

## Physical SE follow-up

The follow-up uses a **physical iPhone SE (2nd generation), iOS 18.7.8**, with an
optimized, signed Release build of `finance.shilling.perf`. Its native UI mode
compiles the production SwiftUI screens and Kotlin screen facades directly through
relative source symlinks. The ordinary transfer benchmark remains the default mode.

The fixture has its own database, uses the production Store5 repositories/stores,
and does not start authentication, signaling, or a sync session. Synthetic data:
10 accounts, 40 categories, 1,000 weekly schedules, 10,000 postings (including
paired transfers), and 250 receipt metadata entries. The update workload changes
one additional fixed posting ten times. Receipt file decoding is outside this test.

### Findings and fixes

1. **Hidden Plan sections kept doing database and formatting work.** The Swift
   model subscribed to all four section StateFlows. While Accounts was displayed,
   ten posting updates caused 140 database reads on Main and about 3.09 seconds of
   process CPU time. Plan now observes only the selected section; a separate task
   continues listening for navigation requests. Switching sections cancels the
   prior collector, allowing the existing five-second `WhileSubscribed` timeout
   to stop upstream work.
2. **Overview built both grouping projections, including collapsed details.**
   Time Profiler recorded 2,404 inclusive samples in `groupByCategory`, with 2,328
   under system time-zone resolution. Repeated native time-zone file opens and
   reads dominated this path. Overview now builds the selected grouping and only
   formats a category's detail rows when expanded. Group counts and totals still
   include every occurrence. Date formatting reuses the existing reference date.
3. **Plan reads and projections ran on the UI dispatcher.** All four shared Plan
   projections now run upstream on Default. Overview publishes its action lookup
   map through a thread-safe StateFlow snapshot. Schedules index accounts and
   categories once instead of scanning them for each schedule.
4. **Receipts also repeatedly resolved the system time zone.** All 104 sampled
   receipt-projection stacks in the baseline CPU trace included that operation.
   Receipts now take one date/time-zone snapshot per projection and reuse it for
   every row. The zone is resolved again on the next projection, so a process-wide
   cache cannot retain an obsolete user time-zone setting.

### Plan measurements

Three fresh processes per build, with the already seeded database and nominal
thermal state. Medians below exclude the separate Instruments runs. Baseline is
the production UI at `d715790`; the candidate contains the Plan fixes above.

| Workload / metric | Before | After |
| --- | ---: | ---: |
| Overview: populated list load | 439.5 ms | 196.3 ms |
| Overview: load CPU time | 519.8 ms | 175.6 ms |
| Overview: main-thread read calls | 24 | 0 |
| Accounts visible, ten updates: CPU time | 3,085.8 ms | 104.0 ms |
| Accounts visible, ten updates: read calls | 140 | 0 |
| Accounts visible, ten updates: largest callback gap | 280.5 ms | 16.7 ms |
| Accounts visible, ten updates: gaps over 25 ms | 14 | 0 |
| Overview: five-second scroll CPU time | 1,824.6 ms | 1,818.3 ms |

The update workload still writes the ten postings through Store5. Zero read calls
means the hidden projections stopped reading; it does not mean no database writes
occurred. The workload includes three seconds of scheduled pauses and a final
one-second settling pause. Its elapsed time decreased from 6.81 to 4.21 seconds
because the former main-thread work also delayed completion callbacks.

Scrolling itself did not materially improve in this cohort: Plan still had about
eight callback gaps over 25 ms per five-second scroll. This pass primarily removes
load work and stalls caused by updates to hidden sections.

Separate 40-second Time Profiler recordings corroborated the change: **nine
270–277 ms main-thread microhangs before, zero after**, at the instrument's 250 ms
reporting threshold. Total sampled CPU time was 6,397 ms before and 3,417 ms after;
main-thread samples fell from 5,533 to 2,876. The category-formatting stack no
longer appeared. These trace totals include launch, scrolling, updates and idle
time, and are not interchangeable with the phase-specific process CPU medians.

### Receipts and Activity

Receipts was measured in three fresh processes before and after its date/time-zone
change. Median populated-list load fell from **224.2 to 183.1 ms**; load CPU fell
from **266.1 to 159.3 ms** (40% less). Both builds performed one Store5 read off
Main. Five-second scroll CPU stayed near 1.84–1.85 seconds, with three callback
gaps over 25 ms at the median. No scroll-speed improvement is claimed.
The separate receipt CPU traces contained 104 projection samples before and one
after; no system-zone resolution appeared in that remaining sample. Sampling
does not imply that resolving the zone once has zero cost.

Activity was profiled as a control with no additional change in this follow-up:
the 10,000 seeded postings rendered about 7,500 merged transaction rows. Three-run
median load was 281.0 ms, CPU 290.1 ms, with eight reads and none on Main. During
five-second scrolling, median CPU was 1.88 seconds, with two callback gaps over
25 ms. Activity, Receipts and Plan all had a 16.7 ms p95 scroll callback interval.

[Raw measurements, navigation checks, medians and sanitized CPU summaries](../scripts/perf/results/native-ios-ui-2026-09-29.json).

### Follow-up validation

- Signed Release arm64 native fixture build and installation succeeded, compiling
  the actual production SwiftUI screens and generated Kotlin async bridges.
- **106 shared JVM tests passed**, including two new integration tests for Plan
  grouping/expansion, summaries, amount/skip undo, mark/unmark actions, references,
  filtering, live updates, and query dispatcher placement through real Store5.
- All 18 native section/grouping checks passed; populated screenshots were inspected.
- A final installed-build Plan smoke run passed six further navigation checks,
  loaded in 196.4 ms, and performed zero hidden reads during the ten updates.
  The synthetic native fixture was left visible on the SE.
- `just guard-architecture` and `git diff --check` passed. Data access remains
  inside Store5/repositories; no server or transport change was made in this follow-up.

### Measurement boundaries

- Load runs from mounting the native screen to a populated `UICollectionView`
  with visible cells, followed by a 50 ms display allowance. Screenshots were
  inspected. This is **screen loading, not full production app cold launch**.
- Scroll advances the real native list by eight points per display callback for
  five seconds. `CADisplayLink` timestamp gaps indicate callback starvation; they
  do not independently measure GPU presentation time or establish frame rate.
- CPU time is process user + system time. Time Profiler uses statistical samples;
  inclusive stack counts overlap. Screenshots occur outside measured intervals.
- The Plan update test waits seven seconds after switching to Accounts, longer
  than the upstream stop timeout. Optional navigation checks subsequently return
  to Overview, switch grouping, and visit Schedules, Categories, and Accounts.
  All 18 checks passed across three runs, including rendering after resubscription.
- Three runs establish a repeatable local improvement, not a population-wide
  percentile. Keyboard/search, editors, receipt decoding, full app launch, Home,
  and the production tab-container lifetime still need separate workloads.

### Reproducing the fixture

Build `app/perf-ios/module.xcodeproj`, scheme `app`, Release, for a signed physical
device and install the resulting app. As with the normal iOS module, the first
build may generate the local Swift package and request a second Xcode build.

```sh
python3 scripts/perf/profile_ios_native_ui.py \
  --device "$DEVICE_ID" --screen plan --runs 3 --label candidate \
  --check-navigation --output /tmp/native-plan-candidate.json
```

Use `--screen activity` or `--screen receipts` for the other populated lists.
The runner launches only `finance.shilling.perf`. It fails on missing populated
lists, incorrect section counts, fixture errors, or timeout. A unique run token
prevents stale console output being accepted as a successful run.

For CPU samples, launch the same workload with Instruments Time Profiler:

```sh
xcrun xctrace record --template 'Time Profiler' --device "$DEVICE_ID" \
  --time-limit 40s --output /tmp/native-plan.trace \
  --launch -- finance.shilling.perf --perf-ui --ui-screen plan --ui-hold
```

The fixture emits `NativeUIWorkload` intervals in the PointsOfInterest category.
Its app-container `Documents/native-ui.jsonl` contains the measurements; PNGs in
the same directory capture each populated screen. Copy these before another run,
which replaces the report. Raw traces and screenshots stay outside the repository.


## Home and editor follow-up — September 30, 2026

This pass used the same physical SE and isolated Release fixture, with the daily
synthetic seed refreshed to September 30. Baseline includes the preceding
Plan/Receipts fixes. Both variants count returned cursor rows and active
(table key, listener) registrations in addition to query calls and their thread.
These counters run in the fixture only. Their overhead is included in all CPU
measurements below; production gains may differ from these instrumented timings.

### Home: bounded recent activity through Store5

Home previously loaded and joined the entire past year of postings for its three
recent activity rows. The new local, read-only Store5 projection selects twice
as many candidates as display rows, plus each candidate's matching transfer leg.
At most four times the display limit is returned to Kotlin. Transfer merging
retains its existing behavior, including suffix pairs, ad hoc pairs, orphans,
date-window boundaries and ordering within a day. Joined account/category/schedule
metadata remains live through SQLDelight's table listeners. A pair-id index is
installed additively in existing databases, preserving user rows.

Three fresh processes per variant, nominal thermal state, median measurements:

| Home metric | Before | After |
| --- | ---: | ---: |
| Populated screen load | 220.4 ms | 176.4 ms |
| Load CPU | 215.3 ms | 158.4 ms |
| Posting rows materialized on load | 10,001 | 6 |
| Ten posting updates: CPU | 1,533.5 ms | 916.6 ms |
| Ten updates: posting rows materialized | 100,010 | 60 |
| Resident memory after updates | 133.8 MiB | 97.3 MiB |
| Main-thread read calls | 0 | 0 |

The first baseline load took 1,264.6 ms, versus 205.2 and 220.4 ms for the other
two baseline processes. It is retained in the raw data and the median; it is not
used to claim a one-second startup improvement. Candidate loads were 176.4,
191.6 and 172.6 ms. These are mounted-screen loads, not full app startup.

The ten updates modify the synthetic probe posting, which is outside the three
visible recent rows under the preserved same-day ordering. Live changes to
visible rows, metadata and deletions are covered by Store5 regression tests.
The update phase includes three seconds of pauses and one second for settling;
its elapsed time remains about 4.2 seconds. CPU fell 40%, with no callback gaps
over 25 ms during updates in either cohort. Home's three-second scroll remained
free of such gaps in both variants; its short content reaches the bottom quickly.
Resident memory is an end-of-phase snapshot, not peak allocation or leak evidence.

Separate 20-second Time Profiler recordings show total sampled CPU falling from
2,621 to 1,899 ms. The full-history `SelectBetweenQuery` accounted for 825
inclusive samples before; the candidate instead spends most remaining data work
reading schedules for the weekly/monthly summaries. GC samples fell from 269 to
83; main-thread samples were essentially unchanged (656 versus 659). Inclusive
stacks overlap and the recordings include launch and idle time.

### Editors: targeted reads and lifetime checks

Account, category and schedule editors now read their selected entity by ID
through Store5. Transaction details use a joined single-posting Store5 projection,
removing the whole-schedule/account/category reads used only to decorate that
posting. Editor subscriptions, receipt lookups and transfer-partner reads run on
Default; mutable form state and actions remain on Main.

| Per editor opening | Before | After |
| --- | ---: | ---: |
| Account rows | 20 | 2 |
| Category rows | 80 | 2 |
| Schedule rows in schedule editor | 2,000 | 2 |
| Schedule rows in transaction editor | 2,000 | 0 |
| Transaction editor read calls | 24 | 10 |
| Main-thread reads: account/category/schedule/transaction | 4 / 4 / 8 / 24 | 0 / 0 / 0 / 0 |

Each process opens four production editors three times. Each opening receives
20 parent state updates before closing: 12 openings and 240 parent updates per
process. In the baseline and candidate, every hosting controller was released,
listener registrations returned to zero after each close, and parent rebuilds
caused zero reads. Ten later posting writes also caused zero editor reads. The
runner now fails if these lifecycle conditions regress. This verifies the earlier
StateObject ownership fix for mount/rebuild/unmount; interactive navigation,
production tab retention and a full retained-object census remain separate work.

The first editor comparison established fewer reads without an overall opening
speed improvement. After reinstalling the baseline, its timings reproduced.
Instruments then exposed repeated `today()`/time-zone resolution during schedule
initialization and projection. Schedule defaults now derive from one start date,
initial UI states reuse the existing form fields, and formatting reuses the
projection's reference date. The next-occurrence label now stops recurrence
generation at the first accepted date within the same three-year search window,
instead of expanding the entire window on each form update.

The final comparison uses two baseline processes and two final candidate
processes, six openings of each editor per variant. Medians include each
process's first opening; the intermediate build's samples remain in the artifact.

| Editor | Opening before | Opening after | CPU before | CPU after |
| --- | ---: | ---: | ---: | ---: |
| Account | 141.6 ms | 170.8 ms | 97.4 ms | 109.9 ms |
| Category | 151.2 ms | 169.2 ms | 105.1 ms | 119.5 ms |
| Schedule | 273.3 ms | 266.9 ms | 225.3 ms | 222.1 ms |
| Transaction | 272.7 ms | 252.1 ms | 218.6 ms | 232.5 ms |

Account/category opening has an 18–29 ms latency cost in this cohort while reads
move off Main. The small lists make saved database work relatively minor; the
asynchronous loading path also shows the loading view before the form. This is
a measured tradeoff, not a universal editor speedup. Account's median largest
callback gap fell from 83.9 to 50.6 ms; other editor gap results were mixed.
Final editors still spend most sampled CPU on Main in native view work: 5,908
of 6,572 samples, with 2,419 inclusive samples under AttributeGraph updates and
913 under the hosting view's layout. Whole-workload CPU is effectively unchanged
from the baseline's 6,599 samples. These totals also include the deliberately
forced parent rebuilds and closing each form.

The final two processes passed all **24 closure checks and 480 parent updates**:
no retained controllers/listeners, no rebuild reads, and no reads after close.
An earlier measurement cohort was interrupted by an Instruments launch and was
discarded before this complete two-process comparison.

### Compact-screen rendering

Screenshots exposed a monthly currency value split/truncated on Home and a
wrapped Monday label in the schedule editor. The monthly value now scales on
one line, and weekday controls use a compact single-line label. Populated SE
screenshots were inspected after both fixes.

### Work identified at that checkpoint

- Home's weekly and monthly use cases still reread schedules/exceptions after
  every notifier update: 20,000 schedule rows over ten posting writes. A shared
  Store5 projection or narrower invalidation needs a separate correctness pass.
- Ad hoc transfer-partner lookup still reads that day's postings (359 rows in
  this seed) before selecting the matching leg. It now runs off Main.
- SwiftUI form construction/layout still dominates editor opening after the
  data reads are reduced. There is no general editor-opening speed claim here.
- Full production startup, keyboard entry, interactive push/pop, tab-container
  lifetimes and receipt decoding remain unmeasured by these workloads.

Use `--screen home --runs 3` for Home and `--screen editors --runs 1` for the three
editor cycles. The latter records closure and rebuild checks as well as opening
measurements. Raw traces/screenshots remain outside git.


### September 30 validation

- **111 shared JVM tests passed.** New coverage compares bounded recent activity
  with full-history transfer merging across mixed randomized histories; observes
  joined metadata changes and deletion; verifies editor transfer saves, unsaved
  fields, remote deletion and background reads; and compares bounded recurrence
  output across every frequency and exception/date boundary.
- The existing-database regression verifies both performance indexes can be
  recreated without removing an account row.
- The final signed Release arm64 fixture built and installed successfully.
- Final installed-build Home smoke passed: six recent posting rows, zero Main
  reads, 917.3 ms CPU for ten updates. The synthetic Home screen was left visible
  on the SE.
- `just guard-architecture` and `git diff --check` passed.
- Entity access remains through Store5/repositories. No transport changes were
  needed for this pass.

[Raw Home/editor samples, medians, lifecycle checks and sanitized CPU summaries](../scripts/perf/results/native-ios-home-editors-2026-09-30.json).

### Follow-up: reactive Home, native navigation, and cold launch

Home's weekly window and monthly budget now observe their Store5 source tables
directly. Posting writes refresh the relevant posting projection without rereading
schedules or exceptions; the receipt tile observes a one-row Store5 count projection
that also updates when a posting deletion detaches a receipt. Transfer partner
lookup now uses the indexed pair ID and date in a Store5 detail query instead of
scanning every posting on the date. Shared JDBC tests cover live schedule,
exception, category, receipt, and transfer updates, including receipt detachment.
The native transaction push returned three posting rows per opening in this
fixture, versus roughly 360 same-day rows in the earlier editor profile.

The same three-process synthetic Home workload was rerun on the physical SE:

| Ten posting updates | Before | After |
| --- | ---: | ---: |
| Median CPU | 916.6 ms | 378.5 ms |
| SQL reads | 260 | 30 |
| Schedule rows returned | 20,000 | 0 |
| Receipt rows returned | 2,500 | 10 aggregate rows |
| Main-thread SQL reads | 0 | 0 |

Home loading remained similar: 176.4 ms before and 179.2 ms after. The update
run was paced across roughly 4.2 seconds in both variants; CPU is the useful
comparison. No update callback gap exceeded 25 ms in the final three runs.

The new native navigation fixture uses `NavigationStack` pushes and pops of the
production editors, focuses a real text field, enters ten characters, and checks
database listeners after each pop. Three processes ran two cycles of all four
editors, giving six typing samples per editor in each variant. Buffered title
fields keep rapid keystrokes local and publish to the Kotlin form after 120 ms of
idle time; Save flushes the latest title synchronously. Save validation now reads
the form directly so it cannot race a pending state projection.

| Ten title keystrokes | CPU before | CPU after | Callback gaps over 25 ms before / after |
| --- | ---: | ---: | ---: |
| Account | 226.4 ms | 224.8 ms | 2 / 0 |
| Category | 230.1 ms | 234.6 ms | 1 / 2 |
| Schedule | 445.0 ms | 214.9 ms | 49 / 0 |
| Transaction | 439.7 ms | 212.4 ms | 41 / 0 |

Push timings were essentially unchanged: schedule 280.8/280.6 ms and transaction
258.4/261.6 ms before/after. The unbuffered trace had 7,042 of 7,704 samples
on Main; SwiftUI AttributeGraph updates and UIKit layout dominated, while Kotlin
form updates had 21 inclusive samples. The first buffering attempt retained
editors through a pending dispatch item; clearing it on disappear fixed the
lifetime issue. All **24 final navigation pops** returned to zero listeners,
and ten later posting writes caused zero reads by closed editors.

For cold launch, a Release build of the normal native app was installed under a
separate bundle ID with its own empty first-launch sandbox. The App Launch trace
entered the foreground active state at 1.912 s (trace clock), after a 113.6 ms
initial frame render. UIKit initialization occupied 1.009 s. Time Profiler
sampled only 20 ms inclusive in synchronous Koin setup in the first five
seconds. This clean onboarding trace does not identify an app-code startup
hotspot that warrants changing initialization; the populated Home path is
measured above in the separate synthetic fixture. Neither trace opened or
changed the installed personal app's data.

The final pass passed **113 shared JVM tests**, the Android perf target build,
the signed Release iOS fixture build and installed SE smoke, `just
guard-architecture`, and `git diff --check`. The final Home smoke took 371 ms
of CPU for ten posting updates with 30 reads (zero schedules/exceptions),
and all eight final editor pops released their database listeners. The
synthetic Perf Home was left visible on the SE.

[Sanitized follow-up samples and launch timings](../scripts/perf/results/native-ios-reactive-navigation-startup-2026-09-30.json).

## Populated startup, tab lifetimes, rendering and large receipts

The next pass used the same physical SE and synthetic Store5 dataset, plus a
Pixel 6 regression run. The normal native app was built under the isolated
`finance.shilling.startupperf` ID with its own app group and a copy of the fixture
database: 10 accounts, 1,000 schedules, 10,001 postings and 250 receipts. Synthetic
onboarding settings select an offline self-hosted household with an unreachable
loopback signaling address. No personal app data or real peers were involved.

### Native tab lifetimes

Six cycles through the production Home, Plan, Activity, Receipts and Settings
tabs stabilized at 145.0–145.9 MiB resident memory. The 35 database listeners
remained stable, and revisiting tabs did not cause additional SQL reads. Removing
the tab container released it and returned listeners to zero after the
subscription timeout. Ten subsequent posting writes caused no closed-screen
reads. This checks the container and data subscriptions; it does not claim that
all system framework caches are freed immediately.

A separate Time Profiler run found 2,955 of 3,063 CPU samples on Main during
repeated switches, dominated by SwiftUI AttributeGraph/rendering and UIKit
updates. Each tab's Swift observer now skips republishing the identical immutable
Kotlin state object when StateFlow replays it. New states continue to publish;
this adds no data cache or persistence path outside Store5.

Three processes per variant ran six cycles through all five tabs. Excluding the
first cycle gives 15 repeat selections per tab:

| Tab | Median selection CPU before | After |
| --- | ---: | ---: |
| Home | 86.3 ms | 87.8 ms |
| Plan | 111.5 ms | 92.1 ms |
| Activity | 191.9 ms | 104.5 ms |
| Receipts | 108.4 ms | 103.1 ms |
| Settings | 104.1 ms | 100.0 ms |

The useful measured reductions are Activity (46%) and Plan (17%). Home did not
improve, and the smaller differences are not treated as established wins. All
six processes released their tab containers and listeners; revisits and writes
after closure caused no extra SQL reads. The before run also contained two
synthetic preview receipt rows left by a failed fixture attempt; those were
removed before the after run. Activity and Plan source rows were unchanged.

### Populated normal-app launch and resume

The first post-install App Launch trace took **1,872 ms** from the beginning of
system initialization to the end of initial-frame rendering. The initial frame
itself took 230 ms; UIKit initialization took 1,005 ms. Of 562 ms sampled CPU
before the first frame, 445 ms was on Main. Accessibility bundle loading and
dynamic linking dominated the main-thread stacks, while `startIosKoin` appeared
in 14 inclusive samples. A later cold process launch of identical code took
**348 ms** over the same lifecycle interval, including a 16 ms initial frame.
These are two observations with different cache conditions, not a before/after
app optimization. The first frame can show the startup state; the separately
captured screenshot verifies that synthetic Home content subsequently populated.

Three returns from Settings retained the same app process. The trace recorded
439–454 ms from the end of Background to Foreground Active, including the system
transition. Sampled CPU from each background exit through 250 ms after becoming
active was 23–27 ms, with 17–19 ms on Main and no Shilling Kotlin frames sampled.
There is no evidence here for moving more startup work or rebuilding the session
on resume. These short backgrounds do not test suspension under memory pressure.

### Instruments rendering evidence

`Animation Hitches` traces now cover editor navigation, all three native lists,
and repeated tab switching. Whole-workload counts were 49, 136 and 74 hitch
intervals respectively; maximum durations were 250, 117 and 533 ms. These include
initial mounts and transitions. Intervals can overlap across rendering contexts,
so their durations must not be summed into a wall-time percentage. The template
does not collect the fixture's custom signposts, preventing exact per-phase
alignment; the raw exported summaries are retained in the result artifact.

The lists still have short expensive commits during scrolling, and navigation
and tab transitions have larger stalls. Improved typing callbacks and stable
memory do not establish hitch-free rendering. Fixture display-link gaps remain
useful supplementary measurements, separately labeled from Instruments hitches.

After suppressing identical state replays, a second tab rendering trace recorded
68 hitch intervals with a 117 ms maximum, versus 74 and 533 ms before. These are
single instrumented runs with variable startup/cache conditions, so the repeated
CPU measurements above provide the stronger comparison. Transition hitches
remain; no claim of consistently smooth 60 Hz tab animation is made.

### Large receipt preparation and preview lifetimes

The physical SE exercised a 4,000 × 6,000 synthetic JPEG (23.3 MiB) and a
20-page image-heavy PDF (34.2 MiB). Inputs enter the fixture through the existing
Store5 staged writer, and Quick Look copies use the production bounded-read
helper. Three cycles of opening and dismissing both files reproduced the old
copy ownership behavior and compared it with the new owner.

The former behavior retained **172.5 MiB** of temporary preview copies after six
opens. `ReceiptPreviewModel` now owns each copy and removes it asynchronously on
dismissal, replacement or editor destruction. Both receipt and transaction
editors use it. All six final dismissals released the Quick Look controller and
left **zero managed preview bytes**. The standalone Swift check also verifies
replacement, destruction and preservation of unrelated files.

Camera JPEG encoding and security-scoped file reads now run in detached tasks,
returning results to the editor on Main. For three 24MP encoding samples, the
largest display callback gap fell from **573.5 ms to 59.0 ms**; the final two
samples stayed at 16.7 ms. Encoding CPU was essentially unchanged (526.8/528.0 ms
median). The objective is responsiveness while that work runs. File-read gaps
fell from 68.9 ms to 16.7 ms. Preparation measurements include 100 ms of paced
display callbacks around the operation and must not be interpreted as pure I/O
latency.

Explicit autorelease pools bound temporary Foundation/UIKit objects in the
background tasks. Without them, the first implementation ended the preview run
at 148.1 MiB resident memory; the final implementation ended at 101.0 MiB, versus
86.1 MiB for the original synchronous baseline. The remaining footprint increase
is reported rather than described as a memory win. These are app-process samples,
not the memory of Quick Look's separate rendering service.

The JPEG/PDF copy step already stayed off Main, with 131/184 ms median elapsed in
the final run and no callback gaps over 25 ms. Preview presentation was exercised
through `QLPreviewController`, validating the active item, visible controller and
release after dismissal. Its two-second settling window is not a measurement of
fully rendered content. In-process screenshots of Quick Look returned black;
no pixel-level visual
validation or full picker-to-preview latency is claimed for this workload.

### Pixel 6 regression

Three Release processes per screen exercised the shared Store5 changes with
10,000 postings and 1,000 weekly schedules. Activity rendered 1,050 scroll frames
and Plan rendered 1,036, with zero `gfxinfo` janky frames in either workload.
Scroll p95 was 7 ms for Activity and 8–9 ms for Plan. Median content readiness
was 97 ms for Home, 160 ms for Activity and 121 ms for Plan; flow readiness is
not the time at which the populated UI finishes drawing. Initial Compose frames
still contain jank.

Home fits on the Pixel display, so swipes produced no frames. The profiling
script now reports null scroll percentiles in that case instead of Android's
4,950 ms sentinel. The physical screenshot exposed a wrapped monthly currency
value; that tile now scales its amount onto one line. The rebuilt Release APK
was installed and the corrected amount was visually verified.

[Pixel regression samples](../scripts/perf/results/pixel6-shared-regression-2026-09-30.json).

[Native startup, resume, tabs, rendering and receipt samples](../scripts/perf/results/native-ios-deep-workloads-2026-09-30.json).

Validation: signed Release iOS and Android fixture builds passed, followed by
physical-device installation and profiling. The final Home check observed an
account balance change made through the production editor and Store5, then its
restoration to the original total. Its ten posting writes still used 30 reads,
zero on Main, and no schedule/exception rereads. Plan switched successfully
through Overview, By category, By day, Schedules, Categories and Accounts;
updates with Overview hidden produced zero reads. Receipt lifetime checks,
Python syntax checks, `just guard-architecture` and `git diff --check` passed.
Synthetic Perf Home was selected on both devices. No changes were pushed.

## October 1: native editor transition hitches

Profiled the physical iPhone SE again using the isolated, signed Release
Shilling Perf fixture and synthetic data. This pass changes the native editor
views; it does not rerun the earlier startup, resume, Android or transfer audits.

### Causes and changes

The synchronized Animation Hitches, Time Profiler and Points of Interest trace
identified eager picker-menu construction during Schedule and Transaction
pushes. The original entity pickers spent 41–48 inclusive Main-thread CPU
samples per heavy push in SwiftUI's `Coordinator.makeMenu()`. Each sample is
approximately 1 ms; inclusive stack counts overlap.

- Account, destination-account and category rows now use a native deferred
  menu. Opening a screen builds the selected label; opening its menu builds the
  choices. The menu retains selection checkmarks, an optional empty choice and
  refreshed transfer destinations. The full row is the button target, with an
  accessibility label/value and SwiftUI text sizing.
- Editor `FlowModel` skips a replay of the exact immutable state object already
  displayed, matching the tab models' existing behavior.
- Schedule and Transaction provide their field focus to the shared editor
  toolbar. The keyboard accessory is created while editing and removed when
  focus ends, reducing work during navigation. Done remains available for the
  title, amount, notes and day-of-month fields.

### Repeated measurements

Three fresh processes per variant, two visits to each editor per process:
six samples per row below. Values are medians. CPU is application CPU time
across threads during the observation window, **not CPU utilization percent**.
Push elapsed time measures populated-field readiness plus 50 ms, not the end
of the navigation animation. Pop measurements use a fixed one-second window.

| Workload | CPU before / after | Readiness before / after | Worst callback gap before / after |
|---|---:|---:|---:|
| Push Schedule | 240.2 / 193.2 ms | 281.2 / 236.1 ms | 139.8 / 105.0 ms |
| Push Transaction | 235.9 / 168.4 ms | 260.2 / 206.1 ms | 118.3 / 75.5 ms |
| Pop Schedule | 216.5 / 204.1 ms | — | 75.6 / 60.3 ms |
| Pop Transaction | 211.2 / 213.1 ms | — | 73.7 / 57.5 ms |

Schedule and Transaction push CPU fell 20% and 29%. Account and Category push
measurements were effectively unchanged. The focused-toolbar change has a
tradeoff: focusing a field now costs about 149–157 ms CPU versus 106–107 ms
before this pass. It reduces the larger callback gaps during focus and pop;
it is not an across-the-board CPU reduction. Schedule typing had one callback
gap over 25 ms across the final six samples; Transaction typing had none.

### Actual rendering evidence and remaining hitches

Separate synchronized traces captured eight pushes/pops each. Assigning hitch
intervals to the fixture signposts excludes initial launch, fixture seeding and
root mounting. The new `analyze_ios_transitions.py` performs this alignment.

| Largest rendering hitch in the relevant phases | Before | Final |
|---|---:|---:|
| Schedule pushes | 283.3 ms | 100.0 ms |
| Transaction pushes | 133.3 ms | 66.7 ms |
| Schedule/Transaction pops | 66.7 ms | 50.0 ms |

These are individual trace comparisons, not statistical tail guarantees.
Hitches still occur: the final push windows contain 16 intervals versus 17
before, and Account/Category back navigation still reaches 66.7 ms. First
keyboard presentation varied substantially between traces, including a 233 ms
outlier in the intermediate menu/replay build. The final trace also contains a
50 ms interval during Transaction typing. Native navigation/layout and keyboard
work remain measurable; these results do not establish hitch-free rendering.
Rendering intervals can overlap and must not be summed into a wall-time ratio.

### Validation

The new `editor-choices` fixture opens the real UIKit menus, inspects their
resolved actions and invokes their public action handlers. On both production
editors it verified 40 categories plus the empty choice, selected checkmarks,
clearing/restoring a category, ten accounts, and nine transfer destinations
that refresh after changing the source account. It checked accessible button
properties, no hidden UIView ancestors, a full-row target, and visually captured
the presented menu. This is not a physical touch or VoiceOver user test.

The same fixture focused title and decimal-pad amount fields and invoked the
actual Done toolbar action: all four keyboard-dismissal checks passed. All 24
editor closes in each repeated variant released their database listeners;
subsequent writes caused zero reads from closed editors. Queries remained off
Main. Thermal state was nominal throughout the recorded runs.

Signed Swift Release fixture builds used the previously verified, unchanged
Kotlin framework. The production Xcode project automatically includes the new
Swift file through its synchronized source group. Architecture guard, Python
syntax checks and diff checks passed. No changes were pushed.

[Raw samples, intermediate variant, aligned traces and functional checks](../scripts/perf/results/native-ios-transitions-2026-10-01.json).

## October 1 follow-up: editor focus, typing and Plan transitions

Continued on the same physical SE and isolated synthetic Release fixture.
Tested local name buffering in Account/Category and moving Schedule/Transaction
focus observation into the keyboard toolbar. **Neither prototype was retained.**
The production editors retain the earlier transition improvements above.

### Measurement method

The final comparison uses three fresh processes per build and two visits to
each editor per process. Typing inserts ten characters 50 ms apart, then waits
another 200 ms **inside** the measured interval, including the final 120 ms
debounced model update. Both builds use this revised window. The earlier
typing probes excluded that settling period and are not the final comparison.
CPU values are application CPU milliseconds across threads, not utilization.

| Workload, median of six samples | Earlier implementation | Combined prototype |
|---|---:|---:|
| Account typing, including final update | 225.9 ms | 233.6 ms |
| Category typing, including final update | 233.8 ms | 241.2 ms |
| Schedule focus | 155.2 ms | 137.7 ms |
| Schedule typing, including final update | 231.1 ms | 249.7 ms |
| Transaction focus | 154.4 ms | 125.8 ms |
| Transaction typing, including final update | 234.8 ms | 254.6 ms |

The apparent 7–8% Account/Category typing gain in the short probe disappeared
when the delayed update was included. Moving focus observation reduced the
focus window's CPU but increased the next typing window's CPU. Schedule's
median maximum callback gap during focus worsened from 22.5 to 39.2 ms;
Transaction's improved from 34.3 to 20.3 ms. Push/pop timings stayed close.
This did not justify the additional state machinery or the buffering change.
For each visit, summing focus and typing CPU first and then taking the median
gave 388.6 → 387.4 ms for Schedule and 385.6 → 381.1 ms for Transaction: only
0.3% and 1.2%, with mixed callback-gap results.

The retained navigation fixture also records a separate 400 ms `push-settle`
interval before focusing the field. This captures the tail after populated-field
readiness and avoids mixing the ongoing push with the keyboard measurement.
Screenshots are taken after that interval. The comparison table above predates
this added separation: both variants used identical timing, and their focus
intervals can include the tail of the push. The final verification uses the
separated intervals; its timing is not directly compared to that table.

### Plan section switches

Added a reproducible `plan-transitions` workload covering Schedules, Categories,
Accounts, Overview, By category and By day twice. It waits for the expected
populated list and another 500 ms before ending each interval. The fixture
uses the production Plan view and verifies listener cleanup after closing.

The synchronized trace recorded individual 50–66.7 ms hitches during most
section switches; By day peaked at 16.7 ms. The largest inclusive stack groups
were native collection layout, visible-cell creation and SwiftUI cell sizing.
The first three section visits made 6, 2 and 2 database reads respectively;
the remaining switches made zero reads, with no queries on Main. This pass
does not change Plan's production layout or claim those hitches are resolved.

### Final trace and verification

The restored production editors were rebuilt, installed and traced with the
separate push-settling window. The largest Schedule push hitch was 116.7 ms;
Transaction reached 66.7 ms and back navigation reached 50 ms. These are single
trace observations, not a new improvement claim or a bound on future hitches.

The first Schedule keyboard presentation also recorded a **250 ms**
commit-to-render hitch and a 270 ms display-callback gap. In that focus interval,
Time Profiler sampled 193 ms of application CPU: 76 Main-thread samples and
117 background samples. The background stacks included 68 inclusive samples
in `_UIRemoteKeyboards.setStickerPrewarmingViewControllerEnabled` and 71 in
dynamic-library loading. Only six Main-thread samples were in SwiftUI view-graph
updates, with zero collection-layout samples or database reads. This points to
iOS keyboard/sticker framework loading during the spike; it does not establish
the cause of every millisecond of rendering delay. The second Schedule focus
had a 16.7 ms rendering hitch. The cold keyboard spike remains unresolved.

The final checks passed on the physical device:

- Rapid saves in Account, Category, Schedule and Transaction dispatched the
  real toolbar actions 49–53 ms after typing. A newly opened production model
  read the complete saved value through Store5, and the fixture restored each
  original synthetic value. This includes the existing buffered title fields.
- Both heavy editors retained their real choice menus and keyboard Done action,
  including a direct switch from the title field to the decimal amount field.
- All eight editor closes in the final navigation run released their database
  listeners; subsequent fixture writes caused zero reads from closed editors.
  The repeated comparison variants also passed all 24 closes each.
- Signed Release builds, Python syntax checks, architecture guard and diff
  checks passed. Thermal state was nominal in the recorded runs.

The action checks use public UIKit handlers in the real production views; they
are not physical-touch or VoiceOver tests. Production editor code is unchanged
by this follow-up. The retained changes are the expanded profiling/validation
workloads, two additional CPU-stack categories in the trace analyzer, and the
results. Synthetic Perf Home was left visible. No changes were pushed.

[Follow-up samples, rejected prototype comparison, final checks and aligned traces](../scripts/perf/results/native-ios-editor-followup-2026-10-01.json).

## October 1 continuation: Plan list layout

Tested two small changes against the remaining Plan section-switch hitches on
the physical SE. Each variant had three fresh Release processes and two visits
to each of six sections per process. Reporting first and repeat visits
separately matters: Schedule's first visit includes six Store5 reads and costs
about twice as much CPU as its repeat visit, which has zero reads. All queries
ran off Main; listeners reached zero when Plan closed. Thermal state stayed
nominal.

| Variant | Schedule first / repeat CPU | Schedule first / repeat median callback gap |
|---|---:|---:|
| Existing List | 203.9 / 101.2 ms | 70.2 / 51.4 ms |
| Direct `Text` for single-line rows | 194.7 / 97.8 ms | 53.6 / 77.6 ms |
| New List identity per section | 208.7 / 128.4 ms | 86.4 / 114.6 ms |

The direct-text row saved about 4% Schedule CPU in both visits, but its repeat
callback gap worsened and the aligned trace showed an 83.3 ms first Schedule
hitch versus 50.0 ms in the separate baseline trace. Account/Category CPU was
essentially flat. There was no repeatable rendering win, so this change was
reverted.

Giving each section a new List identity caused larger regressions: Category
repeat CPU rose from 91.6 to 120.6 ms, Account repeat from 87.3 to 117.4 ms,
and Schedule repeat from 101.2 to 128.4 ms. Account's median maximum callback
gap on repeat visits reached 103.7 ms. That prototype was also reverted.

The navigation fixture exercised all six sections, and Account/Category rows
were visually inspected. Some fixture screenshots occur while a section
animation is still completing, so they are not pixel-comparison evidence.
The Plan workload now checks the complete set of section/cycle pairs, zero
Main-thread reads and zero reads on repeat visits. Signed Release builds,
architecture guard and diff checks passed. The prior production Plan view is
retained; no code was pushed.

[Plan layout variants, physical samples and aligned traces](../scripts/perf/results/native-ios-plan-layout-2026-10-01.json).

## October 1 continuation: hosted Settings and native peer lifecycle

This pass follows the rebase onto public main `332845a`. The physical 16 Pro
remained unavailable and was skipped at the user's request. No new Plan
transition improvement is claimed.

### Hosted Settings polling

The hosted-device model previously polled every 15 seconds for its entire
lifetime, including while native tabs retained it offscreen. Each load fetched
entitlements, registered devices and billing configuration serially. It now
polls only while observed, cancels pending work when observation ends, fetches
independent metadata concurrently, and caches billing configuration for the
current visible session. Manual refreshes share one conflated polling loop.
Access-token changes preserve the current identity and do not restart loading.
Native observation also stops while the scene is inactive; Compose observation
requires a resumed lifecycle.

| Deterministic JVM workload | Before | After |
|---|---:|---:|
| Metadata ready, with 100 ms delay per endpoint | 300 ms | 100 ms |
| Requests during 45 seconds visible | 9 | 7 |
| Requests during the next 45 seconds hidden | 9 | 0 |

These are virtual-time MockEngine results, not native UI or production network
latency. Five tests also cover cancellation, reopening, token/account changes,
self-hosted sessions, refresh bursts and device-removal refresh behavior.

Two physical SE Release runs used the production retained tab controller and a
local synthetic metadata server with the same added 100 ms endpoint delay:

| Run | Visible requests | Hidden requests | Reopened requests |
|---|---:|---:|---:|
| First | 5 | 2 | 3 |
| Second | 7 | 0 | 3 |

Settings was visible for about 35 seconds, hidden behind Home for 35–37 seconds,
then reopened for five seconds. In the first run, the final two poll requests
arrived at the server 3.0 and 7.1 seconds into the hidden measurement; there
were no continuing polls afterward. This is consistent with late network
delivery, but server arrival timestamps alone cannot prove when the client
issued them. The second run recorded no hidden requests. Cancelling a request
cannot retract bytes already sent to the network.

A separate background/resume check retained the same process: **zero requests
over 50.7 seconds in the background**, followed by three fresh metadata requests
within five seconds of foregrounding. The Settings screenshot was inspected.
No measured phase queried the database on Main. Home's active listeners during
the hidden phase are expected. CPU samples include the fixture's display-link
overhead and are not battery measurements or Instruments rendering-hitch traces.

### Native channel callbacks

The physical SE–Pixel test exposed an iOS adapter regression: the iPhone could
have an open channel and receive messages while its connected-peer set stayed
empty. The bounded native receiver replaced Ktor's delegate and handled message
delivery but did not forward lifecycle callbacks. When the iPhone offered the
connection, ICE completed before its data channel opened, and the later Open
event was lost.

The adapter now retains and forwards the original delegate's state and buffered
amount callbacks while keeping message delivery in its bounded queue. Ktor 3.6
uses these callbacks to emit channel events
([upstream implementation](https://github.com/ktorio/ktor/blob/3.6.0/ktor-client/ktor-client-webrtc/ios/src/io/ktor/client/webrtc/DataChannel.kt)).
The connection manager also invokes its existing `onChannelOpen` hook again.
The fixture uses production channel status on both devices so an empty iOS
status cannot silently pass as a valid pre-removal connection.

After rebuilding and installing, the physical checks passed:

- The SE and Pixel both reported the open channel, including the previously
  failing iPhone-offerer case.
- Interrupting only Pixel signaling for eight seconds left both P2P connected
  sets unchanged. Removing that device then emptied both sets, with no Offer
  during a further 20-second observation period.
- Explicitly readmitting and restarting the Pixel restored both connected
  sets. Removing the connected iPhone also emptied both sets, with no Offer
  during the next 20 seconds.
- A browser-to-SE upload and readback verified all **1,048,576 bytes**, using
  64 binary receipt frames and native Store5, with no browser errors. This
  exercised the iPhone-answerer path. Its timer is not a new comparative
  throughput measurement.

These cases inject synthetic control-plane events into the production native
clients. They do not exercise hosted-account authorization or the physical
Settings Remove button. The 14 production signaling-server JVM tests passed,
including removal of an already disconnected device, queue bounds and slow-peer
isolation. Android's fixture build was debug for these correctness checks;
iOS's was signed Release. Both final builds, syntax checks, architecture guard
and diff checks passed. Benchmark processes and the owned ADB reverse were
stopped, and normal Shilling was returned to the foreground on both devices.

### Fresh Android database bootstrap

The newly installed Pixel fixture also exposed a fresh-database regression:
SQLiteOpenHelper's `android_metadata` table was counted as existing application
data, preventing initial schema creation. Bootstrap now excludes that system
table while retaining the protection against discarding actual application
tables. All six bootstrap tests passed, including the new Android case, and
the installed Pixel fixture initialized successfully.

[Hosted polling samples, lifecycle checks and byte verification](../scripts/perf/results/hosted-settings-and-peer-lifecycle-2026-10-01.json).
