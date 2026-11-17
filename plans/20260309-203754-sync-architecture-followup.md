> Backup-first scope superseded by [durable cloud peer](20260929-120000-cloud-peer.md), approved 2026-09-29.

# Sync Architecture Follow-Up

Date: 2026-03-11

Backup design and implementation updated: 2026-09-28.

## Status

This document is now a forward-looking follow-up, not a greenfield plan.

Phase 1 is shipped in the current branch:

- hosted startup order is `config -> auth -> hosted household metadata -> signaling`
- `/api/household` exists and returns the authoritative hosted `householdId`
- hosted signaling join validation checks server-owned household membership
- Android, iOS, web, and desktop all use the same hosted bootstrap contract
- modern Supabase projects use JWKS-backed token verification on the server
- `just server` loads repo-root `.env` and fails fast for incomplete hosted config

## Current Baseline

Present today:

- local app data uses Store5 + local persistence
- peer sync uses WebRTC P2P and the server remains control-plane only
- `AuthService`, `SupabaseAuthService`, and `NoOpAuthService` exist
- `AuthBootstrap` caches `/api/config`, restores or creates hosted auth, fetches hosted household metadata, and gates signaling startup
- `/api/config`, `/api/household`, `/api/ice-servers`, and `/ws/signal` are implemented
- hosted household metadata comes from Supabase `public.user_profiles.household_id`
- Supabase access tokens are attached to hosted metadata and signaling requests when available

Still incomplete:

- tier/device metadata endpoints and server-backed account operations
- production backup capacity benchmarking, automatic worker migration/failover, and service-key rotation
- abuse controls beyond current hosted join authorization and authenticated TURN gating
- Store5 boundary cleanup in sync receive/replay and projection code

## Locked Decisions

- Device user data traverses WebRTC P2P data channels only. Hosted workers may persist encrypted backup chunks to Supabase Postgres over TLS through Store5.
- The signaling server stays control-plane only:
  - signaling
  - config
  - hosted metadata endpoints
  - TURN credential generation
- Store5 under `shared/.../data/store/` is the only application-data boundary.
- Hosted `householdId` is a server-owned sync namespace from metadata such as `user_profiles.household_id`.
- Hosted tier/device/backup/TURN policy stays server-authoritative.
- Metadata stays server-mediated for now:
  - `/api/tier`
  - `/api/devices`
  - `/api/devices/register`
  - `/api/backup/toggle`
- Backup runs as a separate JVM service in the same repository, sharing libraries with the app/server. Each worker serves many households; never deploy one JVM or container per user or household.
- Start with one backup worker, optionally on the same host as signaling, and scale the worker pool independently based on measured load.
- Clients connect directly to their assigned backup worker over WebRTC for backup and restore. Signaling carries only the existing control messages, never backup payloads.
- Backup encryption is an explicit household/account policy. End-to-end encryption is the default: clients encrypt before transfer and workers never receive the recovery key. Trusted cloud requires explicit consent to service access, including consent on each uploading device; workers encrypt at rest using a separately managed service key. Each backup retains its original mode when the preference changes. WebRTC transport encryption alone is insufficient for at-rest protection.
- Recovery keys sync over encrypted WebRTC data channels between explicitly authorized user devices only. Backup workers are storage peers, never key recipients; household membership or an encrypted connection alone is not sufficient authorization to receive keys.
- Cloud backup requires TURN enabled. Enabling cloud backup automatically enables TURN and locks its toggle on until cloud backup is disabled. TURN remains an ICE relay option for encrypted WebRTC traffic, not a separate application-data transport or a guarantee of key delivery.
- Updated 2026-09-29 per user instruction: hosted workers persist encrypted chunks and backup manifests in Supabase Postgres; self-hosted workers default to SQLite. A shared bounded connection pool serves all households. Device transfers remain WebRTC-only, and the signaling server remains control-plane only.
- Self-hosted `authMode=none` is an intentional trusted-operator mode. In self-hosted mode, `householdId` is routing scope, not authentication.

## Cross-Cutting Rules

- Any phase touching sync, server, or data flow runs `just guard-architecture`.
- Each phase updates docs to match the shipped behavior from that phase.
- Remove stale docs instead of leaving design history as active guidance.
- No phase may add new application-data access outside Store5.

## Phase 1: Hosted Identity And Startup

Status: complete and shipped in the current branch.

Shipped behavior:

- hosted clients fetch `/api/config`, construct the right auth service, authenticate, fetch `/api/household`, and only then open signaling
- hosted household identity no longer falls back to Supabase `userId`
- hosted signaling joins are rejected when the requested household does not match server metadata
- Android now follows the same config-driven auth selection as the other clients

Regression tests for this phase:

- fresh hosted client creates or restores auth session before signaling
- hosted client receives server-owned `householdId`
- signaling join rejects mismatched hosted households
- `just guard-architecture`

## Phase 2: Auth UI, Tier Enforcement, And Account Management

Goal: make auth usable end-to-end with UI, feature gating, and session handling.

Status: complete.

Shipped behavior:

- hosted settings now expose a real account surface instead of relying on the old developer account controls
- hosted sign-out and hosted auth loss return the app shell to first-launch onboarding, while explicit login and guest entry still use the dedicated auth gate
- anonymous hosted sessions can still start automatically, with in-app upgrade or sign-in available from settings
- hosted settings expose account state, tier, household ID, and sign-out
- `FeatureGate` is now enforced in hosted UI through plan-aware placeholder states for upcoming hosted features
- self-hosted mode bypasses hosted auth gating and keeps server/household overrides under developer tools

Regression tests for this phase:

- Hosted web/iOS/Android reach a coherent auth surface instead of depending on the developer screen.
- Email sign-up creates account, anonymous-to-email upgrade preserves data and household.
- Sign-out clears session and returns to first-launch onboarding.
- Expired hosted sessions return to first-launch onboarding instead of failing silently.
- Gated hosted features show intentional included-versus-locked placeholder states.

## Phase 3: Shared Backup Workers And Encrypted Restore

Status: initial implementation present in this checkout. See `docs/server/backups.mdx` for deployment and current limits.

Implemented:

- independent multi-household JVM worker, dedicated WebRTC backup/key-sync signaling rooms, and household-scoped worker credentials
- default end-to-end encryption plus explicitly consented trusted-cloud encryption at rest, with per-backup mode retention and local downgrade protection
- protected client key storage, explicit device pairing, durable key acknowledgements, and saved-key restore
- server-authoritative backup/TURN settings, automatic backups while the app is open, manual backup, list, restore, and deletion
- bounded chunk requests, idempotent retries, atomic completion, retention, worker restart persistence, and stale-authorization shutdown

Remaining production hardening:

- benchmark realistic concurrent transfers before raising the default worker limits
- replace the single-writer policy file when scaling the signaling control plane horizontally
- automatic cross-host worker migration/failover and capacity-aware scheduling (initial assignments balance household counts and remain stable)
- streaming client snapshots beyond the initial 32 MiB serialized limit, resume after client process restart, and service-key rotation

Goal: ship a separate, multi-household JVM backup service with client-encrypted backups by default, an explicit trusted-cloud option, and independent worker capacity.

### Service And Assignment

- Create the `backup-peer/` JVM module with an independent process entry point, health/readiness checks, and shared protocol libraries.
- Initially deploy one worker, optionally alongside signaling on the same host. Keep separate processes so backup memory pressure, storage latency, and WebRTC failures do not share the signaling JVM.
- Serve many households per worker with isolated logical peer identities, sessions, and storage namespaces. Do not allocate a process/container or permanently active WebRTC connection per household.
- Assign work by server-owned `household_id`, not auth user ID. Add worker authentication and household-scoped authorization; a worker credential must not allow joining arbitrary unassigned households.
- Establish WebRTC transfers on demand and release idle connections. Clients transfer directly to the assigned worker; adding workers must not add a payload hop through signaling.
- Define assignment ownership, expiry/reassignment, and graceful draining so worker restarts or scaling do not strand backups. A replacement worker must be able to locate committed backups; do not assume in-memory session state is durable.
- Coordinate the minimum enablement/assignment metadata with Phase 5. These APIs carry policy and routing metadata only.

### Encryption, Persistence, And Recovery

- Define a versioned, authenticated-encryption format for backup snapshots/chunks, including receipts and sensitive manifests. Bind encrypted objects to the intended household, backup version, and chunk identity; use a reviewed cryptographic library and safe nonce handling.
- Generate and manage end-to-end backup keys on clients. Keep client recovery keys out of worker memory, storage, logs, and telemetry. Trusted-cloud service keys are operator-managed secrets, separate from stored backup files. Document the minimal visible metadata needed for routing, quotas, and object lookup; ciphertext sizes and access timing are not hidden by this design.
- Provide recovery-key creation, confirmation, and restore on a new device after all existing devices are lost. Account sign-in alone must not decrypt a backup. Explain that losing all authorized devices and the recovery key makes the backup unrecoverable.
- After explicit device approval, automatically sync recovery-key material from an existing authorized device over its encrypted WebRTC data channel. Authenticate the recipient's device identity through trusted pairing; do not trust signaling membership alone or broadcast keys to all household peers. Persist received keys in platform-appropriate protected client storage behind the repository boundary.
- Track key versions and acknowledge receipt only after successful durable storage. Show per-device pending/synced/failed states, retry on reconnect, and never infer successful key sync from TURN being enabled or a data channel opening. Offline devices remain pending until an authorized source device and recipient can connect.
- Keep saved recovery-key confirmation in onboarding: device-to-device key sync improves setup and recovery when a trusted device survives, but cannot recover keys after every device is lost. Keys must never be routed through backup workers or placed in server logs, telemetry, or signaling messages.
- Specify key versioning/rotation and the effect of device revocation on future backups before freezing the format. Revoked devices must not receive new key material; revocation cannot erase keys already received.
- Keep snapshot creation, encrypted-object persistence, and restore/import within the Store5/repository boundary under `app/shared/.../data/store/`. Do not add server-backed account/category/schedule/posting/receipt fetchers or bypass Store5 with backup-specific SQL paths.
- Use Supabase Postgres for hosted encrypted backup storage and per-household SQLite files by default when self-hosted. Database access belongs to the worker Store5 repository; no device or signaling payload path is added. Trusted-cloud uploads are encrypted by the worker before any persistent write; end-to-end chunks remain opaque.
- Define resumable/idempotent uploads, integrity verification, and an atomic completion marker so incomplete uploads cannot replace the last restorable backup. Define retention, quotas, and cleanup of abandoned uploads.
- Restore end-to-end ciphertext over WebRTC and decrypt it on the client; decrypt trusted-cloud chunks in the worker and return them over WebRTC. Import through Store5 only after validating the entire snapshot. Workers must never join ordinary entity-sync rooms or receive client recovery keys.
- Fail fast for missing storage credentials or hosted worker credentials. Unsupported WebRTC platforms have backup/sync disabled, with no REST or WebSocket payload fallback.

### Capacity And Validation

- Bound concurrent transfers, per-transfer buffers, message/chunk sizes, queued work, and per-household usage. Apply backpressure rather than buffering entire backups in memory.
- Measure active connections, heap/native memory, CPU, transfer throughput, storage latency, and signaling responsiveness under concurrent uploads/restores. Establish worker capacity through load tests rather than promising a household count per JVM.
- Test multiple households in one worker and multiple workers, including cross-household access rejection, disconnect/resume, process restart, reassignment, and draining.
- Test encrypted backup/restore in both supported storage modes, including receipts and a fresh device using a recovery key. Wrong keys, modified chunks, and incomplete backups must fail without corrupting local data or replacing the last committed backup.
- Test recovery-key sync to an approved new device over direct and TURN-relayed WebRTC connections, including forced relay connectivity, expired TURN credentials, interrupted transfer, durable receipt acknowledgement, offline retry, and key rotation. Unapproved/revoked devices and backup workers must never receive keys.
- Test that a TURN outage or unavailable source device leaves key sync pending/failed with actionable status, never falsely reported as complete. Independently test all-devices-lost recovery using the saved recovery key.
- Verify stored objects, logs, and telemetry contain no plaintext budget contents or recovery keys; test that a backup peer cannot request ordinary entity sync. Test explicit trusted-cloud consent, at-rest encryption, local downgrade protection, and retention of older backups' modes.
- Verify invalid startup combinations fail immediately and a failed/overloaded backup worker leaves signaling available.
- Run `just guard-architecture` for implementation changes and update hosted/self-hosted docs to describe only shipped behavior.

## Phase 4: Enforce Store5 Everywhere

Goal: make Store5 the only application-data path everywhere.

Current status:

- Store5 already handles the primary app CRUD path.
- The remaining problem is that sync receive/replay, projections, and sync metadata still bypass Store5 in a few places.

Build:

- Remove direct entity reads and writes from sync receive and replay paths.
- Move projection/use-case reads behind Store5 or store-layer APIs.
- Remove direct application-data access outside `shared/.../data/store/`.
- Expand `scripts/enforce-architecture.sh` to catch the remaining bypass patterns.

Test:

- Account/category/schedule/posting/receipt create-update-delete still sync correctly between two devices.
- Reconnect/replay still works after removing direct SQL paths.
- Budget and history-style projections still update correctly after local and remote changes.
- Code audit passes with no application-data access outside Store5.

## Phase 5: Metadata Boundary And Self-Hosted Mode

Goal: keep hosted metadata on the server and make self-hosted behavior explicit.

Current status:

- `/api/config`, `/api/household`, hosted auth, and hosted signaling already exist.
- The missing work is end-to-end completion for tier/devices/backup flows and a cleaner hosted vs self-hosted split in the client.

Build:

- Keep hosted metadata on the server:
  - `/api/tier`
  - `/api/devices`
  - `/api/devices/register`
  - `/api/backup/toggle`
- Make linked-devices and backup-toggle flows use those server endpoints end to end.
- Connect backup enablement to household assignments in the shared worker pool, with scoped worker authorization and capacity-aware routing. Define disablement separately from deletion/retention; neither metadata responses nor signaling messages may contain backup payloads or plaintext keys.
- Make cloud-backup enablement and the mandatory TURN policy one server-authoritative transition. Require configured TURN service and entitlement before accepting enablement; issue and refresh credentials for authorized devices. Clients must apply this policy consistently after sign-in, on new devices, and after settings refresh.
- While cloud backup is enabled, display TURN as on and non-editable with the explanation "Required for cloud backup and recovery-key sync." Reject attempts to disable TURN through the API as well as the UI. Require cloud backup to be disabled first; then allow TURN to be changed independently.
- Keep direct ICE connectivity preferred when available; mandatory TURN means relay availability, not forcing every transfer through a relay. Include TURN bandwidth/capacity in operational planning. Surface degraded connectivity when TURN is unavailable and retry with refreshed credentials; do not promise guaranteed delivery or introduce a non-WebRTC fallback.
- Ensure clients switch cleanly between hosted and self-hosted behavior based on `/api/config`.
- Ensure self-hosted mode stops contacting hosted services once switched.

Test:

- Hosted linked-devices UI reads from the server and shows the right devices.
- Hosted backup toggle updates server state and drives household-scoped assignment in the shared backup-worker pool; disabled households cannot start new backup transfers.
- Enabling cloud backup enables and locks TURN across devices; UI/API attempts to turn it off are rejected until cloud backup is disabled. Missing TURN configuration or entitlement prevents cloud-backup enablement with a clear error.
- New devices inherit the mandatory TURN policy, credential refresh survives expiration, and TURN outages produce accurate key-sync status rather than false success.
- After switching a client to a self-hosted URL, it stops using hosted auth and metadata paths and still syncs with `authMode=none`.

## Phase 6: Harden Hosted Server Abuse Controls

Goal: reduce hosted signaling and TURN abuse without pretending the open-source client can prove true physical device identity.

Build:

- reject self-targeted signaling messages
- enforce one active signaling session per device identity
- add per-user and per-device rate limits for joins, offers, answers, and ICE
- return TURN only to entitled hosted users
- rate limit TURN issuance
- document device limits as abuse controls rather than proof of physical-device count

Test:

- Duplicate hosted joins are rejected or replace the old session as designed.
- Signaling rate limits trigger for abusive join/offer/answer/ICE patterns.
- TURN is denied for non-entitled users and issued correctly for entitled users.

## Order

1. Phase 2: Auth UI, tier enforcement, and account management
2. Phase 3: Shared backup workers, client encryption, recovery, and restore (with required Phase 5 assignment metadata)
3. Phase 4: Enforce Store5 everywhere
4. Phase 5: Metadata boundary and self-hosted mode
5. Phase 6: Hosted server abuse controls

## Release Gate

- `just guard-architecture` passes for each relevant phase.
- Hosted first-launch works on web, iOS, Android, and desktop.
- Auth flows are usable without developer-only affordances.
- Feature gates enforce tier restrictions in hosted mode and bypass in self-hosted mode.
- Self-hosted backup peer works in `sqlite` mode.
- Hosted backup peer uses Supabase Postgres with a restricted database role, verified TLS, a bounded shared connection pool, and no local-storage fallback.
- Many households share each backup JVM; tested worker capacity and resource limits are documented.
- End-to-end backups contain client-encrypted contents with no server-held recovery keys. Trusted-cloud backups require explicit consent and are encrypted at rest with service-held keys. Both restore paths work on an empty device, and older backup modes are preserved.
- Recovery keys sync only to approved user devices with durable acknowledgement over direct or TURN-relayed WebRTC; saved-key recovery still works without any surviving device.
- Cloud backup cannot be enabled without TURN support or remain enabled with TURN deliberately disabled; transient outages are reported and retried rather than treated as successful key delivery.
- Interrupted transfers and worker reassignment preserve the last committed backup; backup load/failure does not compromise signaling availability.
- Store5 is the only application-data path.
- The signaling server never carries entity payloads.
- Hosted docs and self-hosted docs match shipped behavior.
