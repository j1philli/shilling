# Physical iPhone receipt profiling — 2026-09-29

Scope: iPhone SE (iPhone12,8), iOS 18.7.8, Release build of the private-based
worktree at `73ace52` with local audit changes. These are not public-main
measurements. Ktor is 3.6.0. Synthetic receipts travel directly over LAN WebRTC
to/from headless Chrome; the local server carries signaling only.

## Retained changes

- Replaced the iOS receiver's repeated Default → Main → Default empty polls
  with a suspending receive on its existing 256-message bounded queue. Closing
  or cancelling a listener releases its retained native delegate; overflow still
  closes the transport so receipt retries can recover.
- Fixed a first-request race during native delegate installation. Ktor can
  [queue received messages](https://github.com/ktorio/ktor/blob/3.6.0/ktor-client/ktor-client-webrtc/common/src/io/ktor/client/webrtc/WebRtcDataChannel.kt)
  before Shilling handles the channel-open event.
  The adapter now moves those messages into its bounded queue before admitting
  new callbacks. It does not add a connection delay or a transport fallback.
- Moved the iOS storage and WebRTC adapters into `app/ios-platform`, shared by
  the normal app and the new isolated `finance.shilling.perf` app. This avoids
  benchmarking copied production code. Receipt persistence remains behind
  Store5; the fixture clears only its own sandbox's receipt files.
- Added repeatable physical iOS build/install, CPU profiling and reconnect
  workloads under `scripts/perf`.

The handoff defect affected both the old polling receiver and the first
suspending candidate. Failed requests left the browser channel connected/open
with zero bytes and no header after 120 seconds. A diagnostic 500 ms setup wait
made a full run succeed. After the fix, native logs confirmed adoption of early
messages and all ten successive immediate-request connections passed. The full
regression also passed without the diagnostic wait.

## Connected-idle CPU

Both builds were launched under Time Profiler for 20 seconds, connected to an
idle browser peer early in the capture, and sent no receipt payloads. Compare
seconds **10–14**, after startup and with nominal thermal state in both traces:

| Four-second window | Original polling | Retained receiver |
| --- | ---: | ---: |
| 1 ms CPU samples / sampled CPU weight | 4,148 / 4,148 ms | 43 / 43 ms |
| UI-thread samples | 3,125 | 0 |
| GC samples | 48 | 18 |

This is a **99.0% reduction in sampled CPU weight** for the connected-idle
workload. The polling build used roughly one CPU core while idle; the replacement
waits for a native callback. Zero UI samples means none were observed in this
window, not a guarantee of zero UI work. This is not a battery-life measurement.

The default final-five-second window (15–20) also fell from 5,084 to 32 samples,
but the polling capture entered fair thermal state at 14.663 s while the retained
capture remained nominal. Both windows and thermal intervals are preserved in
the results; the earlier common nominal window is the primary comparison.
An earlier independent five-second polling capture had 5,222 samples at nominal
thermal state. Profiles were separate from throughput measurements. No builds
ran during the selected CPU windows.

## Transfer throughput

Every sample verifies all 52,428,800 payload bytes and 3,200 binary frames.
No profiler or build ran during these throughput batches. Chrome used its
ordinary data-only UDP socket with 65,536-byte receive/send buffers, without
media negotiation or browser field trials.

| Build / chronological batch | 50 MiB samples (seconds) | Median |
| --- | --- | ---: |
| Original polling receiver | 2.349, 2.494, 2.476 | 2.476 s |
| Suspending candidate before handoff fix | 3.851, 3.700, 5.892 | 3.851 s |
| Retained receiver, batch A | 3.441, 4.471, 5.216 | 4.471 s |
| Original polling control, repeated later | 4.213, 2.961, 3.798 | 3.798 s |
| Retained receiver, batch B | 3.054, 2.361, 2.877 | 2.877 s |

These separate-connection batches vary substantially and do **not** establish
a receiver throughput speedup or a stable slowdown. The confirmed benefits are
removing idle polling and recovering requests at connection setup. The later
polling control and retained batch B both used a 500 ms pre-request wait to make
the old receiver measurable; that wait is outside the reported request timing.
All retained correctness workloads and batch A used no setup wait. The fixture
logging sink also changed from OSLog to console diagnostics between the old and
retained builds, so these are not a controlled single-variable throughput study.

Mac UDP counters still showed socket drops in both builds. They are system-wide
and cannot attribute every drop to this connection. Single full-regression
transfers took 5.925 s for 50 MiB and 3.581 s for the complete retry; these are
correctness runs, not selected speed-comparison samples.

## Physical verification

- Ten successive connections, each immediately requesting and verifying 1 MiB:
  passed, browser errors empty.
- Full 1/10/50 MiB downloads: passed.
- Reconnect with the fixture's 100 ms / 128 KiB/s / 1% loss emulation, then 1 MiB:
  passed in 14.617 s. This is browser-emulated impairment, not a measured mobile
  network service level.
- Interrupt 50 MiB after receiving 1 MiB; reconnect and retry the complete
  50 MiB: passed.
- Browser uploads of 1 and 50 MiB, native Store5 persistence and byte-verified
  readback: passed. The 50 MiB readback took 3.583 s; this timer does not measure
  upload throughput.
- Release benchmark build/sign/install and normal iOS app ARM64 compilation:
  passed. The normal app was not replaced on the phone.
- `just guard-architecture`, JavaScript syntax checks, shell syntax check and
  `git diff --check`: passed.

## Measurement limits

The fixture uses the production sync dispatcher (`Dispatchers.Default`),
receipt router, encoding and iOS storage/receive adapters. It seeds receipts
only, omits full entity snapshot backfill, and displays a UIKit status label.
These runs do not measure Compose screen rendering, multi-peer load, peak RSS
or battery life. Initial fixture runs on `Dispatchers.Main` were discarded as
performance evidence because that did not match production bootstrap.

The experimental `IOS_RECEIVER=ktor` mode remains diagnostic only. Ktor's
default receive queue is unbounded; the production adapter remains bounded.

Sanitized results:
[`iphone-se-2026-09-29.json`](../scripts/perf/results/iphone-se-2026-09-29.json).
Reproduction commands: [benchmark README](../scripts/perf/README.md).
Raw traces and console logs remain local because they contain device and
network identifiers.
