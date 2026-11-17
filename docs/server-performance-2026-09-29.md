# Server performance — 2026-09-29

Scope: the private-based worktree at `73ace52` with local audit changes, Ktor
3.6.0, Kotlin 2.4.20 and Adoptium JDK 25.0.4.1 on ARM64 macOS 27.0. These are
local synthetic control-plane measurements, not public-main or hosted-production
service levels. No household entities or receipt payloads traverse this server.

## Findings and retained fixes

### Hosted authorization

- **JWKS fetch stampede:** 500 simultaneous cold authorizations made 500 upstream
  key requests. Refresh now occurs inside a suspending mutex, so callers share one
  fetch. Expired keys fail closed. A new key ID can refresh before the existing
  600-second cache TTL; forced refreshes are limited to once per 30 seconds, and
  failed refreshes have a one-second retry backoff. Cancellation propagates.
- **Blocking upstream calls:** synchronous membership requests occupied 64 IO
  workers during the slow-upstream workload. The shared HTTP client now uses
  `sendAsync` with coroutine `await`, a three-second connection timeout and a
  five-second overall request deadline, including semaphore wait and response
  body completion. Coroutine
  [await cancels the future on cancellation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.future/await.html).
- **Unbounded async burst rejected:** the first async candidate returned 256
  errors in a warm 500-request batch. It is not retained. A shared limit of **128
  upstream requests in flight** eliminated those errors in all subsequent full
  runs. The exact cause of the initial connection failures was not isolated.
- **Failure handling:** an unavailable or malformed membership response now
  produces HTTP 503, rather than falsely reporting a missing household as 404.
  Hosted joins still require a verified token and matching membership. Genuine
  absence remains 404. Authorization decisions are not cached. Membership
  queries URL-encode the user ID and request at most one row.

The five-second budget applies to each upstream operation, not the complete
multi-step authorization. Waiting for another caller's JWKS refresh is separate
from the subsequent membership request. The concurrency limit bounds active
upstream work; it is not a global limit on incoming client requests.

### Signaling lifecycle and backpressure

- Empty household maps are removed atomically. The original hub retained **1,000
  empty maps after 1,000 disconnects**; the new hub retains zero. A race test
  covers last-peer removal concurrent with registration.
- Each peer has an ordered outgoing queue. A stalled peer no longer suspends
  another peer's registration or relay. The old implementation failed the
  250 ms healthy-peer progress check; the new implementation passes it.
- Each hub queue is bounded to **64 messages and 262,144 UTF-16 code units**,
  plus at most one message being sent. Ktor and socket buffers are additional.
  Overflow or a five-second stalled send disconnects the affected peer.
- Replacing a device connection cancels its old session. Cleanup checks session
  identity, so the old disconnect cannot remove the replacement.
- Normal per-message logs now use Debug, with the server's production threshold
  set to Info. Warnings and startup information remain enabled.

The outgoing queue addresses the documented possibility that Ktor
[WebSocket send suspends when the outgoing queue is full](https://github.com/ktorio/ktor/blob/3.6.0/ktor-shared/ktor-websockets/common/src/io/ktor/websocket/WebSocketSession.kt).
These queues carry only the existing allowed signaling messages. Application
data remains in Store5 and traverses WebRTC P2P channels.

## Controlled local results

The harness generates disposable RSA keys and synthetic users, launches a local
Supabase stub and its own server with `-Xms128m -Xmx512m`, and tears both down.
Membership responses take 25 ms by default; JWKS responses take 200 ms. HTTP
connections are established before timing to separate authorization concurrency
from the Mac TCP accept backlog. The original implementation is copied into an
isolated project with the same dependency versions for the comparison.

Primary comparison: original three-round run versus final three-round run.
Times below are **p95 milliseconds within each batch**.

| Workload | Original | Retained | Outcome |
| --- | ---: | ---: | --- |
| Cold authorization, 500 requests | 2,182.58 | 731.30 | All 200; JWKS fetches 500 → 1 |
| Slow membership, 100 requests, 1,500 ms upstream | 3,034.89 | 1,586.78 | All 200; removes the second wave of blocked IO work |
| 7,000 ms membership, 10 requests | 7,015.28 | 5,040.49 | Original eventually 200; retained returns deadline 503 |
| Upstream 503, 10 requests | 75.55 | 41.63 | Corrected response: 404 → 503 |
| Rotated signing key, 100 requests | 8.35 | 365.57 | Corrected result: 100 rejected → 100 authorized, one refresh |

The rotation row is a correctness improvement; the original fast rejection is
not useful throughput. Two earlier bounded runs also completed cold authorization
at 694.41 and 608.58 ms p95, and slow membership at 1,563.26 and 1,544.59 ms p95.

| Thread snapshot during slow membership | Original | Retained |
| --- | ---: | ---: |
| JVM threads | 229 | 136 |
| DefaultDispatcher workers | 75 | 2 |
| Stacks blocked in `HttpClientImpl.send` | 64 | 0 |

This is one snapshot per run, not peak thread or memory usage. The retained JDK
HTTP client still has its own executor threads; removing blocked coroutine
workers does not mean all server HTTP work uses only two threads.

### Warm requests and joins: preserve the variation

Each cell lists chronological p95 values for all three rounds. These rounds
share a JVM and are not three independent server restarts.

| Workload | Original rounds (ms) | Retained final rounds (ms) |
| --- | --- | --- |
| Warm membership, 10 | 32.18 / 34.87 / 30.42 | 31.32 / 31.42 / 32.71 |
| Warm membership, 100 | 115.15 / 70.32 / 61.69 | 118.74 / 88.34 / 37.52 |
| Warm membership, 500 | 270.64 / 334.06 / 238.41 | 217.80 / 180.43 / 195.73 |
| Join + echo, 1 household / 5 peers | 38.70 / 32.50 / 58.79 | 38.25 / 66.13 / 36.05 |
| Join + echo, 10 households / 50 peers | 104.43 / 60.29 / 74.25 | 82.64 / 184.58 / 73.93 |
| Join + echo, 100 households / 500 peers | 1,118.86 / 1,011.60 / 982.18 | 859.35 / 936.14 / 909.90 |

Every warm metadata request returned 200, and every timed join completed a
directed Offer/Answer echo. The final warm-500 median of batch p95 values is
195.73 ms versus 270.64 ms originally. Warm-100 and smaller join cohorts show
mixed results. Do not generalize these numbers into a universal latency gain.

An earlier single-round bounded run was slower: warm-500 p95 563.08 ms and
500-peer joins 1,761.23 ms. Repeating the original and retained implementations
was necessary to assess this. The first retained three-round run recorded
warm-500 p95 316.03 / 158.99 / 141.86 ms and join-500 p95
1,010.00 / 881.76 / 676.57 ms, with a 473.19 ms outlier in the 50-peer cohort.
All full runs, including the rejected unbounded candidate, are retained in the
results file. JVM startup/JIT, host load and client work are not independently
attributed by this harness.

Join timing includes WebSocket opening, its local queue of at most 25 simultaneous
upgrades, client RSA token signing, server authorization, Join and Offer/Answer
echo. An authenticated anchor and probe establish each household before timing.
It is not a measurement of pure hub relay latency or WebRTC receipt throughput.

## Validation

- Final strict load run: expected HTTP statuses, one cold/rotation JWKS fetch,
  zero blocking HTTP stacks, and all signaling echoes passed.
- 30 authenticated reconnects across ten households: all replacement connections
  delivered their first echo and continued relaying after the old connection
  closed. Reconnect p95 was 44.09 ms; this excludes the second validation echo.
- 115 JVM tests passed: 89 shared, 26 server; no failures, skips or JUnit
  discovery warnings. New coverage includes cold/expired/failed JWKS coalescing,
  rotation, invalid authentication claims/signatures, cancellation, response-body
  deadlines, membership failure/absence, queue count/content limits, stalled
  sends, empty-map cleanup and disconnect/register races.
- The two original hub regressions were reproduced against the old server
  before applying their fixes.
- `just guard-architecture`, JavaScript syntax and `git diff --check` passed.

These tests do not cover real Supabase/TLS/network behavior, deployment capacity,
large-household ICE storms, peak heap, or long-duration socket/heap retention.
The concurrency and queue limits are conservative starting values, not tuned
production capacity claims. Mobile UI, phone-to-phone full sync and web/desktop
release profiling remain separate audit work.

Sanitized records: [server results](../scripts/perf/results/server-2026-09-29.json).
Commands: [performance README](../scripts/perf/README.md#hosted-server-and-signaling).
Raw server logs remain local.
