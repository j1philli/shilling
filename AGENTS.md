# Architecture Invariants

These rules are mandatory for any change in this repo.

1. User data traversal must use WebRTC P2P data channels only.
2. The signaling server is control-plane only.
3. Store5 is the required data-management boundary for application data.

## Transport Rules

- Allowed signaling traffic: `Join`, `PeerList`, `Offer`, `Answer`, `IceCandidate`.
- Disallowed for user data: WebSocket relay, REST sync fallback, server catch-up, server push, or any non-WebRTC transport.
- If a platform cannot support WebRTC P2P for user data, disable sync on that platform. Do not add a fallback transport.

## Data Rules

- Keep entity data flows inside the Store5/repository layer under `app/shared/.../data/store/`.
- Do not add new server-backed entity fetchers or push paths for accounts, categories, schedules, postings, or receipts.
- Do not bypass Store5 with ad hoc sync-specific persistence paths.

## Regression Check

Run `just guard-architecture` before finishing changes that touch sync, server, or data flow code.
