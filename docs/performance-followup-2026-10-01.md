# Performance followup after finance spaces

This pass rebased the audit branch onto public `main` at `82d94a6` and checked the new finance-space code, receipt storage and previews, signaling admission, sustained P2P transfers, and native iOS transitions. All datasets and hosted credentials were synthetic. The physical iPhone SE and Pixel 6 were used; the iPhone 16 Pro was excluded as requested.

The largest repeatable gains were the linked-transfer query and browser receipt preview. Local receipt-write failures now reach callers, and the actual Settings Remove flow was verified against the production server. Native iOS still has rendering hitches. A later visible desktop run verified receipt import and preview, with a substantial transient WebContent memory rise.

Measurements are retained in [the sanitized results](../scripts/perf/results/spaces-and-cross-platform-followup-2026-10-01.json). Reproduction commands are in [the performance README](../scripts/perf/README.md). The earlier native work remains in [the iOS audit](native-ios-audit-2026-09-29.md).

## Measured improvements

| Workload | Reference | Current | Measurement |
| --- | ---: | ---: | --- |
| Linked transfers among 100,000 postings in two spaces | 386.9 ms | 2.02 ms | Median of 12 alternating JVM runs per variant |
| Browser preview of a 10 MiB PNG | 1,101 ms | 265 ms | Median of three rendered previews per variant |
| Browser preview of a 50 MiB PNG | 4,698 ms | 734 ms | Median of three rendered previews per variant |
| Longest sampled task during 50 MiB preview | 4,250 ms | 241 ms | Chrome main-thread long-task observer |
| Largest sampled main JS heap during 50 MiB preview | 1,804 MiB | 128 MiB | Heap snapshot after each rendered preview |

The linked-transfer query selects the `space-transfer:` prefix through the existing `(space_id, pair_id)` index, inside Store5. It no longer loads every ordinary posting from every authorized space. One hundred alternating space activations had a median of 0.716 ms and maximum of 3.40 ms. Old scopes retired, accounts and transfer results remained correct, and a separate listener-instrumented run ended with zero SQL listeners. Activation timing does not include rendering the destination screen.

Browser images now use a Blob URL and native image decoding. Other Compose targets pass the bytes directly to Coil. This removes whole-image base64 expansion and the browser's extra Skia decoding path. The browser dialog revokes its URL on disposal, restores focus, and keeps its controls accessible. Web icon tooltips use browser-native hover text: the Compose popup previously left stale accessibility controls after closing. Other targets retain Compose tooltips. Receipt editors own and clear their ViewModel stores, releasing selected file bytes when closed.

The UI harness imports actual synthetic PNGs, checks stored byte lengths, checks rendered screenshot pixels, opens and closes each preview three times, checks URL cleanup, reopens an empty editor, and verifies both files after a page reload. The final run completed without page errors. Fresh onboarding was ready in 861 ms and the warm home screen in 375 ms in that run; these are individual local Chrome observations, not startup percentile estimates.

The baseline and final browser runs used the same release setup and images. Final measurements ran alongside WebRTC clients; an earlier independent dialog run measured 734 ms for the 50 MiB preview as well. There are only three samples per size. Main JS heap excludes Wasm, worker, GPU and other process memory, and is not peak memory. Preview time includes click and screenshot polling overhead. Import timing starts after file selection and ends after Save and a durable SQL check.

## Receipt correctness and Store5 ownership

The first real browser import displayed success while storing no file. The SQLDelight grouped upsert was asynchronous but was not awaited. After awaiting it, the new scoped foreign key exposed a second problem: file bytes were written before their receipt metadata existed.

`ReceiptRepository.saveWithFile` now creates metadata through Store5, writes the file through Store5, and removes partial bytes and metadata if creation fails. It rejects overwriting an existing receipt. Tests cover the foreign key, range reads, failure cleanup, and a successful retry.

Store5 write failures are response values. Repositories now inspect those responses and propagate local persistence errors. A separately marked P2P broadcast failure remains an offline success after the local commit, with Store5's retry bookkeeping retained. Retired-space writes now fail visibly. Platform file-store injection resolves the current finance-space graph instead of an unscoped singleton.

The web worker migration path was also verified for scoped keys, foreign keys, legacy ownership, failed persistence, rollback and reload. Entity and file access remains inside the Store5/repository boundary. Signaling carries control-plane metadata only; receipt payloads traverse WebRTC data channels.

## Hosted lifecycle and signaling

Hosted finance-space loading follows active subscribers. Leaving the screen cancels the load; reopening reloads it. Spaces and entitlement requests run concurrently, while local account and transfer projections run off Main. The fetched entitlement is reused. Native Settings observation also follows scene activity.

The new main serialized every hosted Join across upstream authorization. The server now admits up to 32 independent joins concurrently. Membership changes and device removal drain those admissions before changing policy and evicting sessions. A deterministic test verifies overlapping joins, exclusive mutation, cancellation and permit recovery.

Against the local production server and a synthetic hosted stub:

- 500 authenticated joins across 100 households completed with p50 601 ms and p95 818 ms.
- All 30 replacement connections survived the old socket closing.
- Cold and rotated signing keys each caused one JWKS fetch.
- Slow, failed and timed-out upstream requests passed their expected behavior checks; no blocking HTTP stacks were found in the sampled slow-upstream dump.

These are current control-plane measurements. This pass did not measure a matched serial-admission baseline or production Supabase/TLS capacity.

The actual Pixel Settings Remove button was exercised against this production server with disposable hosted identities. The registry removed the peer, its socket closed with policy code 1008, the surviving peer received `removedDeviceIds`, and Settings changed from Free plan 2 / 2 to 1 / 2 with the removed device absent.

## Sustained transfers and lifecycle

The Pixel completed a ten-round receipt soak with three interruption/reconnect/retry cycles. A three-peer run used two simultaneous browser peers with independent upload IDs and the Pixel, five rounds per browser, with an interruption and recovery on each connection. Each round downloaded 50 MiB and uploaded then read back 10 MiB. The harness checked every payload byte and terminal control message.

After local-network approval, the SE completed five rounds and one interruption/reconnect/retry cycle. Its five ordinary 50 MiB downloads took 3.00–4.00 seconds, median 3.44 seconds. All byte checks passed, and the browser reported no protocol errors. Three further 50 MiB downloads after background/resume took 3.18, 3.80 and 4.11 seconds.

These are LAN correctness soaks, not deployment capacity estimates. The simultaneous peers were browser clients connected to one native device, rather than multiple native senders. Upload result timers cover the readback, not total upload throughput.

The Pixel had 76.5 MiB PSS after the initial simultaneous soak and 85.8 MiB after the final repeat with the SE also connected. These snapshots do not isolate the additional peer from retained allocations. Its original one-second PSS sampling itself consumed measurable CPU; the fixture now allows a lower frequency or disabling it. With periodic PSS sampling disabled, separate 30-second idle windows measured 0.066% of one CPU core in foreground and 0.133% in background. Those Pixel windows had no active browser transfer peer.

SE process samples around the soak were approximately 99–106 MiB RSS after warmup, with nominal thermal state throughout. A 20-second foreground idle interval with connected peers used 1.53% of one core. The 34.5-second background-through-resume interval used 0.31%, including the transition overhead. Sampling cannot establish a leak-free long-term plateau or battery consumption. PSS, RSS and browser JS heap measure different things and must not be compared directly.

## Native iOS rendering

Two fresh SE Plan runs covered 24 section transitions. They issued no Main-thread SQL queries; repeated cycles issued no new queries, and listener counts returned to zero after closing. Two editor runs verified keyboard dismissal, focus switching, menus and cleanup, also with no Main-thread SQL queries.

Rendering traces still contain hitches. The Plan trace recorded 20 hitch intervals, maximum 83.3 ms. The editor/keyboard trace recorded 56, maximum 116.7 ms, predominantly frame-swap delays. These full traces include startup, navigation and keyboard activity. Rendering contexts can overlap, so their durations must not be summed into a wall-time hitch ratio. Display callback gaps are not themselves rendering-hitch measurements.

Main-thread samples continued to emphasize UIKit collection-view layout and visible-cell creation. The prior layout and keyboard prototypes did not show repeatable gains and remain reverted. This pass does not claim to have eliminated those native hitches.

On October 2, a new physical SE run initially failed the fixture's fixed Overview item-count check. The List was actually populated with 1,363 items and eight visible cells; the old check required 1,005–1,009. The fixture now captures the initial rendered count and validates return visits against it. Three fresh processes completed all 12 Plan phases each, with zero Main-thread database queries, zero repeat-visit reads and zero listeners after closing. Schedule transitions remained the most expensive: median maximum display-callback gaps were 78.4 ms on first visits and 76.0 ms on repeats.

A variant that suppressed List animations reduced some CPU medians but worsened repeat callback gaps for Schedules, Categories, Accounts and Overview. The baseline overlapped an Instruments capture attempt, limiting small CPU comparisons further. The variant was reverted. An `Animation Hitches` recording reached its time limit but did not finalize an export; the earlier valid hitch traces remain the rendering evidence. Display-callback gaps are reported separately and do not establish a hitch count. [October 2 Plan samples](../scripts/perf/results/native-ios-plan-followup-2026-10-02.json).

## Desktop validation and verification limits

The isolated release desktop app builds with a separate bundle identifier and database. Release logging now uses Info and no longer adds a duplicate stdout target. The earlier desktop diagnostic lacked a visible window; on October 2, the app was brought to the foreground and its WebView was inspected. Through the actual UI, 10 MiB and 50 MiB synthetic PNGs were imported, listed, and previewed. Both receipt records survived an app restart, and the 50 MiB image rendered again after restart.

Three further 50 MiB preview openings reached a rendered screenshot in 986, 1,015 and 1,011 ms. These are keypress-to-inspector observations, including screenshot capture and inspector waiting, rather than isolated decode or frame timings. The 4,000 × 4,000 synthetic PNG briefly showed a blank dialog before the image appeared. Desktop startup time and frame hitches have not been instrumented.

The likely app WebContent process was about 96 MiB RSS after closing the first preview, rose to about 938 MiB after repeated open/close cycles, then fell through 541 MiB to 39 MiB after leaving the editor. An October 2 repeat sampled RSS every 250 ms through four 50 MiB preview cycles: it started at 27 MiB, peaked at 961 MiB, and ended at 913 MiB after 35 seconds; a later settled reading was 24 MiB. A VM map during another preview reported 298.5 MiB resident and 782.7 MiB swapped in WebKit Malloc, and 42.3 MiB resident and 591.1 MiB swapped in JS VM reservations. Those categories must not be added to process RSS. Resident pages declined in this run; these samples do not establish that allocations were released or identify which application and WebKit copies caused the growth. The Tauri host stayed near 95–107 MiB RSS. The `xctrace` Allocations attach did not finalize after its 35-second limit, so it yielded no attribution trace. [Sampled memory curve](../scripts/perf/results/desktop-webcontent-memory-2026-10-02.json).

A second pass used the persisted 10 MiB and 50 MiB receipts in the isolated desktop database. The 50 MiB image is 4,000 × 4,000 pixels. In the first run WebContent peaked at 762 MiB RSS across three 50 MiB previews; a 10 MiB preview reached 382 MiB after the larger previews. The Tauri host settled near 100 MiB. WebContent fell to 146 MiB after closing the 10 MiB editor. A fresh app process initially used about 580–650 MiB WebContent RSS while loading the 60 MiB SQL.js database snapshot, before the preview workload. VM maps put the growth in WebKit Malloc and JavaScript VM reservations, with substantial pages swapped after closing a preview. The storage path reads the BLOB from SQL.js, sends it from the worker to Kotlin/Wasm, then converts it to a JavaScript typed array and Blob for WebKit image decoding. This gives several live copies of the file plus decoded pixels; RSS alone cannot assign the exact share to each copy.

The SQL worker now transfers fresh BLOB result buffers to the app instead of structured-cloning them, removing one 50 MiB inter-thread copy on this path. The browser fixture imported, rendered and durably reloaded both synthetic images without page errors. In a rebuilt desktop app, three 50 MiB previews reached 757 MiB peak WebContent RSS and the host again settled near 100 MiB. WebContent later settled to 25 MiB RSS with the editor closed. The runs had different startup collection and swapping patterns, so the near-equal peaks do **not** prove a whole-process RSS improvement. They do confirm that the worker change preserves the desktop preview path. The expensive remaining copies are the Kotlin/Wasm byte array, its JavaScript typed array and Blob, plus WebKit's decoded image; removing those would require a larger Store5-preserving preview API and separate memory validation. [Second-pass curves and phase markers](../scripts/perf/results/desktop-webcontent-memory-followup-2026-10-02.json). The architecture guard passed after the worker change.

Closing a Compose delete confirmation on the desktop removed the WebView's app controls from the accessibility tree until the app restarted, although the visible UI remained. The web confirmation now uses a native HTML modal, like receipt preview. In the rebuilt desktop app, Escape and keyboard Cancel both preserved the full Settings accessibility tree and restored focus to the trigger. No receipt or device data was deleted.

**Memory accounting correction:** a later VM map of the same idle process still reported **716.8 MiB physical footprint**, including 575.7 MiB swapped in WebKit Malloc, despite the low settled RSS. The RSS fall above therefore does not establish that allocations were released. The receipt workload leaves a substantial footprint after closing; allocation stacks are still needed to separate live retention from engine/allocator capacity. Subsequent desktop audits record physical footprint alongside RSS.

The full JVM suite passed 184 tests across six reports. Web worker persistence, database reload/rollback and SQLite migration checks passed. Release web, Android and iOS builds passed during this pass, and the architecture guard passed. The October 2 desktop web build, isolated Tauri bundle and signed SE fixture build also passed. More native rendering work needs an exportable hitch trace before claiming smoother frames; the 16 Pro remains outside this pass.


## Expanded desktop memory audit — October 2

Earlier desktop memory work covered startup and receipt imports/previews, not the
whole UI. A new isolated release `perf-web` app now exercises the production
Store5 graph, app shell, screens and route-owned editors. Its synthetic database
starts with 10,000 postings, 1,000 weekly schedules, 20 accounts, 40 categories and
250 receipt metadata records. Seeding occurs in a separate process lifetime.
The app visits all core destinations twice, opens four editors, and reviews and
imports another 10,000 CSV rows. The run completed in approximately eight minutes.
Home, populated schedule lists/editors, and the completed Home return were also
checked through the desktop accessibility tree.

These are **WebContent physical-footprint snapshots in MiB**, measured six seconds
after screen navigation. Each value includes memory retained from earlier screens;
the rows are not independent estimates of each screen's memory cost.

| Screen | First visit | Second visit |
| --- | ---: | ---: |
| Home | 387.8 | 657.0 |
| Plan, week | 428.1 | 630.1 |
| Plan, month | 484.9 | 566.2 |
| Schedules | 532.8 | 559.5 |
| Categories | 537.4 | 560.6 |
| Accounts | 552.3 | 562.4 |
| Activity | 575.5 | 572.3 |
| Receipts, metadata list | 587.6 | 584.1 |
| Settings, self-hosted | 588.9 | 577.1 |

The second pass ended slightly below the first. This short run does not demonstrate
unbounded growth on every tab switch, and it does not establish a leak-free plateau.
Navigation's lifetime WebContent footprint peak was 692.1 MiB. A separate empty
production-entry-point process had a 276.3 MiB WebContent footprint before this
fixture; it is an indicative runtime baseline, not a matched allocation comparison.

| Subsequent workload | WebContent footprint, MiB |
| --- | ---: |
| Transaction editor | 582.7 |
| Schedule editor | 599.2 |
| Category editor | 604.5 |
| Account editor | 606.6 |
| CSV review, 10,000 rows | 867.4 |
| CSV import completed | 836.5 |
| Home after import | 872.6 |
| Activity after import | 893.7 |
| Home after about one minute idle | **983.8** |

The CSV workflow raised the lifetime WebContent footprint peak to **about 1 GiB**
(`vmmap` reports this rounded as `1.0G`). Sampled WebContent RSS peaked at 937.0 MiB
and ended at only 116.7 MiB. At the final footprint snapshot, WebKit Malloc alone
had 780.1 MiB swapped: the RSS decline is **not** memory recovery. The final host,
GPU and Networking footprints were 31.8, 24.4 and 8.7 MiB respectively. The GPU
briefly reported 122.8 MiB during the repeated receipt-list visit. Process lifetime
peaks occur at different times and must not be summed as a simultaneous peak.

This confirms a high-memory CSV workload as well as the previously measured
receipt workload. Most accounted growth is in WebKit Malloc, which does not by
itself distinguish live Kotlin/Wasm objects, engine capacity, IPC buffers and
rendering resources. A post-run native heap summary found 43,617 WebCore event
listeners and 3,226 FetchResponse objects, but had no allocation backtraces and
could not map some Wasm regions. Its giant virtual allocation totals are not RAM.

Two concrete paths need attribution next: CSV parsing/review/import retains several
representations of all rows, and desktop console forwarding creates one Tauri IPC
call per log line. Debug-level missing-receipt messages were still being forwarded
as Info despite the Rust release logger's Info threshold. The 250 missing files in
this fixture trigger recovery polling without any connected data peer. The heap
object counts make IPC/logging worth isolating, but do not prove it is the cause of
the retained footprint. No production memory reduction is claimed by this audit.

Coverage limits: this is one sequential run on a busy Mac. It does not cover hosted
space switching/authentication, long-list scrolling, native file-picker memory,
or receipt decoding (the latter has its separate receipt benchmark). The CSV uses
the actual route-owned ImportViewModel, bypassing only file selection and confirmation
interaction. Mobile memory had been sampled previously: the SE tab soak settled
around 145 MiB RSS and Pixel screen-open snapshots around 76–84 MiB PSS; those are
different metrics and neither is a comprehensive allocation/retention audit. The
16 Pro remains excluded. No server memory audit was added in this run.

[Complete phase and RSS samples](../scripts/perf/results/desktop-wide-memory-2026-10-02.json),
[empty desktop baseline](../scripts/perf/results/desktop-empty-memory-2026-10-02.json),
and [reproduction instructions](../scripts/perf/README.md#desktop-memory-across-screens).
The isolated release frontend and Tauri bundle built successfully; the architecture
guard, sampler Python syntax, build-script shell syntax and `git diff --check` passed.
