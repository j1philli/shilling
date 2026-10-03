# Desktop retained state and release metadata — October 3, 2026

This follow-up starts from public `main` at `97f0210`, after
[PR #44](https://github.com/j1philli/shilling/pull/44). It investigates saved-screen
state, CSV review allocations, and the release Wasm artifact. Integration also
includes main's later `7f39c73` beta-publication change; it does not change the
application or benchmark sources measured here.

The changes release inactive projected screen data, avoid the initial unchecked
CSV projection, shorten startup snapshot lifetime, and reduce release debug
metadata. The native results below include unsuccessful experiments as well as
the final configuration; no individual favorable sample is treated as a
guaranteed reduction on every screen.

## Saved-screen lifetime

Navigation saves each tab's view model. Its `WhileSubscribed(5_000)` flows stopped
querying after the screen disappeared but kept the last projected list forever.
That is the documented default: stopping collection and expiring its replay value
are separate settings in
[SharingStarted](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-sharing-started/-companion/).

Activity, Plan, Schedules, Accounts, Categories and Receipts now expire their
projected rows when the existing five-second grace interval ends. Plan also
clears the separate occurrence-to-action index. Search, range, grouping, period,
expanded categories and filters remain in their existing input state. Returning
rebuilds the projection from current Store5 data, as the restarted subscription
already did before this change. A return after expiry can briefly show the
initial/loading projection while those reads finish; quick returns retain the
existing populated snapshot.

The regression test keeps the view models alive, removes their collectors,
checks that their rows and Plan's action index empty, edits the underlying data,
and reopens the screens. It verifies retained selections, fresh joined values,
and a working posting action after the index is rebuilt.

## CSV review

The previous pipeline combined parsed rows directly with a separate asynchronous
duplicate result. A large file could project its full UI once with the previous
empty duplicate set and again when checking finished. Review now publishes the
rows together with their duplicate decisions. Its existing parsed-row `shareIn`
boundary is retained. Removing that boundary looked simpler but two subsequent
full runs ended around 608 MiB WebContent, versus 478 MiB in the earlier candidate
that kept it. The removal was reverted; these measurements do not explain the
underlying runtime allocation/collection mechanism.

A test holds the actual Store5 duplicate-candidate SQL query open. No importable
rows appear while that initial check is pending. When released, the review still
excludes existing transactions and preserves selections and category overrides.
The native fixture imports all 10,000 selected rows through the unchanged Store5
write path.

## Startup database readback

SQL.js copies the IndexedDB snapshot into its MEMFS database file. The worker now
releases the original readback buffer immediately after that constructor finishes,
using the same guarded detachment already used for completed exports. Cleanup
runs on failure too and never changes the load/commit outcome. Unsupported or
throwing cleanup falls back to ordinary garbage collection.

The persistence harness now models IndexedDB's independent copies on reads as
well as writes. It holds each readback view to verify detachment, reopens the
actual SQL.js database in all three cleanup modes, reads existing rows and schema
version, commits new rows, and reopens again. The separate migration/rollback/
failed-commit/foreign-key harness also passes. Recheck this ownership boundary
when upgrading SQL.js; detachment is safe because the tested constructor copies
its input rather than adopting it.

The earlier full-workload diagnostic runs precede this startup-only change;
the final configuration includes it. Cold runs reuse a completed 20,000-posting
database.

## Release Wasm metadata

The production artifact contained nearly 10 MiB of debug names. Packaging now
removes local, type, global and GC field name subsections, preserving module and
function names used in stack traces. The production Wasm file shrank from
**22,861,768 to 15,622,459 bytes**, saving **7,239,309 bytes (31.7%)**. This is an
artifact-size reduction, not a claim of an equal reduction in RAM.

The [WebAssembly specification](https://www.w3.org/2021/11/wasm-stage/appendix/custom.html)
defines the name section as optional debugging metadata that does not affect
execution semantics. An independent comparison of the actual release artifact
verified identical executable sections, module/function names and other custom
sections. Both full and trimmed modules pass engine validation. Tests also
execute an exported function, preserve unrelated/future metadata, check
idempotence, and reject malformed framing before publishing a rewritten file.
See the [artifact comparison](../scripts/perf/results/desktop-wasm-debug-names-2026-10-03.json).

Debug builds retain all names. The unmodified compiler package also remains under
`build/tasks`; interactive debugging of local variables or Wasm type/field names
should use that package. The memory fixture uses the same release transformation,
with `SHILLING_MEMORY_TRIM_NAMES=false` available for a diagnostic control.

## Runtime probe and measurement scope

The optional fixture probe uses weak references to record main-thread Wasm
linear-memory capacity and counts `Intl.DateTimeFormat` construction. In the
full runs, the main memories stayed at **0 and
18,808,832 bytes** through navigation and import; the formatter counter stayed
at zero. This rules out growth in those particular measured capacities during
these runs. It does not measure Kotlin Wasm GC objects, SQL worker memory,
rendering allocations or physical footprint.

All full runs restore the same saved synthetic 10,000-posting database with
1,000 schedules, 20 accounts, 40 categories and 250 metadata-only receipts. They
visit every main destination twice, four editors, import 10,000 CSV rows, then
visit Home/Activity/Home and idle. The optional runtime probe is enabled in each
full run. No compilation overlaps those measurements.

The metric is native `vmmap` physical footprint, including compressed/swapped
allocations. Four-process totals add sequential snapshots of the host,
WebContent, GPU and Networking processes; they are approximate phase totals,
not simultaneous peaks. Other workloads run on this shared Mac. Each complete
configuration has one observation, so differences are not a repeat-run
statistical estimate. Receipt image previews and P2P transfers are outside this
metadata-only workload.

## Native results

All numbers are MiB of physical footprint. Phase cells show **four-process
total (WebContent)**; the last column is only WebContent's process-lifetime
peak. Peaks from separate processes must not be added together.

| Configuration | Home | Navigation end | CSV review | Import complete | Post-import idle | WebContent lifetime peak |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Fresh main control | 467.1 (367.5) | 551.0 (446.4) | 610.4 (503.9) | 616.6 (509.7) | 608.0 (501.0) | 715.5 |
| Screen expiry + checked review | 518.6 (419.7) | 502.5 (408.2) | 634.3 (538.2) | 620.9 (524.5) | 574.1 (477.6) | 693.9 |
| Rejected: no CSV sharing, trimmed names | 449.5 (358.4) | 517.7 (441.4) | 608.8 (530.7) | 690.1 (611.9) | 686.5 (607.8) | 732.8 |
| Rejected: no CSV sharing, full names | 458.3 (356.7) | 506.3 (412.9) | 575.1 (479.9) | 716.3 (620.8) | 703.5 (607.9) | 716.2 |
| Final configuration | 494.6 (406.0) | 496.0 (399.4) | 565.8 (467.8) | 591.5 (493.4) | 577.4 (478.7) | 626.8 |

The final full run reduced settled total footprint by **30.6 MiB (5.0%)** versus
the fresh control. WebContent settled at 478.7 versus 501.0 MiB, and its lifetime
peak was 626.8 versus 715.5 MiB (**12.4% lower**). Initial Home was higher in this
run, which illustrates the runtime variability. The earlier sharing-preserving
candidate also settled near 478 MiB WebContent, but these are not identical
configurations or a statistical repeat set. Heavy import memory remains high.

The prior report's 727.3 MiB final total is not used as this pass's baseline:
its fresh control already settled at 608.0 MiB before these changes. The two
no-sharing experiments are retained above because omitting their worse outcomes
would misrepresent the investigation. Their nearly identical WebContent idle
footprints also argue against debug-name trimming being responsible for that
specific regression.

### Cold idle on the same 20,000-posting database

These three launches restore the exact same completed 20,000-posting snapshot.
The first two use the intermediate no-sharing configuration with the original
startup readback lifetime; only debug-name trimming changes between that pair.
The final launch restores CSV sharing and includes startup-buffer cleanup. No
CSV screen is instantiated during this Home-only workload.

| Cold configuration | Host | WebContent | GPU + Networking | Total |
| --- | ---: | ---: | ---: | ---: |
| Full debug names, original startup readback | 28.3 | 370.0 | 28.0 | 426.3 |
| Trimmed debug names, original startup readback | 28.3 | 363.3 | 24.5 | 416.1 |
| Final configuration | 63.8 | 352.3 | 28.4 | 444.5 |

The initial name-trimming pair reduced settled total by 10.2 MiB (2.4%). The
final WebContent sample is another 11.0 MiB lower, but the host is 35.5 MiB higher:
**the final cold total is worse, not a demonstrated improvement**. The cause of
that host retention difference has not been isolated. These runs do not justify
attributing an exact RAM saving to either startup detachment or name trimming.
The worker tests independently establish that the now-unneeded readback buffer
is released without losing database contents.

Raw cold measurements: [full names](../scripts/perf/results/desktop-memory-retention-cold-control-2026-10-03.json),
[trimmed names](../scripts/perf/results/desktop-memory-retention-cold-trimmed-2026-10-03.json),
[final configuration](../scripts/perf/results/desktop-memory-retention-cold-final-2026-10-03.json).

### Data integrity and artifacts

The final full run's saved IndexedDB snapshot passes SQLite integrity checking.
It contains 20,000 postings, including exactly 10,000 imported rows with unique
IDs and 10,000 matching change-log version records. No test data was added to the
production Shilling sandbox. The matched cold database also passes
[integrity checking after reopening](../scripts/perf/results/desktop-memory-retention-cold-integrity-2026-10-03.json).
The owned benchmark processes and loopback server were stopped, and the original
benchmark sandbox was restored after measurement.

- Full runs: [fresh control](../scripts/perf/results/desktop-memory-retention-control-2026-10-03.json),
  [screen/review candidate](../scripts/perf/results/desktop-memory-retention-screens-2026-10-03.json),
  [rejected trimmed experiment](../scripts/perf/results/desktop-memory-retention-trimmed-2026-10-03.json),
  [rejected untrimmed experiment](../scripts/perf/results/desktop-memory-retention-untrimmed-2026-10-03.json),
  [final configuration](../scripts/perf/results/desktop-memory-retention-final-2026-10-03.json).
- [Final runtime counters](../scripts/perf/results/desktop-memory-runtime-final-2026-10-03.json),
  [final source/artifact fingerprints](../scripts/perf/results/desktop-memory-retention-source-2026-10-03.json),
  [rejected experiment fingerprints](../scripts/perf/results/desktop-memory-retention-experiment-source-2026-10-03.json),
  [final database integrity](../scripts/perf/results/desktop-memory-retention-integrity-2026-10-03.json).

## Verification

- 187 JVM tests and 11 web tests pass on the final source.
- Production release web and isolated native desktop builds pass.
- Architecture guard, shell syntax and diff checks pass.
- State expiry/reopening, duplicate checking, release artifact semantics and
  existing import/persistence behavior are covered as described above.
