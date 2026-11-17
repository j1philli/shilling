# Receipt-list Store5 projection — 2026-09-29

The receipt list now reads its displayed rows through a dedicated local Store5
projection using the existing `selectAllWithPostings` SQL join. It previously
loaded every receipt, posting and schedule, then built lookup maps in Kotlin.
With 250 receipts attached to a 10,000-posting library, initial list delivery
on the physical Pixel fell from **93.2 ms to 17.3 ms** in the final comparison.
This is a data-delivery measurement, not Compose frame time.

Scope is the private-based `t3code/audit-shilling-performance` worktree and its
local audit changes. These are not public-main release measurements. Only the
isolated `finance.shilling.perf` app and synthetic data were used. The normal
Shilling app and its data were preserved. No server or network was needed.

## Retained change

`ReceiptStore` owns both the existing mutable receipt store and an uncached,
read-only Store5 projection. The latter uses local-only reads, with SQLDelight
listening to receipts, postings and schedules. `ReceiptRepository.watchAll()`
observes this projection, and equal results are suppressed. Repository and
application wiring no longer inject entire posting and schedule stores solely
for this list. The existing title fallback, date formatting, metadata, sort
order and unattached/missing-posting behavior are preserved. There is no schema
migration or new persistence path.

The original schedule-exception batching fix was already present, so this pass
focused on the remaining receipt-list projection work.

## Matched physical measurements

Both variants run in the same Release APK: production `ReceiptRepository` for
the joined variant, and a benchmark-only copy of the original combined Store5
projection for the reference. An earlier saved baseline APK also showed the
same full-table reads and approximately 88 ms initial/posting refresh medians.

Each dataset contains 10,000 postings, 1,000 schedules and either 250 or 1,000
attached receipts. All setup uses production Store5 and the Android SQLite
driver. Three fresh process cohorts per variant alternate their order. Each
process takes five samples per workflow, giving **15 samples per cell** below.
Initial samples subscribe anew to the list; they do not measure process startup
or disk-cache cold starts. An edit timer stops at the first list emission with
the correct changed value. Builds finished before measurements began.

| Workflow, median ms | 250 receipts, reference | 250 receipts, joined | 1,000 receipts, reference | 1,000 receipts, joined |
| --- | ---: | ---: | ---: | ---: |
| Initial list delivery | 93.2 | **17.3** | 91.2 | **36.6** |
| Attached posting edit | 103.0 | **23.3** | 104.9 | **46.8** |
| Attached schedule title edit | 22.8 | 23.0 | 27.6 | 46.9 |
| Receipt metadata edit | 26.1 | 30.3 | 38.4 | 55.7 |

At 250 receipts, initial delivery improves **81.4%** and posting refresh
**77.3%**. At 1,000 receipts, they improve **59.9%** and **55.3%**.
The tradeoff is explicit: schedule and metadata edits are slower in the larger
library, by 19.3 ms and 17.3 ms respectively at the median. The join resolves
labels again for each changed receipt-list query, whereas the reference can
reuse previously loaded posting and schedule data. This is not a uniform
improvement across all edits. The change is retained for its substantial
initial-list and posting-refresh gains and lower full-table materialization.

| Rows returned to Kotlin, 250-receipt dataset | Reference | Joined |
| --- | ---: | ---: |
| Initial list delivery | 11,250 | 250 |
| Posting edit | 10,000 | 250 |
| Schedule edit | 1,000 | 250 |
| Receipt metadata edit | 251 | 251 |

Initial result queries fall from six to one. The counters include empty Store5
bookkeeping reads, and count returned cursor rows rather than SQLite index
visits. `sqlQueryMs` sums driver query/cursor work; overlapping reads can make
that sum exceed wall time. The reference and retained variants share this
instrumentation. Process peak memory, energy and screen rendering were not
measured in this pass.

## Rejected candidate

A second candidate shared a receipt flow and queried labels only for attached
posting IDs, in batches of 400. It avoided label queries on metadata edits, but
its additional flow and query work performed worse in the 1,000-receipt cohort:
51.7 ms initial, 49.2 ms posting, 60.9 ms schedule, and 79.0 ms metadata medians.
That candidate was removed. Its measurements are retained separately from the
final comparison in the results file. It does not establish that avoiding
label re-reads is inherently slower; it rejects this tested implementation.

## Verification

**124 JVM tests passed**, including joined title fallback, dates, ordering,
metadata, missing relationships, live changes from all three tables,
attach/detach, and deletion. Android app and fixture Release builds, iOS app
and fixture ARM64 debug compilation, and Web WASM debug compilation passed.
`just guard-architecture`, Python syntax checks and `git diff --check` passed.
The new query is shared across targets; runtime timings here cover Pixel only.
iPhone app UI remains excluded as requested.

Sanitized samples and candidate summaries:
[Pixel receipt-list results](../scripts/perf/results/pixel6-receipt-list-2026-09-29.json).
Reproduction: [performance README](../scripts/perf/README.md#receipt-list-data-refresh).
