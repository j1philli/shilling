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

No new physical iPhone UI or Pixel frame measurements are claimed in this pass.
