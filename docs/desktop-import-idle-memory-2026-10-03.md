# Desktop import and idle memory follow-up — October 3, 2026

The complete preceding performance audit merged into public `main` through
[PR #43](https://github.com/j1philli/shilling/pull/43), commit `1a865d1`.
This follow-up starts from that merged version. The earlier full workload ended
at 879 MiB across the host, WebContent, GPU and Networking processes; see the
[preceding report](desktop-memory-reduction-2026-10-03.md).

The final full workload ended at **727.3 MiB**, down from **879.0 MiB** across
the four app processes (**17.3%**). WebContent ended at **619.6 MiB**, down from
796.1 MiB (**22.2%**), and its reported lifetime peak fell from 940.4 to 798.9 MiB.
The new full run uses the production worker and no optional SQL probe.

| Phase | WebContent: merged audit → follow-up | Four-process total: merged audit → follow-up |
| --- | ---: | ---: |
| Initial seeded Home | 382.1 → 421.0 MiB | 480.1 → 521.0 MiB |
| End of second navigation pass | 458.5 → 533.7 MiB | 553.2 → 638.9 MiB |
| Review 10,000 CSV rows | 579.6 → 581.4 MiB | 661.8 → 688.6 MiB |
| Immediately after import | 841.9 → 684.2 MiB | 924.6 → 791.6 MiB |
| Final idle after returning Home | 796.1 → 619.6 MiB | 879.0 → 727.3 MiB |

The improvement is in the heavy import and subsequent idle measurement.
Navigation/startup were higher in this run; no navigation improvement is
claimed. The changes do not establish a universally lower footprint on every
screen, and the short-run variation below limits attribution of individual
steps. Totals sum sequential phase snapshots rather than simultaneous peaks.
Raw [full workload samples](../scripts/perf/results/desktop-memory-import-idle-full-2026-10-03.json)
and [source fingerprints](../scripts/perf/results/desktop-memory-import-idle-source-2026-10-03.json)
are retained alongside the earlier baseline.

## Import allocation changes

Duplicate detection previously fetched every posting in the CSV's date window,
including all other accounts, then scanned the selected account's list for each
CSV row. A dedicated read-only Store5 projection now fetches only date, amount
and title for the selected account and finance space. Date buckets restrict the
remaining comparisons while preserving the existing case-insensitive title and
strict half-cent amount tolerance.

The synthetic 20-account fixture now returns **500 candidate rows instead of
10,000 full postings** for that query. The SQL probe confirms those counts.
There is no new schema migration or transport. Entity writes and version/P2P
behavior still use the existing Store5 import batches.

Each review projection now computes its reference date once rather than once
per row. Import consumes a sequence of parsed rows, immediately maps selected
rows to the compact per-category input, and discards their temporary raw CSV
fields. It no longer retains a second full parsed CSV list alongside the review
list. The category groups and original review still exist during import; this
is not a claim of constant-memory CSV ingestion.

The first shorter native run, containing just these import changes, settled at
**469.2 MiB WebContent / 561.7 MiB across the four processes**. The preceding
shorter batch diagnostic settled at 823.8 MiB WebContent. The changes are a
bundle; these runs do not assign a byte saving to each individual change.

Evidence: [native samples](../scripts/perf/results/desktop-memory-import-projection-2026-10-03.json),
[SQL probe](../scripts/perf/results/desktop-memory-import-projection-sql-2026-10-03.json).
The stopped fixture's persisted database passed SQLite integrity checking and
contained 20,000 postings, including all 10,000 unique imported rows; see the
[integrity result](../scripts/perf/results/desktop-memory-import-integrity-2026-10-03.json).

## Offline idle work

The receipt retry timer previously checked every missing file and receipt record
every 30 seconds, even with no open P2P channel. The fixture has 250 receipts
without local bytes. In the first follow-up run, the SQL count increased by 750
between `home_settle` and `final_idle`, despite no user action or connected peer.

The production session now supplies actual WebRTC channel availability to the
router. Offline startup scans and retry ticks return before any receipt queries
or queued file requests. The existing peer-connected event immediately scans
and requests missing files when communication becomes possible. Pending requests
are retained and rechecked against current metadata and file presence on
reconnect. Connected-peer retries retain their original behavior.

The regression test controls retry ticks directly. It verifies no offline file
checks, immediate requests on connection, no repeated checks after disconnect,
and removal of requests for receipts deleted or completed while disconnected.

The native SQL probe confirms **30,237 calls at Home settle, final idle and
completion**, with no intervening queries or database snapshots. Before this
fix the same idle interval added 750 SQL calls. See the
[idle SQL evidence](../scripts/perf/results/desktop-memory-import-idle-sql-2026-10-03.json).
The combined short run nevertheless settled at 684.5 MiB WebContent, higher
than the preceding import-only candidate's 469.2 MiB. Thus removing idle work
does not by itself guarantee a smaller physical footprint; collection of
temporary buffers materially affects these runs.

## Database export buffer lifetime

The worker now detaches SQL.js's standalone exported snapshot buffer after
IndexedDB finishes the write. The persisted structured clone and SQL.js's live
Wasm database remain intact. Detachment also runs after a failed persistence
attempt; failed commits still poison the worker as before so reopening reloads
the last durable snapshot. Cleanup failures cannot change the commit outcome.

This uses feature-detected `ArrayBuffer.transfer(0)`. The zero length discards
the old contents, and the original buffer becomes detached; see
[MDN's transfer documentation](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/ArrayBuffer/transfer).
WebKit introduced this API in [Safari 17.4](https://webkit.org/blog/15063/webkit-features-in-safari-17-4/).
Older engines fall back to ordinary collection. This is buffer-lifetime
management, not a forced garbage collection or an IndexedDB format change.

The isolated worker experiment confirmed 26 snapshots were detached and ended
at 614.5 MiB WebContent, versus 684.5 MiB in the immediately preceding combined
short run. This remains above the first candidate's 469.2 MiB; the native
observations demonstrate substantial variability, not a guaranteed percentage
for this step alone. Evidence: [native samples](../scripts/perf/results/desktop-memory-import-detached-csv-2026-10-03.json)
and [buffer counts](../scripts/perf/results/desktop-memory-import-detached-sql-2026-10-03.json).

The persistence harness now models IndexedDB's copied argument and verifies
retained export views become empty when detachment is supported. It also covers
missing support and throwing cleanup methods, while verifying saved row bytes
remain intact. Migration, rollback, failed commit, reload, scoped keys and
foreign-key checks pass against the actual SQL.js worker.

## Verification and measurement scope

- All 186 JVM tests and eight web tests pass, including account/space/date
  isolation, duplicate matching, import selections/categories, batch behavior,
  and disconnected/reconnected receipt retries.
- Release production web and isolated native desktop fixture builds pass.
- Architecture guard and diff checks pass.
- The fixture uses the same saved 10,000-posting database as the preceding audit,
  with 1,000 schedules, 20 accounts, 40 categories and 250 receipt metadata rows.
- Measurements use native `vmmap` physical footprint, including swapped or
  compressed allocations. RSS alone is not used as evidence of memory release.
- These are sequential observations on a busy shared Mac, with other workloads
  running. Garbage collection and rendering affect individual samples. Receipt
  image previews and transfers are not covered by this metadata-only workload.

The fixture also supports `SHILLING_MEMORY_WORKLOAD=idle`: it opens Home and
waits without importing. Restoring the completed import database before that
workload distinguishes the larger dataset's cold footprint from the allocation
history of the import itself.

The final cold restart reused the completed **20,000-posting database**.
WebContent measured **371.3 MiB** on Home and **371.6 MiB** after the idle wait;
four-process totals were **470.2 and 470.5 MiB**. See the
[cold idle samples](../scripts/perf/results/desktop-memory-import-cold-idle-2026-10-03.json).
The difference from the 727.3 MiB full-workload idle result includes navigation,
JIT warmup and import allocation history; it cannot all be labeled an import
leak. Substantial session-dependent memory remains to be attributed.

The final persisted snapshot again passed SQLite integrity checking and retained
all **20,000 postings**, **10,000 unique imported rows**, and **10,000 imported
entity version records**. The [final integrity result](../scripts/perf/results/desktop-memory-import-idle-integrity-2026-10-03.json)
was read from the stopped fixture's copied IndexedDB snapshot. The successful
cold native launch also exercised reopening that persisted database.

All owned benchmark processes and the loopback fixture server were stopped,
and the pre-investigation benchmark storage was restored. Production application
storage was not used.
