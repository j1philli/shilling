# Sync Architecture Follow-Up

Date: 2026-03-11

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
- backup-peer module and hosted backup storage path
- abuse controls beyond current hosted join authorization and authenticated TURN gating
- Store5 boundary cleanup in sync receive/replay and projection code

## Locked Decisions

- User entity data traverses WebRTC P2P data channels only.
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
- Backup peer supports two explicit storage modes:
  - `sqlite` for self-hosted
  - `supabase` for hosted
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

## Phase 3: Create The Backup Peer And Its Modes

Goal: create the backup peer from scratch and make its runtime modes explicit and shippable.

Build:

- Create the `backup-peer/` JVM module.
- Create the hosted JVM Store5 Supabase source-of-truth needed by the backup peer.
- Add hosted signaling auth for backup peer.
- Make `sqlite` mode the self-hosted path with per-household SQLite files.
- Make `supabase` mode the hosted path using the Store5 Supabase implementation.
- Fail fast on invalid startup combinations:
  - `supabase` mode without Supabase credentials
  - hosted signaling without a backup-peer auth credential
- Discover hosted backup assignments by `household_id`, not auth user ID.

Test:

- The new backup-peer process starts, exposes health, and can be launched independently from the app/server.
- Self-hosted server + `sqlite` backup peer stores one household and survives reconnect.
- Hosted server + `supabase` backup peer joins signaling and persists data under the correct hosted household.
- Invalid env combinations fail on startup instead of limping along.

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
- Ensure clients switch cleanly between hosted and self-hosted behavior based on `/api/config`.
- Ensure self-hosted mode stops contacting hosted services once switched.

Test:

- Hosted linked-devices UI reads from the server and shows the right devices.
- Hosted backup toggle updates server state and drives hosted backup-peer assignment.
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
2. Phase 3: Backup peer modes
3. Phase 4: Enforce Store5 everywhere
4. Phase 5: Metadata boundary and self-hosted mode
5. Phase 6: Hosted server abuse controls

## Release Gate

- `just guard-architecture` passes for each relevant phase.
- Hosted first-launch works on web, iOS, Android, and desktop.
- Auth flows are usable without developer-only affordances.
- Feature gates enforce tier restrictions in hosted mode and bypass in self-hosted mode.
- Self-hosted backup peer works in `sqlite` mode.
- Hosted backup peer works in `supabase` mode.
- Store5 is the only application-data path.
- The signaling server never carries entity payloads.
- Hosted docs and self-hosted docs match shipped behavior.
