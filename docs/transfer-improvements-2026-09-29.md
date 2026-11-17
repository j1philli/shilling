# Receipt transfer improvements — 2026-09-29

Scope: the private-based worktree at `73ace52`, with local audit changes. These
are physical Pixel 6 → Chrome measurements, not public-main or iPhone throughput
claims. Public main already uses Ktor 3.6.0; the receipt changes need selective
porting and new measurements there.

## Retained changes

- Upgraded the shared Ktor version from 3.5.2 to 3.6.0. The
  [Android data-channel implementation](https://github.com/ktorio/ktor/blob/3.6.0/ktor-client/ktor-client-webrtc/android/src/io/ktor/client/webrtc/DataChannel.kt)
  now throws when native sends fail, allowing the existing receipt retry path to
  recover. The underlying Android WebRTC dependency remains 1.3.10. Ktor also
  [retains its iOS delegate](https://github.com/ktorio/ktor/blob/3.6.0/ktor-client/ktor-client-webrtc/ios/src/io/ktor/client/webrtc/DataChannel.kt);
  the app's existing native receive adapter remains pending a separate physical
  regression check of removing that adapter.
- Made binary receipt chunks mandatory, avoiding per-chunk Base64/JSON. The
  pre-GA protocol has no older-peer negotiation or text-chunk fallback. All
  receipt traffic uses WebRTC. See [the wire protocol](receipt-binary-protocol.md).
- Retained bounded background encoding, fixed 16 KiB payloads, 256 KiB send
  backpressure, the 50 MiB receive budget and Store5 file ownership. The iOS
  receive adapter now preserves binary messages; overflow closes the channel for
  retry instead of silently dropping old messages.

## Same-connection comparison

These measurements preceded removal of the temporary text compatibility path.
They compare the previous text format with the binary framing retained today.

The release benchmark alternated formats over one direct LAN connection with
Chrome's normal **64 KiB data-only UDP receive buffer**. No video negotiation,
browser field trials or operating-system tuning was used. All transfers verified
every byte of a 50 MiB synthetic receipt.

| Pair (execution order) | Text | Binary | Elapsed-time reduction |
| --- | ---: | ---: | ---: |
| 1 (text, binary) | 8.853 s | 7.500 s | 15.3% |
| 2 (binary, text) | 9.063 s | 7.128 s | 21.3% |
| 3 (text, binary) | 7.748 s | 6.822 s | 11.9% |
| Median | **8.853 s** | **7.128 s** | **19.5%** |

Data-channel application bytes per receipt fell from **70,309,539** to
**52,480,249**, a **25.4%** reduction. These counters exclude lower-level protocol
headers. The retained change improves transfer time, but Chrome UDP socket drops
and SCTP recovery remain a throughput limit. It does not achieve the independent
TCP link baseline documented in the preceding audit.

## Rejected experiments and limits

Delaying every text chunk by 1 ms took 14–15 s; 2 ms took about 21 s. Delays after
groups of 2/4/8 chunks also did not consistently beat the unpaced control. These
experiments sometimes reduced global UDP-drop counters but added enough waiting
to offset the benefit. No artificial pacing is enabled in production.

Separate Ktor-only baseline groups had medians of 7.86 s (3.5.2) and 6.71 s
(3.6.0), but subsequent controls varied substantially. The old sender later
completed a compatibility run in 6.96 s. Therefore no isolated throughput gain
is attributed to the dependency update; the paired comparison above isolates
the format change on the same Ktor build and connection.

Only three format pairs were measured. Socket-drop counters cover the whole Mac
and may include unrelated traffic. Browser CPU measurements in the raw results
describe the JavaScript fixture, not the production WASM UI. Physical iPhone
binary-transfer performance has not been measured in this pass.

## Initial verification (before compatibility removal)

- **102 JVM/server tests passed**, including legacy request defaults, a stable
  frame layout, malformed/version/UTF-8/bounds rejection, duplicate handling,
  final-chunk validation and text/binary round trips.
- Release Android benchmark build/install, Android app compilation, iOS ARM64
  compilation and WASM debug build passed.
- Binary live transfers at 1/10/50 MiB passed byte verification. The emulated
  slow link completed 1 MiB in 14.44 s. Interruption after 1 MiB, reconnect and a
  full 50 MiB retry passed; the browser reported no errors.
- Browser binary uploads at **1 and 50 MiB** were stored and read back through
  the native Store5 file boundary with all bytes verified. Reported upload/readback
  timings cover readback only, not upload throughput.
- The actual older 3.5.2 benchmark APK accepted a new receiver's capability field
  and returned verified 1/50 MiB text transfers (`binaryFrames: 0`). New senders
  also served legacy text requests in all three paired runs.
- `just guard-architecture`, JavaScript syntax checks and `git diff --check`
  passed. The full live regression overlapped a final Android app compilation;
  its timings are correctness evidence, not the primary speed comparison.

Sanitized raw results:
[`pixel6-transfer-improvements-2026-09-29.json`](../scripts/perf/results/pixel6-transfer-improvements-2026-09-29.json).
Reproduction options are in [the benchmark README](../scripts/perf/README.md).

## Pre-GA protocol cleanup and current verification

Older-peer support is not required. Removed the capability flag, Base64 fields,
JSON chunk serialization, sender fallback and obsolete text benchmark hooks.
All receipt chunks now use binary framing; request/header/completion controls
remain JSON. Invalid receipt IDs are rejected instead of selecting another
format. WebRTC-only transport and Store5 ownership remain enforced.

- **102 JVM/server tests passed**, now including rejection of JSON chunk
  encoding/decoding, mandatory binary round trips and invalid receipt IDs.
- Release Android benchmark build/install, Android app compilation, iOS ARM64
  compilation and WASM debug build passed. Physical iPhone transfer testing is
  still outstanding.
- The rebuilt Pixel fixture passed 1/10/50 MiB downloads, the emulated slow link,
  interruption after 1 MiB and a complete 50 MiB retry. Every completed transfer
  used binary frames and verified every byte; the browser error list was empty.
- Browser uploads of 1 and 50 MiB passed native Store5 persistence and readback.
- `just guard-architecture`, JavaScript syntax checks and `git diff --check`
  passed.

The normal 50 MiB transfer took 5.99 s and the retry took 6.94 s. These single
runs verify the simplified protocol; they do not establish an additional speedup
over the paired binary measurements above. No builds ran during these transfers.

Sanitized current results:
[`pixel6-binary-only-2026-09-29.json`](../scripts/perf/results/pixel6-binary-only-2026-09-29.json).

## Physical iPhone follow-up

The subsequent [iPhone SE audit](iphone-performance-2026-09-29.md) completed
binary transfer, slow-link, interruption/retry and Store5 upload/readback checks.
It also replaced iOS idle polling with a suspending bounded receiver and fixed
early-message loss during receiver installation. The earlier iPhone testing
status above records the state at the time of the Pixel measurements.
