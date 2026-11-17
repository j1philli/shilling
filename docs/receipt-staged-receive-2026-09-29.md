# Staged native receipt receive — 2026-09-29

This pass addresses the receive buffer left by native receipt sending. A valid
50 MiB header previously allocated a 50 MiB `ByteArray` immediately. Android
and iOS now stage incoming chunks in a private file through Store5, then
publish the file after every expected chunk arrives. The wire protocol and
WebRTC P2P transport are unchanged. SQL-backed targets keep their buffered
receive path; their storage needs a separate measured approach.

## Implementation

`FileTransferManager` validates the header, chunk index and exact chunk size
before writing. The native `ReceiptFileWriter` writes each 16 KiB chunk through
the Store5 source of truth. Android writes a random-access stage file; iOS uses
file-handle range writes. A duplicate chunk is ignored and reordered chunks
write at their declared offset. On completion, an atomic same-directory rename
replaces the receipt. An incomplete or cancelled transfer removes its stage and
leaves any earlier receipt visible. Both native stores remove abandoned stage
files when initialized after a process restart.

The isolated Android fixture now exposes the same staged storage interface as
the app. A first candidate capture was discarded because its fixture wrapper
still exposed the old ranged-only interface. All measurements below use the
corrected fixture.

## Physical Pixel measurement

The Release `finance.shilling.perf` fixture ran on a physical Pixel 6 with its
existing synthetic 1/10/50 MiB receipts. Each 50 MiB upload used a fresh app
process and a fresh Chrome peer. The browser sent 3,200 binary WebRTC frames;
the native Store5 commit marker was observed before measuring peak RSS. This
receive-only workload does not request the file back, so sender memory cannot
obscure the receive peak. `VmHWM` is lifetime peak process resident memory.

| 50 MiB receive-only workload | Buffered receive | Staged receive |
| --- | ---: | ---: |
| Peak RSS, three runs | 249.6 / 245.8 / 244.2 MiB | 216.6 / 216.4 / 216.8 MiB |
| Median peak RSS | **245.8 MiB** | **216.6 MiB** |
| Median elapsed, including a fixed 2 s post-send wait | 12.228 s | 12.108 s |

The median peak fell **29.1 MiB**. Elapsed times overlap, so the result does
not establish a throughput change. The upload-only browser checks sent byte
and frame counts, while the native commit marker confirms storage. A separate
staged 50 MiB upload **and readback** verified all 52,428,800 bytes and 3,200
frames end to end. The full live suite also completed 1/10/50 MiB receives, a
slow-link receive, interrupted send, reconnect and 50 MiB retry, and 1 MiB
upload/readback with no browser errors.

## Verification and limits

The JVM suite passed **122 tests**, including staged visibility, duplicate and
reordered chunks, incomplete transfer, and cancellation. Android app and
fixture Release builds, iOS app and fixture ARM64 debug compilation, and Web
WASM debug compilation passed. `just guard-architecture` passed. iOS staged
receive was compiled but not profiled on a device; iPhone app UI remains
excluded as requested. SQL-backed web/desktop receive memory is unchanged.
The Pixel peak includes Android and WebRTC native memory, and the fixture
measures synthetic receipts only.

Sanitized measurements: [Pixel staged receive results](../scripts/perf/results/pixel6-receipt-staged-receive-2026-09-29.json).
Reproduction: [performance README](../scripts/perf/README.md#receipt-peak-memory).
