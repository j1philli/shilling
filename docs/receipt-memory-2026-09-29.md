# Pixel receipt peak memory — 2026-09-29

Scope: physical Pixel 6, Android 16, Release `finance.shilling.perf` fixture from
the private-based worktree at `73ace52` with local audit changes. All receipts
are synthetic; the normal Shilling app and its data were preserved. The live
workload transfers exactly 50 MiB in 3,200 binary WebRTC frames through the
production Store5 receipt file boundary. The local server carries signaling
only. iPhone app UI is outside this pass.

## Measurement

The fixture records `/proc/self/status` `VmHWM`, the process's lifetime peak
resident memory. Unlike a sampled PSS or Java heap reading, it catches brief
allocation spikes. Its value is cumulative, so each one-transfer comparison
uses a freshly started process. The live fixture first writes 1/10/50 MiB
receipts into its own sandbox, then the measured processes reopen those existing
files with `reuseReceipts=true`. This keeps the immediate 50 MiB seed allocation
out of the transfer start. Starting `VmHWM` was 149–150 MiB in these runs.

| Live 50 MiB workload | Before peak RSS | After peak RSS | Reduction |
| --- | ---: | ---: | ---: |
| Pixel sends to browser, one transfer | 329.8 MiB | 279.4 MiB | 50.4 MiB |
| Browser uploads, Pixel stores then sends readback | 345.9 MiB | 296.7 MiB | 49.3 MiB |
| Pixel sends to browser, three transfers in one process | 370.4 MiB | 337.7 MiB | 32.7 MiB |

The upload/store marker was 273.6 MiB before and 246.3 MiB after. The final
peak for that workload includes the subsequent native readback. These absolute
RSS figures include Android runtime and WebRTC native memory; they are not the
size of retained receipt bytes. Repeated-transfer peaks differ because garbage
collection and native allocations occur at different points in each process.

The offline fixture, which deliberately keeps sender, receiver and comparison
arrays in one process, completed with a 356.4 MiB peak RSS. At the start of its
50 MiB case it had already reached 239.9 MiB. This is a memory stress test,
not a live-peer memory estimate. The Java heap limit was 256 MiB, and this run
completed without an allocation failure.

## Change

`FileTransferManager` now emits views of its already loaded receipt buffer
instead of allocating another 16 KiB array per chunk. `BinaryFileChunkCodec`
copies directly from that view into the wire frame. On receive, the decoded
chunk views the received frame until `handleChunk` copies its payload into the
single validated receive buffer. External access to `FileChunk.bytes` still
returns the expected standalone payload. The wire format, 16 KiB frame size,
bounded send queue and Store5 ownership are unchanged.

The before/after process peaks are consistent with removing one complete
receipt's worth of intermediate chunk copies. The live receiver still needs one
50 MiB assembly buffer, and native WebRTC and remaining frame allocations
contribute to peak RSS. Whole-file Store5 reads on send also remain. A streaming
Store5 file interface would be a larger follow-up; this change does not create
an alternate persistence path.

## Correctness and throughput

Both one-transfer directions completed and verified all 52,428,800 bytes and
3,200 binary frames. The browser reported no errors for upload/readback. Three
native-to-browser repeats on each version also verified every byte. Their
times were 7.20/8.13/6.04 s before (median **7.20 s**) and
6.30/7.09/6.84 s after (median **6.84 s**). Run-to-run spread exceeds the
median difference; this pass establishes a memory reduction, not a reliable
throughput speedup. The browser upload/readback result's timer measures the
**readback**, not upload throughput.

The fixture Release build and 115 JVM tests passed. The Web WASM debug build
and iOS ARM64 debug build passed. `just guard-architecture` and
`git diff --check` passed. Device runtime measurements here cover Pixel only;
neither iPhone app UI nor web/desktop peak memory was measured in this pass.

Sanitized measurements: [Pixel receipt memory results](../scripts/perf/results/pixel6-receipt-memory-2026-09-29.json).
Reproduction: [performance README](../scripts/perf/README.md#receipt-peak-memory).
