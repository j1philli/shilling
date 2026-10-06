# Desktop CSV review allocations — October 6, 2026

Editing a single row in a 10,000-row CSV review previously rebuilt every row's
UI model, date label and amount label. The shared presentation layer now retains
compact parsed rows and reuses unchanged UI models. In two matched desktop pairs,
checkbox/category state updates were **4–6 times faster**, and WebContent's
lifetime peak physical footprint fell from **659–680 to 528–554 MiB**
(**18.5–19.8%**). Initial-review and final-idle whole-app memory were mixed;
these runs do **not** establish a reliable idle reduction.

## Change

`ImportViewModel` maps the parser's row sequence into compact review values,
dropping per-row raw field lists and unused raw CSV strings. It still keeps the
original CSV content for column remapping and the existing import path. Blank
descriptions retain their original fallback title, computed once.

`ImportReviewRows` remembers only its latest source list and immutable UI
snapshot. A checkbox or category edit copies the affected model and reuses the
other models and formatted labels. Category name/color/removal and duplicate
status changes refresh the affected fields. New parsed rows, a different
reference year or a different currency symbol invalidate formatting reuse.
Repeated dates share labels within one projection; there is no date cache that
grows across files. Previously published snapshots remain unchanged.

This is still an O(N) scan and reference-list allocation per projection, with
the full compact review in memory. It is not a streaming or paged review.
Store5 import writes and duplicate queries are unchanged. The combined candidate
does not isolate the memory contribution of each allocation reduction.

## Matched workload and measurements

Base: public main `8a55b34c21e944b893db1a3853fe89743f95280a`.
Both versions include the new `csv-review` benchmark. The control uses the
unchanged production view model; the candidate uses the compact/incremental
projection. Control Wasm was built before the production edits on October 5
and preserved; both native bundles were rebuilt on October 6. Artifact hashes
are recorded in the [provenance file](../scripts/perf/results/desktop-csv-review-provenance-2026-10-06.json).
All distributed fixture files other than `perf-web.wasm` have identical hashes.

- Mac15,6, 36 GiB RAM, macOS 27.0.1 (26A434), 1200 × 900 viewport, DPR 1.
- Release Kotlin/Wasm with the existing Binaryen optimization and unsigned
  Tauri bundles. SQL, runtime and allocation probes enabled for both versions.
- Each run starts fresh host/WebContent/GPU/Networking processes from the same
  restored synthetic database: 10,000 postings, 1,000 schedules, 20 accounts,
  40 categories and 250 receipt metadata records.
- Home → load 10,000 CSV rows → 60 single-row edits → import → Home → Activity
  → Home → final idle. Five rounds edit rows 0, 5,000 and 9,999: checkbox off/on,
  category assign/clear. All edits are restored before import.
- Run order: control 1, candidate 1, control 2, candidate 2. No forced GC or
  overlapping compilation. Other user applications remained open on this Mac.
- All four runs completed with eight expected READY checkpoints and all three
  diagnostic streams present. Checkpoint visibility was `visible` throughout.
  A Home screenshot/accessibility inspection occurred in control 1; no manual
  edits occurred in the measured workloads. Later UI checks are excluded.

This OS differs from the October 3–4 measurements. These values should be
compared within this experiment, not directly against those older runs.
Two runs per version are useful repeat checks, not a statistical estimate.

### State update latency

Milliseconds. Each edit column summarizes 30 actions; p95 uses nearest rank.

| Run | Review load | Checkbox median / p95 / max | Category median / p95 / max |
| --- | ---: | ---: | ---: |
| Control 1 | 135 | 25.5 / 33 / 44 | 29 / 39 / 40 |
| Candidate 1 | 110 | 5.5 / 8 / 11 | 5 / 8 / 9 |
| Control 2 | 118 | 23 / 29 / 40 | 24 / 31 / 34 |
| Candidate 2 | 112 | 5 / 9 / 11 | 6 / 10 / 10 |

Load timing includes synthetic CSV byte generation, `loadFile`, parsing,
duplicate checks and publication of an importable state. Edit timing starts
before the view-model action and ends when the expected state is published.
The 50 ms pacing after each edit is excluded. These are not native file-picker,
input-event or rendered-frame latency measurements.

### Physical footprint

MiB, from `vmmap`. Whole-app phase values sum the host, WebContent, GPU and
Networking snapshots taken sequentially, so they are approximate phase totals.
The last row is **WebContent only**, using that process's lifetime peak counter;
it is not a simultaneous whole-app peak. Independent process peaks are not added.

| Phase | Control 1 | Candidate 1 | Control 2 | Candidate 2 |
| --- | ---: | ---: | ---: | ---: |
| Home before CSV | 415.4 | 357.3 | 358.5 | 376.6 |
| Review loaded | 525.4 | 449.6 | 488.7 | 540.4 |
| After 60 row edits | 565.3 | 546.2 | 637.6 | 470.5 |
| Import complete | 628.6 | 589.5 | 600.7 | 540.9 |
| Home after import | 575.8 | 512.9 | 504.6 | 513.3 |
| Activity after import | 588.0 | 521.0 | 502.7 | 524.5 |
| Home settle | 590.1 | 523.7 | 505.4 | 527.1 |
| Final idle | 589.5 | 522.9 | 504.7 | 525.4 |
| **WebContent lifetime peak** | **658.8** | **528.2** | **679.5** | **553.5** |

Renderer peak and the post-edit/import checkpoints improved in both pairs.
Final idle improved by 66.7 MiB in the first pair but increased by 20.8 MiB in
the second. The initial review total also moved in opposite directions.
Cold Home variability and GPU/allocator behavior limit what can be attributed
to retained CSV objects. The supported result is lower edit allocation/latency
and a repeatable renderer-peak reduction in this workload, not a fixed idle
budget or proof that desktop wrapper overhead has changed.

## Correctness and validation

The stopped IndexedDB snapshots from all four runs pass SQLite integrity checks.
Each has 20,000 postings, including 10,000 new unique import IDs and 10,000
matching posting version records. All imported values are uncategorized
15.75 expenses. Sorted financial columns match across all runs after excluding
generated IDs and space IDs; their SHA-256 is
`3236e5e70f0494d3ad1a8dc30d04f83cc1b2a326d64e7212e4f6789c2bb5f424`.
See the [database checks](../scripts/perf/results/desktop-csv-review-integrity-2026-10-06.json).
Different session metadata makes snapshot byte sizes differ.

The new shared tests check that one edit replaces only one of 10,000 row objects,
old snapshots stay immutable, and unchanged projections reuse their list. They
also cover duplicates, category metadata/deletion, reference-year changes,
remapping, invalid/blank-description rows and replacement/reset behavior.
Existing view-model integration tests cover duplicate gating and persisted
selection/category results.

- 157 shared JVM tests and 13 web tests passed.
- `just guard-architecture` passed.
- Shared Android release and iOS arm64 release compilation passed.
- Optimized release perf-Wasm and the unsigned native fixture built successfully.
- Separate native UI inspection confirmed Home and the scrolled review with
  transaction titles, dates, amounts, categories and selection controls rendered.
- Standard production web release packaging and unsigned macOS app bundling
  passed. The optimized production Wasm is 9,389,843 bytes; this is artifact size,
  not a RAM measurement. The production bundle was not opened against user data.

This round does not add Linux/Windows runtime validation, actual mobile-device
UI checks, or a new standalone-browser RAM comparison. The fixture's local
server carries control-plane traffic only. Production data was not used.
After profiling, the original benchmark WebKit sandbox was restored and its
18 files verified against the backup; the test processes and local server stopped.

## Reproduction and raw evidence

Build with `SHILLING_MEMORY_WORKLOAD=csv-review`,
`SHILLING_MEMORY_SQL_PROFILE=true`, `SHILLING_MEMORY_RUNTIME_PROFILE=true` and
`SHILLING_MEMORY_ALLOCATION_PROFILE=true` via
`bash scripts/perf/build_desktop_memory.sh`. Use the isolated seed/restart and
explicit four-process profiler procedure in the [perf README](../scripts/perf/README.md).
Restore the same seed before each control/candidate run and preserve the two
bundles. To reconstruct this control, apply the benchmark-only changes to the
base commit, leaving production `ImportViewModel` unchanged.

Raw profiles: [control 1](../scripts/perf/results/desktop-csv-review-control-1-2026-10-06.json),
[candidate 1](../scripts/perf/results/desktop-csv-review-candidate-1-2026-10-06.json),
[control 2](../scripts/perf/results/desktop-csv-review-control-2-2026-10-06.json),
[candidate 2](../scripts/perf/results/desktop-csv-review-candidate-2-2026-10-06.json).
The [summary](../scripts/perf/results/desktop-csv-review-summary-2026-10-06.json)
contains exact phase totals and latency statistics. The raw profiles retain
RSS samples, per-process footprints, diagnostic values and all 60 edit timings.

The remaining idle-memory question needs a separate long-session experiment:
repeated open/edit/cancel/import cycles, followed by extended idle on the same
OS and dataset. An updated standalone-web comparison would help distinguish
shared UI/runtime retention from desktop-specific costs.
