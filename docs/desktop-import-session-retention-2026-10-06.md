# Import review lifetime across tab switches — October 6, 2026

The next retention investigation starts from public main `c17f732`, after the
[CSV review allocation work](desktop-csv-review-memory-2026-10-06.md).
A saved import screen kept its parsed rows, formatted row models and published
UI snapshot indefinitely after observers disappeared. Its flows stopped doing
work, but their replay values and the projection's last snapshot stayed alive.

Two matched native pairs confirm that hidden reviews now publish **zero rows
instead of 10,000**, and still restore the edits correctly. Final WebContent
footprint was **16–17 MiB lower** in both pairs. Whole-app savings varied from
**3 to 16 MiB**, with the candidate settling at **467–472 MiB**. This is a modest
result, not a solution to the app's large baseline footprint. Peak results were
mixed and do not establish another reliable peak-memory improvement.

The shared import view model now releases those derived values after the public
state's existing five-second grace interval. It keeps the original CSV content,
mapping, selected account, default category and per-row choices so that returning
to the saved screen can rebuild the review and rerun duplicate checks. Returns
during the grace interval retain the populated snapshot. A later return can
briefly show the initial empty state while parsing and projection resume.

## Ownership change

There are three references to clear, not just the visible state's row list:

- `stateIn` now expires its UI replay value when the grace interval ends.
- `ImportReviewRows` clears its latest source and projection when the upstream
  state projection stops.
- The internal parsed-row `shareIn` expires its replay value as soon as its last
  subscriber stops. The public state already supplies the grace interval, so the
  internal stream does not add another five-second delay.

The update preserves the serialized projection boundary. It does not clear the
input file or user edits when a tab is hidden. Closing the navigation entry still
uses the normal view-model lifetime. Store5 data access and import writes are
unchanged, as are signaling and device transport.

## Regression evidence

The import integration test now keeps the view model alive while removing all
observers, then reads `state.value` without resubscribing. On the control, the
review remained populated and the new assertion timed out after seven seconds.
With the change, the test passes after the five-second expiry.

The same test changes category metadata while hidden, returns to the review,
checks the retained CSV, selected/excluded rows, duplicate label and updated
category name/color, then imports through the real repository graph and verifies
the saved amount and category. Existing row-reuse/immutability tests also pass.

The full shared JVM suite passed: **157 tests, zero failures or skips**. Shared
Android release and iOS arm64 release compilation and `just guard-architecture`
passed. This proves the lifetime behavior and import regression coverage; it is
not a measurement of desktop RAM savings. The optimized release perf and
production web builds and both unsigned macOS bundles also built successfully.

## Desktop experiment

The `csv-session` fixture adds four 10,000-row review cycles using production
navigation. Each edits selection and category, leaves the unfinished import via
the Home tab for 18 seconds, restores the saved Activity/import stack and checks
the edits. It cancels the first three reviews and imports the fourth. After each
close, the helper holding that cycle's model returns before the footprint
checkpoint. Three one-minute idle checkpoints follow the last cycle.

`SHILLING_REVIEW_LIFETIME` records hidden row counts by reading `state.value`
without a subscriber. It also counts opened and cancelled view-model scopes.
The counters hold no models. Cancellation confirms navigation cleanup; it does
not prove garbage collection. The runtime/SQL/Skia probes and four-process
physical-footprint sampler provide separate evidence about process memory.

Use the [perf README](../scripts/perf/README.md) with
`SHILLING_MEMORY_WORKLOAD=csv-session` and matching SQL/runtime/allocation probes.
Restore the same synthetic seed before each version, keep the Mac unlocked and
window visible, and finish compilation before measuring. The expected final
database has 20,000 postings: the original 10,000 plus the fourth cycle's import.

## Native results

Measurements used a Mac15,6 with 36 GiB RAM, macOS 27.0.1 (26A434), system dark
appearance, a 1200 × 900 viewport and DPR 1. All compilation and optimization
finished before the first run. Both versions use release Wasm, the existing
Binaryen optimization, unsigned native bundles and identical SQL/runtime/
allocation probes. All packaged assets except `perf-web.wasm` have matching
hashes; the two production source changes are the review cache and view model.

Run order was control 1, candidate 1, control 2, candidate 2. Each began with fresh
host/WebContent/GPU/Networking processes and the same restored synthetic seed:
10,000 postings, 1,000 schedules, 20 accounts, 40 categories and 250 receipt
metadata records. No forced GC was used. Other user applications remained open.
Each run lasted about seven minutes, including three idle minutes; this is not
a multi-hour soak or an image/P2P workload.

All **80 READY checkpoints** were visible, with complete runtime and main/SQL-worker
allocation diagnostics. All **325 five-second screen-lock checks** were unlocked. There
were no manual workload edits. Read-only Home accessibility/screenshot checks
occurred in the first control and candidate runs; the second pair had no such
inspection. The fixture uses only synthetic data and a loopback control-plane
server. The production app was not opened against user data.

### Physical footprint

MiB from `vmmap`. Whole-app values sum four sequential process snapshots, so
they are approximate phase totals. Renderer lifetime peaks are WebContent only;
independent process peaks are never summed.

| Metric | Control 1 | Candidate 1 | Control 2 | Candidate 2 |
| --- | ---: | ---: | ---: | ---: |
| After first cancelled review | 423.6 | 434.6 | 448.8 | 458.5 |
| After second cancelled review | 393.9 | 389.1 | 391.9 | 408.3 |
| After third cancelled review | 378.5 | 399.0 | 380.2 | 399.9 |
| After fourth review/import closes | 490.0 | 495.6 | 500.8 | 503.3 |
| Idle minute 1 | 489.4 | 472.1 | 483.7 | 498.7 |
| Idle minute 2 | 475.0 | 472.1 | 483.7 | 482.0 |
| **Idle minute 3** | **475.1** | **472.1** | **483.8** | **467.4** |
| Final WebContent only | 418.1 | 402.0 | 427.3 | 410.5 |
| WebContent lifetime peak | 616.5 | 522.8 | 529.9 | 533.8 |

The first pair's apparent 15.2% peak improvement did not repeat: the second
candidate's peak was 0.7% higher. Final renderer footprint was lower by 16.1 and
16.8 MiB, but host variation reduced the whole-app saving in the first pair.
Whole-app final differences were 3.0 MiB (0.6%) and 16.4 MiB (3.4%). Two runs per
version on a shared Mac do not establish a guaranteed saving or isolate the
runtime/allocator mechanism. Immediate post-close and hidden-phase footprints
were not consistently lower, despite deterministic release of the published
rows. The [summary](../scripts/perf/results/desktop-import-session-summary-2026-10-06.json)
contains all 20 phase measurements per run.

Repeated cancellation did not produce steadily rising settled footprints in
these cycles. Every run opened four review scopes and cancelled all four. That
is useful lifecycle evidence, not proof that every object was collected or that
the application has no leaks.

The tracked main linear memories stayed at 0 and 18,808,832 bytes; SQL's linear
capacity stayed at 22,151,168 bytes. The final reported Skia font cache was
91,242 bytes and its resource cache counter was zero. These counters do not
measure the Kotlin/Wasm GC heap or explain the remaining process footprint.
WebContent still accounted for roughly 402–411 MiB of the candidates' final
467–472 MiB totals, making the shared frontend/runtime the larger remaining
target. This experiment does not update the standalone-browser comparison.

### Data and cleanup

All four stopped database snapshots pass SQLite integrity checks. Each contains
20,000 postings, including 10,000 unique imported IDs and 10,000 matching posting
version records. The first three cancelled reviews create no extra postings;
all final imported rows are uncategorized 15.75 expenses. Sorted financial
columns, excluding generated IDs and space IDs, have identical SHA-256 values:
`3236e5e70f0494d3ad1a8dc30d04f83cc1b2a326d64e7212e4f6789c2bb5f424`.
See the [database checks](../scripts/perf/results/desktop-import-session-integrity-2026-10-06.json).

The original benchmark WebKit sandbox was restored and its 18 files verified
against the backup. Owned app processes, the local server and temporary
display-idle assertions were stopped. System lock preferences were not changed.

Raw profiles: [control 1](../scripts/perf/results/desktop-import-session-control-1-2026-10-06.json),
[candidate 1](../scripts/perf/results/desktop-import-session-candidate-1-2026-10-06.json),
[control 2](../scripts/perf/results/desktop-import-session-control-2-2026-10-06.json),
[candidate 2](../scripts/perf/results/desktop-import-session-candidate-2-2026-10-06.json).
The [provenance file](../scripts/perf/results/desktop-import-session-provenance-2026-10-06.json)
records source and packaged-asset hashes, setup and measurement limitations.
