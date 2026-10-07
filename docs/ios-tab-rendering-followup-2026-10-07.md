# iOS tab rendering follow-up — October 7, 2026

The Receipt resume hitch is reproducible on the physical iPhone SE. Two new
baseline traces recorded 83.3 ms and 100.0 ms maxima, following the 100.0 ms
observation in the [mobile regression audit](mobile-regression-ios-rendering-2026-10-07.md).
The expensive work overlaps native tab transitions, hosting-view attachment and
layout. The measured resume phases perform no SQL on Main.

Both production experiments were reverted. This follow-up retains measurement
and trace-analysis improvements, with no new production performance claim.

## Method

Baseline production code is `c8a9061` (PR #54), including the scroll-preserving
resume observers. All runs use the same Release Kotlin framework and production
SwiftUI screens in the isolated `finance.shilling.perf` app. The physical SE
(second generation) runs iOS 18.7.8. Its synthetic Store5 database contains
10,001 postings, 1,000 schedules and 250 receipts. No personal app data is used.

Each fresh process visits Activity, Plan and Receipts, scrolls each list, spends
seven seconds in Settings to expire the shared replay cache, and returns. The
fixture checks row counts and scroll position, then closes the tab controller
and checks listener/controller release. There are three uninstrumented processes
per variant. Separate 45-second Animation Hitches recordings include Time
Profiler and Points of Interest for exact phase alignment. All recorded thermal
states were nominal.

The first baseline trace predates the new timing fields below. Subsequent
baseline and prototype builds use identical timing instrumentation. Only Swift
was rebuilt; Kotlin code and the data model were unchanged. Instrumented CPU
samples and uninstrumented process CPU measurements are reported separately.

## Initial callback blind spot

Previously, `maxCallbackGapMs` started with the interval between the first and
second display callbacks. Synchronous work before the first callback was absent.
The fixture now also records `firstCallbackLatencyMs`, measured from phase start
until the first callback executes. `listReadyMs` records when a populated list
with visible cells is found in the view hierarchy.

Neither value measures completed rendering. The list check polls, and callback
timing depends on when a switch occurs relative to the display cycle. Keep both
callback metrics and use Instruments for rendering claims. For example, one
fade-prototype Activity resume had a 20.1 ms maximum callback gap but waited
94.2 ms for its first callback and 97.3 ms for list readiness.

The trace analyzer now includes CPU samples inside each hitch interval and
separate categories for hosting layout, window attachment, layer layout and tab
transitions. Categories are inclusive and overlap; they must not be added into
wall-time percentages or treated as proof of causality. Hitch windows can extend
beyond their assigned phase.

In the second baseline's 100.0 ms Receipt hitch, 57 Main-thread samples included
35 in hosting layout, 38 in layer layout, 14 in window attachment and 16 in tab
transition stacks. No collection-cell creation or sizing samples appeared in
that window. This narrows the remaining work beyond data reloads and repeated
row construction, without claiming every rendering cost belongs to the app.

## Experiments

The geometry prototype added `.geometryGroup()` to the Receipt root passed to
`UIHostingController`. Apple's [geometry-group documentation](https://developer.apple.com/documentation/swiftui/view/geometrygroup())
describes a boundary for propagating parent geometry. It did not reduce this
case's measured work: Receipt CPU and list readiness were effectively unchanged,
and its rendering trace still reached 83.3 ms. It was reverted.

The fade prototype used the public
[tab-transition delegate](https://developer.apple.com/documentation/uikit/uitabbarcontrollerdelegate/tabbarcontroller(_:animationcontrollerfortransitionfrom:to:))
and a 150 ms incoming-alpha animation. It changed only the native tab transition,
with no additional view or data caches. It reduced total phase CPU by 7–13% but
did not improve initial list readiness. Two traces gave Receipt peaks of 66.7 ms
and 99.9 ms, so the lower first peak did not establish a consistent fix. The
extra custom animation code was not retained for this rendering-hitch task.

Medians across three fresh processes; CPU is total app-process CPU time across
threads, not utilization. Readiness is a hierarchy check, not first paint.

| Resume | Baseline CPU | Geometry CPU | Fade CPU | Baseline ready | Geometry ready | Fade ready |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Activity | 255.3 ms | 260.5 ms | 222.4 ms | 96.5 ms | 97.2 ms | 98.8 ms |
| Plan | 164.8 ms | 163.1 ms | 146.9 ms | 70.4 ms | 71.3 ms | 71.4 ms |
| Receipts | 162.8 ms | 162.8 ms | 151.4 ms | 88.3 ms | 89.0 ms | 88.1 ms |

Geometry changes only Receipts; its Activity and Plan values are controls and
illustrate run-to-run variation. RSS checkpoint medians for baseline / geometry /
fade were 140.7 / 144.8 / 139.7 MiB at Activity, 157.5 / 164.5 / 156.6 MiB at Plan,
and 169.2 / 172.0 / 167.5 MiB at Receipts. These checkpoints do not establish a
peak-memory or long-session memory improvement.

Actual rendering maxima from the separate synchronized traces:

| Resume | Baseline trace 1 / 2 | Geometry trace | Fade trace 1 / 2 |
| --- | ---: | ---: | ---: |
| Activity | 83.3 / 100.0 ms | 100.0 ms | 100.0 / 83.3 ms |
| Plan | 66.7 / 66.7 ms | 50.0 ms | 50.0 / 33.3 ms |
| Receipts | 83.3 / 100.0 ms | 83.3 ms | 66.7 / 99.9 ms |

Plan's fade traces were lower, but its initial readiness was unchanged. Receipt
and Activity retained comparable worst peaks. This small sample does not justify
claiming all tab rendering improved, and a shorter fade changes the transition
itself. The baseline trace 2 Receipt hitch was labeled expensive commit / delayed
frame swap; fade trace 2 was labeled expensive commit / expensive GPU. The
underlying native commit and rendering work still exceeds a 60 Hz frame budget.

## Verification and remaining scope

All 27 uninstrumented cohort resumes preserved exact row counts and scroll
offsets. All nine processes released the tab controller and returned database
listeners to zero, and all resume measurements had zero Main-thread SQL reads.
The five synchronized trace runs also completed those fixture checks. A final
baseline reinstall and resume run verifies the retained Swift instrumentation.

The analyzer preserved existing phase-level CPU/hitch counts on the first
baseline export. Its new per-hitch Main-thread count was independently checked
against the XML: 62 samples in that trace's largest Receipt interval. Python
compilation, the Release Swift builds, architecture guard and diff checks pass.
Production Swift, Kotlin and Android sources are unchanged by this follow-up;
the broader mobile regression coverage remains in PR #54.

These results narrow the next investigation to native hosting attachment,
layout and render commits. They do not resolve long-session memory, keyboard
startup stalls, every Plan section transition, or rendering on other OS/device
combinations. Avoid keeping hidden screens subscribed or adding view caches just
to hide the measured resume work: their retention cost was not evaluated here.

[Sanitized samples and aligned traces](../scripts/perf/results/ios-tab-rendering-followup-2026-10-07.json).

## Reproduction

Build the Release perf-ios target, install it on the physical test device, then:

```sh
python3 scripts/perf/profile_ios_native_ui.py --device "$DEVICE_ID" \
  --screen tab-resume --runs 3 --label baseline --output /tmp/tab-resume.json

xcrun xctrace record --template 'Animation Hitches' \
  --instrument 'Time Profiler' --instrument 'Points of Interest' \
  --device "$DEVICE_ID" --time-limit 45s --output /tmp/tab-resume.trace \
  --launch -- finance.shilling.perf --perf-ui --ui-screen tab-resume --ui-hold
```

Wait for trace finalization before exporting `OSSignpostIntervals`, `time-profile`
and `hitches-summary`, then pass those exports to
`scripts/perf/analyze_ios_transitions.py`. Raw local traces, signed experiment
apps, logs and the fade patch are under
`build/perf/ios-rendering-followup-2026-10-07/` (ignored).
