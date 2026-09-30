# Pixel populated-screen profiling — 2026-09-29

Scope: Pixel 6, Android 16, Release `finance.shilling.perf` fixture from the
private-based worktree at `73ace52` with local audit changes. The fixture uses
the production Android SQLite driver, Store5 repositories and shared Compose
History/Weekly screens. Its own database contains **10,000 synthetic postings**
across 28 days and **1,000 weekly schedules**. UIAutomator confirmed populated
content in every sample. The normal Shilling app and its data were preserved.
iPhone app UI was deliberately excluded from this pass.

## Finding and retained change

The History screen collects and projects all 10,000 rows in its Compose scope.
In the baseline, the first nonempty projection reached Compose before its first
frame, and that frame consistently took over 150 ms. `PostingRepository.watchBetween`
now applies `flowOn(Dispatchers.Default)` after its Store5 reads, joins and sort.
That moves this work from the native UI collector without changing Store5
ownership or the query result.

The fixture now retains its synthetic database across force-stopped runs and
separately records:

- **First-frame duration:** Android `FrameMetrics.TOTAL_DURATION` for the first
  frame after installing the screen content.
- **Content-ready time:** elapsed time from installing content until Compose
  observes the first nonempty Store5 projection. The callback runs after that
  state enters composition; it does not prove that a populated frame has reached
  the display. UIAutomator subsequently verifies the expected text.
- **Scrolling:** `gfxinfo` reset after content is visible, followed by ten
  350 ms upward swipes.

The matched baseline, a candidate with both projections moved to Default, and
the final retained build each had three force-stopped openings per screen. Both
versions in the matched comparison included the same content-ready observer.
Timings are milliseconds; each cell lists the three chronological samples and
their median.

| Screen and metric | Baseline | Retained final |
| --- | --- | --- |
| History first frame | 156.2 / 162.8 / 173.5 (**162.8**) | 107.2 / 110.6 / 110.6 (**110.6**) |
| History content ready | 149 / 153 / 149 (**149**) | 137 / 140 / 125 (**137**) |
| Weekly first frame | 105.6 / 113.9 / 109.4 (**109.4**) | 113.3 / 108.5 / 107.9 (**108.5**) |
| Weekly content ready | 117 / 115 / 113 (**115**) | 118 / 115 / 116 (**116**) |

The retained History first-frame duration fell **32%** at the median, while
content-ready time improved by 12 ms in these runs. This is a cold fixture-screen
measurement, not a claim that the whole normal app or its navigation became 32%
faster. In the final History samples, content reached Compose near the first
frame and before the next frame callback. The first drawn frame may still have
shown the empty state briefly.

Moving the Weekly projection to Default reduced its first-frame median to
71.1 ms, but delayed its content-ready median from 115 to **143 ms** and exposed
an earlier empty frame. That candidate was **rejected**; the final build keeps
Weekly's existing collector behavior. This screen still has a roughly 109 ms
cold first frame in the fixture and merits a separate query/composition profile
before another change.

## Scrolling and memory observations

| Final build, three ten-swipe runs | Frames | Janky frames | p95 frame time |
| --- | --- | --- | --- |
| History | 332 / 335 / 349 | 0 / 0 / 0 | 7 / 7 / 7 ms |
| Weekly | 340 / 338 / 338 | 0 / 0 / 1 | 7 / 7 / 8 ms |

The one Weekly jank event is 1 of 1,016 scrolling frames. The prior same-device
single-run screen audit also found smooth scrolling, so no scrolling speedup is
claimed here.

Steady post-open PSS medians were 85,491 KiB (baseline) and 86,134 KiB (final)
for History; 77,967 and 78,242 KiB for Weekly. These are snapshots after the
screen opened, not peak memory, and the small differences are not interpreted as
a memory trend.

## Verification and limits

- All 18 matched screen openings displayed populated synthetic data. The final
  six swipe sequences completed and left the normal Shilling app visible.
- The final Release Android fixture, web WASM debug and iOS ARM64 debug builds
  passed, as did 115 JVM tests, `just guard-architecture`, Python syntax and
  `git diff --check`. The iOS app was compiled but not profiled in this pass.
- The fixture creates a new activity and Koin scope for each cold opening, so
  this is not an in-app navigation measurement. It excludes search typing,
  images, receipt preview, live sync, peak RSS and long-running memory behavior.
  The scrolling phase excludes the cold opening frames.
- Host thermal load, device scheduling and process caches can affect small
  timing differences. Three runs distinguish the stable large first-frame gap
  from a single lucky sample; they do not establish a fleet-wide service level.

Sanitized per-run records: [Pixel screen results](../scripts/perf/results/pixel6-screen-open-2026-09-29.json).
Reproduction: [performance README](../scripts/perf/README.md#production-screen-scrolling-fixture).
