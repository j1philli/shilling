# Native receipt streaming — 2026-09-29

This pass removes the full receipt buffer from native **sending**. It uses the
private-based performance worktree, synthetic receipts, a physical Pixel 6,
and a local control-plane server. The Release fixture is isolated from the
normal Shilling sandbox. Every measured receipt traversed a WebRTC P2P data
channel; the server carried signaling only. iPhone app UI was outside scope.

## Change

`FileTransferManager` now emits its header and 16 KiB wire chunks from a lazy
flow. Native storage reads at most 256 KiB per Store5 read. Android uses local
random-access file reads; iOS uses file-handle range reads. Both enter through
the Store5 receipt source of truth. The storage mutex and file descriptor are
released between blocks, so a slow peer cannot hold either for the duration of
a transfer. Store5 invalidates an active native reader when its receipt is
replaced or deleted. A failed local read tells the receiver to discard its
partial transfer, and a later request can retry.

SQL-backed receipt storage retains a buffered snapshot. A local sql.js
diagnostic using synthetic 50 MiB blobs measured a median **246.9 ms** for one
whole read versus **2,658.4 ms** for 256 KiB `substr` slices over three samples.
The SQL range candidate was rejected. This diagnostic measures query and copy
costs only; it is not a browser application or network benchmark.

## Physical Pixel result

The matched before/after fixtures used the same installed synthetic 50 MiB
receipt and a freshly started process per workload. `VmHWM` is lifetime peak
process resident memory; initial values were 148.5–149.2 MiB. Chrome and the
Pixel selected host UDP candidates. All completed transfers verified
52,428,800 bytes and 3,200 binary frames.

| Workload | Buffered sender | Native range sender | Difference |
| --- | ---: | ---: | ---: |
| Pixel sends 50 MiB three times, peak RSS | 342.1 MiB | 283.6 MiB | **58.5 MiB lower** |
| Pixel sends 50 MiB, median elapsed | 5.923 s | 5.811 s | 0.112 s lower |
| Browser uploads 50 MiB, Pixel stores and sends readback, peak RSS | 316.6 MiB | 318.4 MiB | 1.8 MiB higher |
| Upload/readback elapsed, one run | 5.730 s | 6.531 s | 0.801 s higher |

The three send times were 5.923/5.738/6.804 s before and
5.042/5.931/5.811 s after. Their spread is larger than the median change, so
this establishes a memory improvement, **not** a reliable speedup. The reverse
direction still needs a 50 MiB receive assembly buffer and a Store5 write;
its similar peak is expected. The upload/readback timer covers the readback
after upload, not upload throughput. Single reverse runs cannot establish a
regression or improvement.

**Follow-up:** Android and iOS native receive now use staged Store5 file writes.
The separate [staged receive measurement](receipt-staged-receive-2026-09-29.md)
isolates Android receive peak memory without a readback transfer.

The full live suite passed with byte-verified 1/10/50 MiB receives, a 1 MiB
emulated slow link, interruption after 1 MiB of a 50 MiB send, reconnect and
successful 50 MiB retry, and a 1 MiB upload/readback. The browser reported no
errors. This checks the lazy flow's cancellation and retry behavior on a real
WebRTC connection.

## Verification and limits

The JVM suite passed **120 tests** across six report files, including Store5
range reads, invalidation, read-failure recovery, bounded blocks, cancellation,
and transfer retry. Android app and fixture Release builds, iOS app and fixture
ARM64 debug compilation, and the Web WASM debug build passed. The architecture
guard passed. These device memory measurements cover Pixel only. SQL-backed
web/desktop memory, iOS runtime memory, and receipt preview remain separate
workloads. `VmHWM` includes the Android runtime and native WebRTC allocations;
it does not measure retained receipt bytes alone.

Sanitized measurements: [Pixel receipt streaming results](../scripts/perf/results/pixel6-receipt-streaming-2026-09-29.json).
Reproduction: [performance README](../scripts/perf/README.md#receipt-peak-memory).
