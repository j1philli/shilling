# Performance checks

## Hosted server and signaling

```sh
VERIFY_FIXED=1 REPEATS=3 node scripts/perf/bench_server.cjs
```

Requires the normal Kotlin/JDK toolchain and Node with `ws` available. The harness
refuses to use an occupied port 8081. It generates disposable RSA keys, starts a
local Supabase stub and its own server, then terminates both. It uses synthetic
credentials and identities only; inherited Supabase JWT and TURN secrets are
removed from the child environment. The stub binds loopback; the production
server keeps its usual bind address. JSON metrics go to stdout and a local raw
server-log path goes to stderr. Do not commit raw logs containing identifiers.

Workloads cover cold JWKS misses; 10/100/500 warm metadata requests; authenticated
joins and directed Offer/Answer echoes across 1/10/100 households; 30 replacement
connections; slow, timed-out and failing membership calls; and signing-key
rotation. HTTP connections are pre-established in groups of 25. Signaling timing
includes client signing and the queue for at most 25 concurrent WebSocket opens.
No entity or receipt payload is sent to the server.

`VERIFY_FIXED=1` requires expected status counts, one cold/rotation JWKS fetch,
zero blocking HTTP stacks, and all signaling/reconnect responses before emitting
`complete`. Omit it when characterizing the original server's known failures.
`REPEATS` is 1–5 (default 1), repeating warm metadata and join cohorts in the same
JVM. `SIZES` selects metadata batch sizes, each 1–500 (default `10,100,500`);
the signaling/slow/failure cohorts are fixed. `SERVER_PROJECT_DIR` can point to
an isolated original-source project with matching dependency versions, allowing
a baseline comparison without swapping the active worktree's source files.

Run benchmarks without concurrent builds or CPU profiling. The slow-upstream
phase deliberately captures one `jcmd Thread.print` snapshot. Results measure
local control-plane behavior, not Supabase/TLS deployment capacity, WebRTC data
throughput, or peak memory. Findings and all candidate runs are documented in
[`docs/server-performance-2026-09-29.md`](../../docs/server-performance-2026-09-29.md).

## Physical Android device

Unlock the device and keep it awake. Select it with `ANDROID_SERIAL` if more than
one ADB device is connected. Both scripts build **release** APKs with shrinking and
packaged baseline profiles, signed with the standard local Android debug key.
They require an existing `~/.android/debug.keystore` and never use a production
signing key. Icons are committed on public main; no local icon generation is required.

- `scripts/perf/profile_android_startup.sh`: replaces the locally signed Shilling
  installation in place, preserves its data, then measures five force-stop/cold
  process launches. Do not confuse this with clearing disk caches or measuring
  full data readiness. Keep the same visible destination between comparisons.
- `scripts/perf/run_android_workloads.sh`: installs **finance.shilling.perf**, a
  separate application without access to the Shilling sandbox. The default
  workload activity is offline; the explicit P2P fixture has network access. Emits JSON measurements and a terminal complete/failed record. Each
  invocation resets only its own synthetic database and files.

The workload fixture includes 1,000 and 10,000 imported postings (11,000 total),
1,000 weekly schedules, recent/history/weekly repository queries, and 1/10/50 MiB
receipt round trips. All entity setup and reads use production Store5 repositories;
receipt bytes use Store5 with a private file source of truth. The benchmark uses
the same Android SQLite driver as the app. Offline sync version metadata is
included; Kermit logs below Error are disabled to avoid log-volume noise.

The receipt round trip runs sender and receiver in one process and checks every
byte, so it is a conservative memory stress test. It does **not** measure WebRTC
network throughput or preview decoding. Native file reads and encoding occur
while the message flow is consumed: compare **prepare + receive** times across versions, not
prepare alone. `VmHWM` is process-lifetime peak RSS; PSS/heap measurements are
snapshots after each operation, not peaks.
History/weekly measurements are data/projection latency, not scrolling frame time.

Do not use debug startup numbers as release performance claims. The September 28
Pixel debug APK had about 57 MB of DEX; release had 5.4 MB and baseline profiles.
Raw synthetic results are in `results/pixel6-2026-09-28.json`.

## Receipt-list data refresh

Build and sign the Release `perf-android` APK as above, install it in the isolated
`finance.shilling.perf` package, then run:

```sh
python3 scripts/perf/profile_android_receipt_list.py --label current --output /tmp/shilling-receipt-list.json
# The same APK can run the original combined Store5 projection for comparison.
python3 scripts/perf/profile_android_receipt_list.py --variant combined --label reference --output /tmp/shilling-receipt-list-reference.json
```

The script starts three fresh fixture processes and restores normal Shilling when
finished. Each process seeds 10,000 postings, 1,000 schedules and 250 attached
receipt metadata rows through production Store5, then measures five initial list
subscriptions and five edits each to an attached posting, schedule and receipt.
The existing receipt files and normal app sandbox are untouched. No server is
needed. Query counters include all SQL result queries in the measured interval;
row counters count rows returned to Kotlin, not internal SQLite index visits.
`sqlQueryMs` sums the driver calls; concurrent queries can make this sum exceed
elapsed wall time. The reference and current variants can be alternated with
`--processes 1` to reduce drift across comparisons.
Use `--receipts 1000` for the larger measured library; `--receipts 10000` is also
available for additional scaling diagnostics. The measured dataset size is
recorded in each output file.
The edit timer stops when the list contains the correct changed value. These
are data delivery timings, not Compose frame timings or process startup.

Matched Pixel results: [receipt-list performance](../../docs/receipt-list-performance-2026-09-29.md).

## Live WebRTC receipt workload

Start the production signaling server locally with `SHILLING_AUTH_MODE=none
./kotlin run --module server` (one shell command). After building/signing/installing
the benchmark APK as above:

```sh
adb reverse tcp:18081 tcp:8081
adb shell am start -S -W -n finance.shilling.perf/.P2pPerformanceActivity
node scripts/perf/bench_live_webrtc.cjs
adb reverse --remove tcp:18081
```

Requires Node with `ws` available and Google Chrome (`CHROME_BIN` overrides its
macOS default path). A fresh headless profile receives 1/10/50 MiB over a real
WebRTC data channel, verifies every byte, tests an emulated slow link, interrupts
and retries a 50 MiB transfer, and uploads/reads back 1 MiB through native Store5.
ADB forwards **signaling only**. Payloads never traverse the signaling server.
Throttling is applied before reconnecting; changing rules on an existing peer
connection did not affect it. `FAST_ONLY=1` skips impairment/reconnect workloads;
`SLOW_ONLY=1` runs baseline and throttled 1 MiB only. Native `ShillingP2p` logs
include cumulative backpressure wait counts/time, sampled PSS/heap, and the
process-lifetime peak RSS counter `VmHWM`.

### Receipt peak memory

After the P2P fixture has seeded its synthetic 1/10/50 MiB files once, restart
its process with existing files for a transfer-only memory baseline:

```sh
adb shell am start -S -W -n finance.shilling.perf/.P2pPerformanceActivity --ez reuseReceipts true
FAST_ONLY=1 REPEATS=1 SIZES=50 node scripts/perf/bench_live_webrtc.cjs
# Restart the fixture with the same command before the reverse direction.
UPLOAD_ONLY=50 node scripts/perf/bench_live_webrtc.cjs
```

`reuseReceipts=true` fails if the fixture's synthetic files have not been seeded.
Compare the `ShillingP2p` ready and final `VmHWM` values from a fresh process
for each direction. `upload_stored` marks the native Store5 write before the
subsequent readback. `VmHWM` measures lifetime peak RSS, while PSS and Java heap
are sampled once per second and can miss spikes. Do not treat the browser
upload/readback timer as upload throughput. The controlled Pixel results are in
[`docs/receipt-memory-2026-09-29.md`](../../docs/receipt-memory-2026-09-29.md).

For repeatable captures with an installed fixture, the profiling script restarts
the isolated process, waits for its ready marker, verifies every transferred byte,
saves the native high-water counters and browser results, then restores normal
Shilling to the foreground:

```sh
python3 scripts/perf/profile_android_receipts.py --workload send --repeats 3 --output /tmp/shilling-send.json
python3 scripts/perf/profile_android_receipts.py --workload upload --repeats 1 --output /tmp/shilling-upload.json
python3 scripts/perf/profile_android_receipts.py --workload receive --repeats 1 --output /tmp/shilling-receive.json
```

If another checkout is using port 8081, run the local server with
`SHILLING_AUTH_MODE=none SHILLING_PORT=18082 ./kotlin run --module server`, reverse
`tcp:18081` to `tcp:18082`, and add
`--signal-url ws://127.0.0.1:18082/ws/signal` to the profiling commands. The direct
browser harness accepts the same URL through `SIGNAL_URL`. Stop the owned server
and remove the ADB reverse mapping after profiling.

`--workload receive` uploads 50 MiB without readback, waits two seconds after
the browser send queue drains, and requires a native Store5 commit marker. It
isolates receiver peak RSS. Run several fresh processes per version because
the Android process peak varies. The browser send count and native commit
marker alone do not verify stored byte content; run `--workload upload` for a
byte-checked readback. The staged native receive findings are in
[`docs/receipt-staged-receive-2026-09-29.md`](../../docs/receipt-staged-receive-2026-09-29.md).

Native transfer readers fetch 256 KiB blocks through the Store5 source of truth;
the bounded encoding flow sends 16 KiB wire chunks from each block. Overwriting
or deleting the receipt invalidates an active native reader. No repository lock
or open file handle spans network sending. SQL storage retains a buffered file
snapshot because the tested `substr` range candidate was substantially slower.
The matched Pixel before/after memory and retry results are in
[`docs/receipt-streaming-2026-09-29.md`](../../docs/receipt-streaming-2026-09-29.md).
Reproduce that diagnostic with
`node --expose-gc scripts/perf/bench_receipt_reads.cjs`; it measures local sql.js
query/copy costs only, with no Store5, IndexedDB, UI or network workload.

## Physical iPhone WebRTC fixture

`app/perf-ios` is a separate `finance.shilling.perf` application. It shares the
`ios-platform` module with the normal iOS app, including its receipt storage and
WebRTC receive adapter. Entity/file operations use the production Store5 repositories.
It resets only its own receipt files. The normal Shilling sandbox is preserved.
Run `just setup-webrtc` first if the iOS framework has not been downloaded.

Start the local signaling server as above, then run:

```sh
IOS_DEVICE_ID=<physical-iPhone-UDID> scripts/perf/run_ios_webrtc.sh
# In another terminal, after the fixture reports ready:
PEER_ID=z-iphone node scripts/perf/bench_live_webrtc.cjs
PEER_ID=z-iphone UPLOAD_ONLY=50 node scripts/perf/bench_live_webrtc.cjs
# Exercise immediate requests on ten successive connections (no setup sleep).
PEER_ID=z-iphone RECONNECTS=10 node scripts/perf/bench_live_webrtc.cjs
```

The installer builds Release, signs, installs and streams the fixture console.
Xcode needs a development signing profile for `finance.shilling.perf` covering
the device; automatic provisioning requires the appropriate account signed in.
`IOS_TEAM_ID` overrides the repository's default team. `IOS_PERF_SIGNAL_URL`
overrides the Mac `en0` LAN address, and `IOS_PERF_BUILD_DIR` overrides the
temporary build directory. The iPhone and Mac must reach each other on the LAN.
Allow the fixture's local-network access prompt on first launch. USB is used
for deployment/profiling; receipt data still travels over WebRTC.

`IOS_RECEIVER=ktor` selects an experimental comparison using Ktor's own receive
delegate. The default `adapter` mode uses the current production iOS receive
adapter and native timer. Ktor 3.6's receive queue is unbounded by default, so
this option is a diagnostic comparison, not a production replacement for the
bounded adapter. The fixture uses a
simple native status view; it does not measure Compose screen rendering.
The fixture runs sync on `Dispatchers.Default`, matching production bootstrap.
`COMPARE` and `RAW` switches still control the Android fixture only.
`PEER_ID=z-iphone IDLE_ONLY=20 node scripts/perf/bench_live_webrtc.cjs` keeps a
connected peer idle for 20 seconds for native CPU profiling. Capture such profiles
separately from uninstrumented throughput runs, and avoid concurrent builds.
`CONNECT_SETTLE_MS=500` is a diagnostic setup delay, disabled by default. Do not
use it to validate first-request delivery; the normal and reconnect workloads
send immediately after the browser's channel opens.

For CPU captures, Instruments can launch the installed fixture directly if
attaching to a `devicectl` process fails:

```sh
xcrun xctrace record --template 'Time Profiler' --device <iPhone-UDID> \
  --time-limit 20s --output /tmp/shilling-idle.trace --launch -- \
  finance.shilling.perf --perf-server ws://<Mac-LAN-address>:8081
# In another terminal, immediately after starting the capture:
PEER_ID=z-iphone IDLE_ONLY=25 node scripts/perf/bench_live_webrtc.cjs
# Wait for "Output file saved as" before exporting.
xcrun xctrace export --input /tmp/shilling-idle.trace \
  --xpath '/trace-toc/run[@number="1"]/data/table[@schema="time-profile"]' \
  --output /tmp/shilling-idle-cpu.xml
python3 scripts/perf/analyze_ios_cpu.py /tmp/shilling-idle-cpu.xml --start 15 --end 20
```

Confirm the peer connected before the selected window. Use the same window for
both builds, exclude startup/transfer work, and inspect thermal state. The CPU
summary contains overlapping inclusive frame counts, not a battery estimate.
Keep raw traces and native logs local; they contain device/network identifiers.

Stop the console/fixture when finished and reopen the normal application:

```sh
xcrun devicectl device process launch --device <physical-iPhone-UDID> finance.shilling.app
```

## Production-screen scrolling fixture

After installing the Release `finance.shilling.perf` APK, run:

```sh
python3 scripts/perf/profile_android_ui.py --runs 3 --scroll
```

This force-stops only the isolated fixture between openings, verifies that the
10,000-posting Activity or 1,000-schedule Plan screen is populated, records the
first five frame durations, the first nonempty projection, `gfxinfo` scroll
statistics and a steady memory snapshot, then restores normal Shilling to the
foreground. The fixture seeds its own `ui-synthetic.db` only when absent. To
refresh an older fixture before profiling, launch it once with `--ez reseed true`:

```sh
adb shell am start -S -W -n finance.shilling.perf/.UiPerformanceActivity --es screen activity --ez reseed true
```

`contentReady` means Compose observed nonempty Store5 data; it can precede the
populated draw. The first frame can also show an empty state. UIAutomator text
verification is outside the readiness timer. These are cold fixture-screen
measurements; app navigation is separate. Repeat commands and controlled
results are in [`docs/pixel-ui-performance-2026-09-29.md`](../../docs/pixel-ui-performance-2026-09-29.md).

```sh
adb shell am start -S -W -n finance.shilling.perf/.UiPerformanceActivity --es screen activity
# Wait for ShillingUi status=rendering and visible data before resetting metrics.
adb shell dumpsys gfxinfo finance.shilling.perf reset
adb shell 'for i in 1 2 3 4 5 6 7 8 9 10; do input swipe 550 1800 550 800 350; done'
adb shell dumpsys gfxinfo finance.shilling.perf
```

Repeat with `--es screen plan`. Coordinates are for the Pixel 6 in portrait;
inspect the screen first on other devices. This renders the actual shared Compose
screens with 10,000 postings and 1,000 schedules through Store5. It deliberately
excludes app navigation and sync. Initial screen composition is excluded from the
reset scrolling measurement; cold render timings are available in `ShillingUi`
logs and must be reported separately. Stop the benchmark and reopen Shilling
when finished; do not clear the main application's data.

For repeated throughput comparisons, use
`FAST_ONLY=1 REPEATS=3 SIZES=50 node scripts/perf/bench_live_webrtc.cjs`.
The P2P activity defaults to the production bounded encoding pipeline.
`--ez pipeline false` selects sequential preparation for a same-build comparison.
Both preparation modes send the production binary chunk format. Every receive
checks size, chunk count and byte content; the interruption workload also
requires an interrupted result.

`COMPARE=1 node scripts/perf/bench_live_webrtc.cjs` alternates sequential and
pipeline preparation for three pairs on one connection and one running Activity.
The fixture accepts single-top intents to switch that mode without reseeding or
reconnecting. First-pair startup effects and later variation must be included in
reporting; do not select only the fastest samples.

### Binary receipts (September 29)

`node scripts/perf/bench_live_webrtc.cjs` exercises the required production binary
frame codec, including impairment, interruption/retry and native Store5
upload/readback. Control messages remain JSON. All traffic uses the same ordered,
reliable WebRTC data channel; there is no legacy text-chunk mode.

`UPLOAD_ONLY=50 node scripts/perf/bench_live_webrtc.cjs` runs a 50 MiB browser
upload and native Store5 readback. The returned timing covers readback; it is not
an upload-throughput measurement. `binaryFrames` counts received binary chunks.

Historical text/binary comparisons and rejected text-send pacing experiments
remain in the audit results. Their compatibility flags and test hooks were
removed when binary chunks became mandatory.

## Transport diagnostics

These options affect only the isolated fixture. They use synthetic bytes and can
change framing/negotiation, so their timings are not production performance claims.

```sh
# Skip receipt frame preparation and send the known byte pattern as raw binary.
RAW=ktor,native,batched:16384:10:262144,batched:65536:1:262144 \
  REPEATS=2 node scripts/perf/bench_live_webrtc.cjs

# macOS receiver socket and global UDP-drop counters, with normal receipt framing.
# Clear RAW mode on the running Android fixture first.
adb shell am start -f 0x20000000 -n finance.shilling.perf/.P2pPerformanceActivity --ez pipeline true
SOCKET_DIAG=1 FAST_ONLY=1 REPEATS=3 SIZES=50 node scripts/perf/bench_live_webrtc.cjs

# Benchmark-only receive-buffer experiment; repeat with 65536 and 1048576.
MEDIA_BUFFER_PROBE=1 RECEIVE_BUFFER_PROBE_BYTES=65536 SOCKET_DIAG=1 \
  FAST_ONLY=1 REPEATS=3 SIZES=50 node scripts/perf/bench_live_webrtc.cjs
```

`RAW` is a comma-separated list of `mode:chunkBytes:pollMs:sendBufferCapBytes`.
Defaults are 16 KiB, 10 ms and 256 KiB; chunk sizes are limited to 16/64 KiB.
`ktor` uses Ktor binary send, `native` calls the Android native channel directly,
and `batched` additionally avoids querying native buffered bytes until a
conservative queued-byte bound exceeds the cap. Every receive verifies the known
pattern. `RAW_MIB` selects one of the 1/10/50 MiB fixtures (default 50).
The normal Store5 file read still precedes the raw sender; raw timings do not
include receipt chunk/frame preparation. Sender stage timers are cumulative wall
times, overlap transmission and must not be added to browser elapsed time.

`MEDIA_BUFFER_PROBE=1` negotiates an unused receive-only video transceiver. It
does not attach a track, capture media or request camera/microphone permission.
On the measured Chrome build this raises its UDP receive buffer to 1 MiB and
send buffer to 256 KiB. `RECEIVE_BUFFER_PROBE_BYTES` additionally starts only the
fresh benchmark Chrome process with the `WebRTC-ReceiveBufferSize` field trial;
compare actual `netstat` socket sizes to verify the trial took effect. This
permits comparing receive sizes while keeping video negotiation/send-buffer size
constant. Neither option is a Shilling production setting or fix.

`SOCKET_DIAG=1` requires macOS `netstat`, prints the selected UDP socket, a two
second idle counter delta and per-transfer counter deltas. UDP-drop counters are
system-wide and can include unrelated traffic. `ROUTE_FILE=/tmp/route.json`
writes selected candidate addresses/ports. `CAPTURE=/tmp/receipt.pcap` captures
only that IPv4 UDP flow on `en0` with 128-byte snapshots; the interface must match
the selected route. Raw diagnostic output contains local network addresses;
strip them before committing results. The capture contains encrypted DTLS
records and must be assessed separately from application-side SCTP logging.

For a short native SCTP trace, restart the fixture with `--ez sctpLog true`,
capture `adb logcat --pid=<fixture-pid> -v brief` to a local file, and run one
`RAW_MIB=10 RAW=batched:65536:1` transfer. The verbose log contains synthetic
payloads and perturbs timing. Summarize it with
`python3 scripts/perf/analyze_sctp.py /tmp/sctp.log`; repeated send attempts are
not an on-wire retransmission count. Restart the fixture without `sctpLog` before
timing more workloads. Only the benchmark APK enables shell profiling.

The independent LAN TCP comparison in the audit streamed `/dev/zero` from an
Android `toybox nc` listener to a local counting client. It measures link capacity
outside Shilling; application sync continues to use WebRTC exclusively.

## Public main: native iOS and shared presentation

The audit branch now builds on public main `2abf1db`, using native SwiftUI on iOS.
The Android UI fixture renders Activity and Plan; older History/Weekly results
refer to the archived layout and are not measurements of these screens.

```sh
CI_RETRY_ATTEMPTS=1 ./scripts/ci/run-jvm-tests.sh
python3 scripts/perf/bench_activity_projection.py
```

The projection benchmark reuses the toolchain's recorded JVM test classpath and
JDK. It checks the original and indexed outputs, performs five warmups per size,
and alternates 15 measured rounds at 1,000 / 10,000 / 20,000 synthetic transfer
postings. Run after builds finish. It measures shared CPU work on the host JVM,
not iPhone rendering, SQLite latency, or Swift bridging.

See [the native UI audit](../../docs/native-ios-audit-2026-09-29.md) for results and
the remaining native profiling workloads.

### Hosted Settings polling and device removal

`hosted_control_fixture.cjs` serves synthetic registration/plan metadata with a
100 ms delay per endpoint and accepts signaling for `z-pixel` and `z-iphone` in
the `synthetic-live-perf` household. It has no entity or receipt HTTP/WebSocket
routes. Its local controller routes accept requests from localhost only.

```sh
node scripts/perf/hosted_control_fixture.cjs > /tmp/shilling-hosted-fixture.jsonl
```

With a rebuilt Release `finance.shilling.perf` installed on the phone:

```sh
python3 scripts/perf/profile_ios_native_ui.py --device <UDID> \
  --screen hosted-settings --hosted-server http://<Mac-LAN-address>:18083 \
  --runs 1 --label hosted-settings --output /tmp/hosted-settings.json
```

This mounts the production native tabs and measures Settings visible for 35 s,
hidden behind Home for 35 s, then reopened for 5 s. With negligible network delay,
the metadata request counts are 7, 0, and 3. Network delays can move the last visible
request's arrival into the hidden interval; inspect request timing for continued
polls rather than assuming that cancelling a request retracts it from the network.
App records and fixture requests include wall-clock times. Billing uses an authenticated
synthetic guest and empty configuration, so no purchase SDK service is called.
The script ends the fixture process when measurement completes. To check actual
background/resume behavior, launch the same arguments through `devicectl` without
`--console`, then read `Documents/native-ui.jsonl` from the perf app container.

For the physical removal check, forward the Android fixture's signaling port
with `adb reverse tcp:18081 tcp:18083`, launch `P2pPerformanceActivity`, and launch
the iOS fixture with `--perf-server ws://<Mac-LAN-address>:18083`. Both report
`SHILLING_PEERS` whenever their production `PeerConnectionStatus` changes. Once
both list the other peer, POST to `/__fixture/disconnect?device=z-pixel`, verify
the P2P channel remains open, then POST to `/__fixture/remove?device=z-pixel` and
verify both connected sets empty and no subsequent Offer is sent to the removed
peer. `/__fixture/readmit?device=z-pixel` allows a newly launched fixture to join
again. These controller routes simulate control-plane events; the production
server eviction/queue behavior is covered by `SignalingHubTest` and
`SignalingLifecycleTest`.

To check native receipt reception after a channel-adapter change, restart this
server with `BROWSER_PROBE=1` (which also permits the synthetic `a-browser` peer),
stop the Android fixture, and relaunch the iPhone P2P fixture. Then run:

```sh
SIGNAL_URL=ws://127.0.0.1:18083/ws/signal PEER_ID=z-iphone UPLOAD_ONLY=1 \
  node scripts/perf/bench_live_webrtc.cjs
```

This uploads 1 MiB through native Store5 and verifies the returned bytes over
WebRTC. It is a correctness check, not a before/after throughput comparison.

## Finance spaces and linked transfers

After the JVM suite compiles the shared tests, run the production Store5 projection
and its original reference on the same synthetic database:

```sh
./scripts/ci/run-jvm-tests.sh
python3 scripts/perf/bench_activity_projection.py finance.shilling.shared.data.store.SpacePerformanceBenchmark
```

The benchmark seeds 50,000 ordinary postings per space and a linked pair through
Store5. It alternates twelve reference/indexed measurements, checks equivalent
results, switches spaces one hundred times, verifies retired scopes and selected
accounts, and waits for SQL listener cleanup. Activation timing excludes screen
rendering. Keep timed comparisons separate from builds and profilers.

## Release browser receipt previews

```sh
npm ci --ignore-scripts --prefix scripts/ci/smoke
SHILLING_DB_NAME=shilling-performance-audit SHILLING_DEV_TOOLS=true bash build-web.sh
python3 scripts/perf/generate_receipt_images.py /tmp
LABEL=current SCREENSHOT_DIR=/tmp node scripts/perf/profile_web_receipts.cjs web-app-dist /tmp
```

The generator creates deterministic, exact-size 10 and 50 MiB synthetic PNGs using
only the Python standard library. The harness requires locally installed Chrome
and the pinned Playwright dependencies from `scripts/ci/smoke`. It serves only the
selected build on a random loopback port, uses a fresh browser context, and blocks
non-loopback requests. Production Shilling databases and browser profiles are
not opened.

The UI imports both files, checks SQL byte lengths, verifies actual decoded image
pixels from screenshots, opens and closes each preview three times, tests a Close
icon hover and a fresh empty receipt editor, checks Blob URL cleanup, and verifies
the files after reload. Optional full screenshots go to `SCREENSHOT_DIR`, which
must already exist. Failures print the current accessibility tree and save an
editor screenshot under `/tmp`.

Metrics cover onboarding/home readiness, save-to-persistence latency, rendered
preview latency, main-thread long tasks and sampled **main JS heap**. They do not
measure total file-picker time, process/GPU/Wasm/worker memory, peak memory, or
production network startup. `BASELINE_DIALOG=1` permits the original Compose
preview's known accessibility failure after dialog close and reloads between
those samples. It is only for measuring an archived baseline, not a passing
regression configuration.

## Sustained receipt transfers

Start the isolated native P2P fixture and a signaling fixture as described above.
For the hosted-control fixture, `BROWSER_PROBE=1` admits the two synthetic browser
identities. Use the actual local fixture URL; no payload bytes use this URL.

```sh
SIGNAL_URL=ws://127.0.0.1:18083/ws/signal SOAK_ROUNDS=10 node scripts/perf/bench_live_webrtc.cjs
# Run the next two commands in separate terminals for simultaneous browser peers.
SIGNAL_URL=ws://127.0.0.1:18083/ws/signal BROWSER_ID=a-browser SOAK_ROUNDS=5 node scripts/perf/bench_live_webrtc.cjs
SIGNAL_URL=ws://127.0.0.1:18083/ws/signal BROWSER_ID=b-browser SOAK_ROUNDS=5 node scripts/perf/bench_live_webrtc.cjs
# After those clients exit, use the iPhone fixture instead.
SIGNAL_URL=ws://127.0.0.1:18083/ws/signal PEER_ID=z-iphone SOAK_ROUNDS=5 node scripts/perf/bench_live_webrtc.cjs
```

Each of 1–100 rounds downloads 50 MiB, uploads and reads back 10 MiB, and waits ten
seconds. Every third round interrupts another 50 MiB download after 64 chunks,
reconnects, and verifies a full retry. Browser identities have distinct upload
receipt IDs. Every received byte is checked. The upload result times **readback
only**. Metrics and route samples include local addresses; sanitize them before
committing output.

Android `P2pPerformanceActivity` accepts `--el memorySampleMs 10000` for lower-cost
PSS sampling or `--el memorySampleMs 0` to disable it for idle CPU measurements.
The default one-second PSS probe can materially perturb CPU measurements. The
isolated iOS fixture accepts `--perf-soak`; its opt-in ten-second process sampler
writes RSS, cumulative CPU, thermal state and lifecycle events to
`Documents/p2p-soak.jsonl`. CPU percentages require a wall-time interval and are
normalized to one core; they are not battery estimates.

## Actual hosted device removal UI

Build, sign and install the isolated Release `perf-android` APK first. Then run:

```sh
ANDROID_SERIAL=<Pixel-serial> node scripts/perf/hosted_ui_fixture.cjs --launch-android
```

This generates disposable signing keys and credentials, starts a loopback hosted
stub and the production Ktor server on port 18085, establishes two synthetic
signaling sessions, installs an ADB reverse, and launches the actual Settings UI
in `HostedUiPerformanceActivity`. The normal Shilling app and its data are not
used. Open Devices and use Remove on `synthetic-peer`.

A successful run emits `remove-verified` only after the registry deletion, policy
socket close and removal notification all occur. Also verify Settings updates
from 2 / 2 to 1 / 2 and no longer lists the removed peer. Stop the fixture with
SIGTERM to terminate its server and delete its temporary token file; remove the
owned ADB reverse afterward. This exercises hosted control metadata only.

The October finance-space, browser, device and server findings are in
[the followup report](../../docs/performance-followup-2026-10-01.md).

## Isolated desktop receipt memory

Build the release web frontend with a dedicated database name, then bundle the
Tauri app with `finance.shilling.perf.desktop` as its identifier. Start
`node scripts/perf/desktop_control_fixture.cjs` and onboard that isolated app
to `http://127.0.0.1:18091`. The fixture listens on loopback and serves only
configuration, empty ICE metadata and an empty signaling peer list. It never
accepts receipt bytes. Import the PNGs from `generate_receipt_images.py` through
the desktop UI, then open and close the 50 MiB preview several times.

Sample both the Tauri host and its WebKit WebContent process at short intervals
with `ps -o pid,rss`. Identify the WebContent PID immediately after launching
the isolated app; other apps may also have WebContent processes. `vmmap -summary
<pid>` can show the resident and swapped region groups while the preview is
visible. RSS and VM regions do not give allocation stacks or a decode-only
latency. Record a settled sample after leaving the receipt editor. The October
2 [sampled curve](results/desktop-webcontent-memory-2026-10-02.json) contains
four 50 MiB preview cycles.

## Desktop memory across screens

Run `npm run test:web` for the SQL worker persistence and request-lifecycle
regressions. The desktop/web entry point uses `createDatabaseWorker` to release
SQLDelight 2.4.0's per-request callbacks; see the
[October 3 memory reduction](../../docs/desktop-memory-reduction-2026-10-03.md).

For a shorter import-only diagnostic, build with
`SHILLING_MEMORY_WORKLOAD=csv SHILLING_MEMORY_SQL_PROFILE=true` before the command
below. The optional probe reports SQL request/row counts and full-database
snapshot counts/bytes at phase boundaries. It does not retain row data. Snapshot
byte totals are cumulative copying work, not resident memory. Omit both variables
for the full screen benchmark. Run only one instance of the benchmark bundle;
multiple instances share its database and phase log and invalidate a comparison.

`SHILLING_MEMORY_WORKLOAD=idle` opens Home and waits without navigating or
importing. Restore a completed import fixture before using it to compare cold
idle against memory retained after the import. See the
[import/idle follow-up](../../docs/desktop-import-idle-memory-2026-10-03.md).

`SHILLING_MEMORY_RUNTIME_PROFILE=true` enables fixture-only phase counters for
main-thread date formatter construction and Wasm linear-memory capacity. The
probe keeps only weak references to memory objects. Linear capacity excludes
Kotlin Wasm GC objects, SQL worker memory and rendering allocations, so it is
not a substitute for native physical-footprint measurements. Leave the probe
disabled for normal builds; compare runs with the same probe setting.

`SHILLING_MEMORY_ALLOCATION_PROFILE=true` adds SQLite-worker linear capacity,
database page count/size and page-cache configuration, plus Skia CPU font and
resource-cache counters. GPU allocations and Kotlin Wasm GC objects are outside
these counters. The optional worker probe queues its read-only diagnostics with
the normal worker requests; it never exports or retains a database snapshot.

Release web/desktop packages optimize the Kotlin Wasm application with the
lockfile-pinned Binaryen build dependency (`npm ci`). The size-focused pass keeps
floating-point and trap semantics and retains function names for stack traces.
It then trims local/type/global/field debug names. The unmodified compiler
package remains under `build/tasks`; Debug builds are unchanged. This adds an
optimization step to release build time. The memory fixture uses the same path.
Set `SHILLING_MEMORY_OPTIMIZE_WASM=false` for the previous trimmed control; also
set `SHILLING_MEMORY_TRIM_NAMES=false` for the original untrimmed diagnostic.
The [retained-state follow-up](../../docs/desktop-retained-state-memory-2026-10-03.md)
records the fresh control, rejected experiments, final full run and cold-idle checks.

`bash scripts/perf/build_desktop_memory.sh` builds the separate **Shilling Memory
Perf** bundle (`finance.shilling.perf.memory`) and the `perf-web` entry point.
Start the loopback `desktop_control_fixture.cjs`, then launch that bundle. Its
first launch seeds 20 accounts, 40 categories, 1,000 schedules, 10,000 postings
and 250 receipt metadata records through the production Store5 repositories.
Restart after the seed-complete message to exclude seeding allocations from the
screen measurements. It uses the dedicated `shilling-memory-fixture-v1` database.

The fixture renders the production app shell and screens. After a 20-second
attachment window, it visits Home, weekly/monthly Plan, Schedules, Categories,
Accounts, Activity, Receipts and Settings twice, then four editors. It loads and
imports a synthetic 10,000-row CSV through `ImportViewModel`, returns to Home
and Activity, and finishes with an idle interval. CSV timing excludes the native
file picker. The import adds records to the fixture database, so subsequent
runs start with more than 10,000 postings; use a new dedicated database/bundle
or explicitly reset only the benchmark sandbox for matched comparisons.

Identify the new host, WebContent, GPU and Networking PIDs immediately after
launch, then run:

```sh
python3 scripts/perf/profile_desktop_memory.py \
  --host-pid HOST --web-pid WEB --gpu-pid GPU --network-pid NETWORK \
  --phase-log "$HOME/Library/Logs/finance.shilling.perf.memory/Shilling Memory Perf.log" \
  --output /tmp/shilling-desktop-memory.json
```

The sampler reads RSS approximately every 250 ms and takes `vmmap -summary`
physical-footprint snapshots at the fixture's READY markers. It exits on
COMPLETE. Without `--phase-log`, type phase names to take snapshots and `quit`
to finish. RSS may fall because macOS swaps or compresses memory; it is not a
substitute for footprint or an allocation-retention trace. Large virtual Wasm
reservations are not committed RAM. Other apps sharing the Mac can affect RSS,
collection and swapping, so use footprint and repeat runs before claiming gains.
