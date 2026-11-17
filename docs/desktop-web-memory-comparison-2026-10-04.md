# Desktop versus standalone web memory — October 4, 2026

This research starts from public `main` at `dedeb19`, after
[PR #46](https://github.com/j1philli/shilling/pull/46). It measures the unchanged
release frontend outside Tauri and compares the results with published reports
for other finance applications. It does not introduce an application optimization.

**Substantial memory use exists without Tauri.** The ordinary production web
build with 20,000 postings settled at **494.5 MiB for its isolated Chrome session**,
including **203.7 MiB in Shilling's renderer**. Even the empty app used
**464.1 MiB total / 175.7 MiB renderer**. The full navigation/import fixture
settled at **714.1 MiB total / 231.4 MiB renderer** at DPR 1.

The previous desktop result was **577.4 MiB after the same fixture workload**,
with only **63.5 MiB in the native host**. The evidence points toward the shared
frontend/runtime/rendering stack as the larger optimization target. It does not
isolate an exact Tauri wrapper surcharge or establish that WebKit is intrinsically
less efficient than Chrome. Shilling web was close to a fresh Actual Budget browser session with 20,464
transaction rows (**518.5 MiB**). Leaner native-app reports suggest room to improve,
but the evidence is too heterogeneous to serve as a controlled industry ranking.

## Measurement scope

The machine is a Mac15,6 with 36 GiB RAM, running macOS 27.0 (26A428).
Chrome is 154.0.8037.95, controlled by the repository's Playwright 1.63.0.
The browser is headed, sandboxed, uses an isolated fresh profile, and has a
1200 × 900 CSS-pixel viewport. The primary new runs use device-pixel ratio 1.
A completed earlier full run used DPR 2 and is reported separately.
The previous desktop fixture also configured a 1200 × 900 window, but its
actual device-pixel ratio was not recorded; this is not an exact DPI-controlled
Tauri-versus-Chrome experiment. No forced
garbage collection was used. No application compilation overlapped measurements.
Other user applications remained running on this shared machine.

The full workload restores the same synthetic, Store5-created 10,000-posting
snapshot used by the previous desktop audit: 20 accounts, 40 categories,
1,000 schedules and 250 metadata-only receipts. It visits the nine main
destinations twice, four editors, reviews and imports 10,000 additional CSV rows,
visits Home/Activity/Home, and idles for 45 seconds. New imports use October 4
as the fixture's current date; the previous desktop run used October 3.
The compiled release fixture Wasm is identical. Receipt images and live P2P
transfers are not part of this workload.

The production web tests load the ordinary `web-app` release bundle, not the
navigation fixture. The populated case restores the exact completed
20,000-posting snapshot from the previous desktop audit. The empty case creates
a fresh application database with the same local setup. Both wait for the
application's Activity navigation button, then sample Home after 30 seconds and
again 60 seconds later. The test restores snapshots before a separate fresh
browser process starts; seeding allocations are not included in measured peaks.
Client totals exclude the static and signaling fixture servers.
These are local synthetic test profiles; production Shilling data is untouched.
A loopback static server serves code only. The separate signaling fixture
exchanges control-plane messages and no entity data.

The metric is macOS **physical footprint in MiB**, not RSS, downloaded Wasm size,
virtual reservations, or JavaScript heap alone. The first DPR 2 run used `vmmap`;
subsequent probes use the kernel's `proc_pid_rusage` physical-footprint counters.
A paired check against the same live renderer gave 226.5958 MiB from the kernel
and 226.6 MiB from `vmmap`, with matching lifetime peaks. Chrome's custom malloc
zone causes `vmmap` allocation-inspection warnings; the independent counter
check validates the headline footprint, not detailed malloc classification.
See [kernel validation](../scripts/perf/results/browser-kernel-validation-2026-10-04.json).

The app renderer PID is identified by a brief CDP timeline marker. Chrome spawns
a separate tracing service for this, which is excluded from headline browser
totals and retained separately in raw results. All remaining browser-owned
processes are counted, including the browser UI renderer, spare renderer, GPU,
networking and storage. That is the cost of an isolated browser session, **not**
the incremental cost of adding one Shilling tab to an already-running browser.
GPU/process memory is shared across tabs in normal use. Process sums are
sequential approximate phase totals, not the unique system-wide RAM that closing
the app would reclaim; shared graphics ownership can complicate attribution.
Individual process lifetime peaks must
never be added to produce a supposed simultaneous whole-app peak.

Each configuration has one complete observation, not a statistical repeat set.
The two DPR runs differ in display density and sampling implementation, so their
difference is not a controlled estimate of DPI's memory cost. Startup blank-page
samples can include transient browser initialization and are not subtracted to
manufacture an exact per-tab cost.

## Measured Shilling results

All cells are **MiB physical footprint at the settled checkpoint**. Desktop
values come from the previous audit; browser values were collected in this pass.
“Other” includes the browser UI/spare renderer and services in Chrome, and the
networking process in the four-process desktop measurement.

| Case | App renderer / WebContent | GPU | Native/browser host | Other | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| Desktop cold Home, 20,000 postings (prior fixture) | 352.3 | 19.3 | 63.8 | 9.1 | 444.5 |
| Web production, empty database | 175.7 | 104.7 | 65.5 | 118.2 | 464.1 |
| Web production, 20,000 postings | 203.7 | 107.6 | 65.1 | 118.2 | 494.5 |
| Desktop after full import workload (prior fixture) | 478.7 | 26.4 | 63.5 | 8.8 | 577.4 |
| Web after full import workload, DPR 1 | 231.4 | 293.0 | 66.9 | 122.7 | 714.1 |
| Web after full import workload, DPR 2 | 234.3 | 243.0 | 64.6 | 123.7 | 665.6 |

The populated production renderer is only **28.0 MiB above the empty renderer**
in this pair; whole-browser totals differ by **30.4 MiB**. That points to a large
baseline cost already present without transaction records. It does not mean the
remaining footprint is unnecessary or that all data uses exactly 28 MiB.

The complete browser import runs reached app-renderer lifetime peaks of
**459.0 MiB (DPR 1)** and **464.0 MiB (DPR 2)**, versus **626.8 MiB WebContent** in
the previous desktop run. Their import-complete whole-browser phase totals were
881.4 and 820.6 MiB, respectively; these are checkpoint samples, not a measured
simultaneous whole-browser peak. Both settled substantially below that checkpoint.
Every populated browser run passed SQLite integrity checking and contained
20,000 postings, including 10,000 imported rows with 10,000 matching change-log
versions. The empty production run passed integrity checking with zero postings.

Cold production tests and the full fixture have different entry points and UI
padding. Production probes also wait 15 seconds on a blank browser before
navigation; full runs used earlier probe versions without that explicit wait.
These results establish substantial standalone web memory use, but subtracting
the cold production result from a full fixture result does not isolate a causal
“memory retained by import” quantity. Compilation caches, GPU resources and
startup timing can also differ.


## Other finance applications

Published client-memory benchmarks are sparse. These are primary, first-hand
reports or a vendor support estimate, not a matched benchmark suite. Their units
are preserved as reported (MB); they are not silently relabeled as our MiB metric.

| Application | Reported memory | Workload and evidence | Limits |
| --- | ---: | --- | --- |
| GnuCash 4.13 | 160–170 MB loaded; approximately 280 MB while loading | A Windows 11 user measured a 20-year, roughly 60 MB data file in Task Manager, August 2025. [Official project mailing list](https://lists.gnucash.org/pipermail/gnucash-user/2025-August/117398.html) | Older version, different OS and data; Task Manager accounting is not our macOS physical footprint. |
| Alzex Finance | 100–300 MB described as normal even with large databases | Support reply on the [vendor's community](https://community.alzex.com/d/1601-alzex-running-slowly-on-macos-tahoe). | Estimate without a reproducible dataset or version; explicitly describes macOS releases before Tahoe. The user's 3.7 GB Tahoe regression is not a normal-use baseline. |
| Quicken Classic Business & Personal beta 9.1.0 | Approximately 500 MB at sync start | June 2026 macOS 26.5.1 report with 5,502 attachment records. [First-hand bug report](https://community.quicken.com/discussion/7974432/cloud-reset-causes-memory-leak-out-of-memory-crash-on-mac) | A single baseline before a cloud-reset leak, not steady-idle benchmarking. The later runaway usage is excluded from normal comparisons. |

### Actual Budget measured on the same Mac

The public [Actual demo](https://demo.actualbudget.org/) reported client version
**26.10.0**, with no server. In an isolated Chrome profile at the same 1200 × 900
viewport and DPR 1, I generated its demo, imported 20,000 synthetic CSV rows with
merging disabled, closed the browser, and reopened the saved local budget in a
fresh browser process. The full Budget grid was visibly rendered before the
cold measurements. Samples 30 and 90 seconds after confirming readiness were
**518.5 and 518.5 MiB for the entire browser**, respectively. The final sample
was approximately 181 seconds after measurement started, versus approximately
106 seconds for Shilling's cold production sample.

A subsequent export passed SQLite integrity checking: **20,464 active transaction
rows, including all 20,000 imported rows**, eight accounts, 16 categories and four
schedules. Shilling's 20,000-posting fixture instead has 20 accounts, 40 categories,
1,000 schedules and 250 receipt metadata records. Actual also has a different
schema, budget UI and process distribution. This is useful same-machine context,
not a feature-matched race or proof that one app is more efficient.

| Cold client on this Mac | Whole isolated browser, MiB |
| --- | ---: |
| Shilling production web, 20,000 postings | 494.5 |
| Actual 26.10.0 demo plus import, 20,464 transaction rows | 518.5 |

**Shilling web is in the same broad range as this loaded Actual browser client.**
It is not an obvious browser-client outlier based on these observations, although
both are considerably larger than the lean native-app reports above. Comparing
a browser session with a native executable still involves different process and
feature boundaries.

Actual's default demo, measured 30 seconds after generation without restarting,
used 616.2 MiB. Its later CSV-review checkpoint was 1,176.8 MiB. A checkpoint
45 seconds after selecting Budget following import reached 2,033.6 MiB, but the
main Budget pane was still blank: **that sample is excluded from normal settled
idle comparisons**. Reopening rendered correctly and produced the stable 518.5
MiB result. The stress case imports twice as many rows as Shilling's import step;
it does not establish a comparative leak, normal usage level, or memory ratio.
The failed early tracing attempts are also not used as app measurements.

[Cold Actual measurements and exported-database checks](../scripts/perf/results/browser-memory-actual-cold-2026-10-04.json)
and [demo/import diagnostic samples with the exclusion recorded](../scripts/perf/results/browser-memory-actual-import-2026-10-04.json)
retain the evidence. No individual Actual renderer is labeled as the whole app:
its owned renderer processes are all included in the browser total.

These reports support continuing to reduce Shilling's memory. They do not
establish an industry median, a universal RAM budget, or a defensible exact
“times worse” ratio. Actual's hosting/server memory requirements are also not
client-browser memory measurements. Its [official installation documentation](https://actualbudget.org/docs/install/)
distinguishes local browser use from the optional server.

## What this says about Tauri and the frontend

[Tauri's process model](https://v2.tauri.app/concept/process-model/) separates the
Rust host from the OS webview; macOS uses WKWebView. A small native executable
does not imply a small running frontend. The previous desktop result put
478.7 of 577.4 MiB in WebContent, approximately **83%** of the measured total.
That process includes the application, database worker, engine and rendering
work; it is not all “Tauri overhead” and not all live application objects.

The browser full runs settle at approximately **231–234 MiB in the app renderer**,
but another **243–293 MiB in Chrome's GPU process**. Compare that with
**478.7 MiB WebContent + 26.4 MiB GPU** on the desktop. The coarse renderer-plus-GPU
sums are **477.3–524.4 MiB in Chrome versus 505.1 MiB on desktop**. Chrome's GPU
also serves its browser UI, so this is not an exact app-only allocation boundary.
It does show why comparing 231 MiB with 479 MiB alone would be misleading.
Different engines account for rendering work in different processes.

The native host was **63.5 MiB**, approximately 11% of the desktop total. The
complete Chrome sessions were larger than the previous complete desktop session,
partly because they also include browser UI, spare rendering and storage/network
services. These observations do **not** support replacing Tauri as an immediate
memory fix. They also do not quantify the exact overhead of Tauri: that would
require a working same-WebKit, same-workload control.

The next profiling work should separate (1) main Kotlin/Compose/WasmGC objects
and compiled engine code, (2) the SQL worker and its database/snapshot buffers,
and (3) canvas/Skia/GPU resources. Compare loaded versus empty production runs,
then repeat on both engines with the same visibility, viewport and data. Preserve
the existing Store5 boundary and WebRTC-only device transport. A high settled
footprint is worth reducing, but these short runs alone do not demonstrate an
unbounded leak.

An independent historical experiment also cautions against assuming that a
wrapper alone determines memory. A [2022 Tauri issue](https://github.com/tauri-apps/tauri/issues/5889)
reported the Postman web app at 421 MB in Tauri, 471 MB in Safari, 337 MB in Electron
and 381 MB in Chrome on macOS 12.6.1. Those are old versions, different software,
and disputed/shared-memory accounting, so they are context rather than a current
ranking. Tauri's [official benchmark page](https://tauri-apps.github.io/benchmark_results/)
currently measures memory only on Linux with `time -v`, using small benchmark
applications. Its numbers cannot establish a budget for this loaded macOS app.

[Chrome's memory guidance](https://developer.chrome.com/docs/devtools/memory-problems)
distinguishes OS footprint from the JS heap. In particular, the earlier
Shilling runtime probe's approximately 18 MiB main linear-memory capacity does
not measure Kotlin WasmGC objects, the SQL worker, engine code or GPU allocations.
[V8's WasmGC documentation](https://v8.dev/blog/wasm-gc-porting) describes managed
objects separately from linear memory. WebKit's [March 2026 engineering report](https://webkit.org/blog/17899/introducing-the-jetstream-3-benchmark-suite/)
describes changes to WasmGC object layout and collection for Kotlin and other
languages. This makes engine-specific profiling worthwhile; its benchmark speed
improvements are **not** evidence of an equal RAM reduction in Shilling.

## Limits and excluded attempts

Safari's WebDriver session could not start because Allow Remote Automation is
disabled. The setting was left unchanged. A minimal Cocoa/WKWebView host loaded
the unchanged frontend, but reported `document.visibilityState = hidden`, never
created the Compose canvas, and never reached a workload checkpoint. Matching
Tauri's inactive scheduling policy did not resolve it. No footprint from that
incomplete run is used as evidence about WebKit or Tauri overhead. A same-version
Safari/bare-WKWebView comparison therefore remains unmeasured.

One DPR 1 Chrome attempt used slow `vmmap` process inspection and was stopped
before useful workload measurements; the complete replacement uses the validated
kernel counter. An earlier asset-server 404 setup attempt is also excluded.
These failures are not treated as low-memory results.

## Reproduction and artifacts

- Full browser fixture: [DPR 1](../scripts/perf/results/browser-memory-full-dpr1-2026-10-04.json),
  [DPR 2](../scripts/perf/results/browser-memory-full-dpr2-2026-10-04.json).
- Production frontend: [20,000 postings](../scripts/perf/results/browser-memory-production-2026-10-04.json),
  [empty](../scripts/perf/results/browser-memory-empty-2026-10-04.json).
- [Source and asset fingerprints](../scripts/perf/results/browser-memory-source-2026-10-04.json),
  [research source notes](../scripts/perf/results/browser-memory-research-sources-2026-10-04.json).
- [Browser profiler](../scripts/perf/profile_web_memory.cjs) and
  [macOS kernel counter](../scripts/perf/process_footprint.py).
- Prior [desktop full run](../scripts/perf/results/desktop-memory-retention-final-2026-10-03.json),
  [desktop cold run](../scripts/perf/results/desktop-memory-retention-cold-final-2026-10-03.json)
  and [its audit](desktop-retained-state-memory-2026-10-03.md).

Both full browser imports and both production profiles passed database integrity
checks. The production renderer was visibly functional in the final screenshots,
with the expected populated or empty Home screen. The kernel probe was checked
against `vmmap`, and Node/Python syntax plus the architecture guard passed.
Owned benchmark browser processes were closed after measurement.

The probe requires a new label/profile for every run and refuses an existing
profile. From the repository root, after installing `scripts/ci/smoke` dependencies:

```sh
node scripts/perf/profile_web_memory.cjs /tmp/isolated-memory-run production chrome-production 1
node scripts/perf/profile_web_memory.cjs /tmp/isolated-memory-run empty chrome-empty 1
node scripts/perf/profile_web_memory.cjs /tmp/isolated-memory-run full chrome-full 1
```

Prepare that directory with `fixture/` and `production/` copies of the release
asset directories, a minimal `bootstrap.html` in each, and synthetic
`seed.sqlite`, `completed.sqlite` and corresponding `*-settings.json` snapshots.
The fixture HTML must select the `all` workload. Serve only the asset directories
at `http://127.0.0.1:18193`, with the same COOP/COEP headers as the desktop, and run
`scripts/perf/desktop_control_fixture.cjs` on port 18091. Snapshot restoration is
a benchmark setup operation; application writes during the workload still use
Store5. Do not substitute a personal database or expose the fixture server.
