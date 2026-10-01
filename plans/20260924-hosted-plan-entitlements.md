# Hosted Plan Entitlements

Date: 2026-09-24
Status: policy defined; hosted entitlement, device, TURN, purchase entry points, and space membership roles implemented; invitations, isolated space switching, linked transfers, and bank provider pending

Free, Silver, and Gold are hosted subscription plans. Self-hosted is an explicit deployment edition with operator-controlled infrastructure, not a RevenueCat product. Local budgeting and access to existing local data remain available in every edition.

The user-facing container is a **finance space**: a home, side business, rental, or other set of books. The legacy protocol and database field still call it `household_id` during migration. Each space needs isolated Store5 data. A cross-space transfer should be recorded once and create linked entries in both spaces, so funding a business from home finances and moving profits back are visible from either side. This linked transfer must stay in the Store5/repository boundary and sync over WebRTC for each space; no server transaction ledger is allowed.

## Plans

| Capability | Hosted Free | Hosted Silver | Hosted Gold | Self-hosted |
| --- | --- | --- | --- | --- |
| Registered sync devices per household | 2 | Unlimited | Unlimited | Operator controlled |
| WebRTC sync | Direct connections; no hosted TURN allocation | Direct connections plus hosted TURN | Direct connections plus hosted TURN | Direct connections plus operator-configured TURN |
| Household memberships per account | 1 | 1 | Unlimited | Operator controlled |
| Live bank account connection and read-only transaction sync | No | Included | Included | Available when the operator configures a supported provider |

"Same network" is product shorthand for the expected Free experience, not a guaranteed restriction: withholding TURN does not prevent WebRTC from connecting across different networks through direct ICE candidates and STUN. Product copy must say that Free sync works best when devices are on the same local network, while Silver provides the hosted relay needed when direct connection fails. Do not promise that Free can *only* sync on one network. All user data still traverses WebRTC P2P data channels; TURN, when used, relays encrypted WebRTC traffic and is never an application-data endpoint.

## Accounts and households

- A person has a hosted account. Multiple accounts can be members of one household. Membership, invitations, removal, and active household selection are server-owned metadata.
- Purchases belong to the purchasing account. For a household's shared sync capabilities, its effective plan is the highest active plan among its current members. One Silver or Gold member therefore grants every member of that household unlimited registered devices and hosted TURN access while that membership and subscription remain active.
- The number of households an account can join or create follows **that account's own plan**: Free and Silver may each belong to one; Gold may belong to unlimited households. A Gold member's sponsorship of a household does not give every other member unlimited household memberships. This avoids one subscription spreading through a chain of households.
- Removing or leaving the subscribing member causes the remaining household to use the next-highest active member plan for new connections and TURN credentials. A person can sponsor every household of which they are a current member.
- Space owners/admins manage invitations, members, and devices. Existing profiles are backfilled into server-owned space membership metadata; owners and admins can remove devices. Invitation, member removal, and last-owner transfer flows still need UI and transactional server actions.
- A Silver or Gold sponsor unlocks live bank reading for members of that household. Bank connections, provider credentials, consent, and imported transaction ownership need a separate provider-specific design. This policy does not authorize bank data to traverse signaling or bypass Store5; bank provider transport is an explicit third-party integration and must be reviewed separately from device-to-device sync.

## Devices and downgrades

- A device is a persistent registered device identity for a household, including when offline. The hosted server registers it and enforces the household's limit before allowing hosted signaling. One open-source client cannot prove a unique physical device, so this limit is an abuse and product control, not hardware attestation.
- Members can view and remove registered devices. Removing a device revokes its hosted signaling access; it can be registered again if a slot is available.
- If a household drops to Free with more than two registered devices, local data remains available on every device. Hosted sync pauses for devices above the limit until members choose the two active devices or the household regains a paid sponsor. Do not delete device data or choose devices silently.
- If an account drops from Gold while in multiple households, local data remains available. It must choose one household to keep active before hosted sync and membership actions resume for that account. Existing other members retain their own access according to their entitlements. Define ownership transfer before allowing a departing sole owner to leave a household.
- Expired, refunded, or revoked subscriptions use the same downgrade rules. A short grace period may follow the verified billing provider entitlement, not a client-side timer.

## Billing and authority

- Use RevenueCat entitlements `silver` and `gold`, mapped to one stable hosted account ID across iOS, Android, web, and desktop. Gold includes Silver capabilities. The hosted server computes effective plan from verified account entitlements and household memberships; clients display the result but cannot grant it.
- iOS and Android use their native in-app purchase flows. The web app uses RevenueCat Web checkout. Desktop opens the same web checkout in the system browser, then refreshes account entitlement state on return/sign-in. Purchases made on one surface unlock the same account on the others.
- Web checkout requires a supported RevenueCat billing engine/payment provider. RevenueCat Billing with Stripe is the initial recommended choice; final provider, regional availability, and store compliance are release decisions. The client checks for plan activation after opening web checkout. Native clients use RevenueCat's management URL when available; web purchasers use the customer portal link in their billing email.
- Require a durable hosted account before purchase so the subscription can be restored and shared across surfaces. Support purchase restoration, subscription management, and a clear pending-payment state.
- The hosted server reads active entitlements directly from RevenueCat's API and caches them briefly. Webhooks and a local subscription mirror may be added for scale or faster updates, with signature verification and idempotent processing. Clients must not set their own paid tier in profile metadata. The server returns effective account and household capabilities through authenticated metadata endpoints.

## Enforcement boundaries

- Server: authorize household membership and registered devices at signaling `Join`; issue TURN credentials only for households with an effective Silver/Gold plan; recheck when connections or credentials renew. Device removal sends `PeerList.removedDeviceIds` to remaining signaling clients; updated clients close that peer's channels and cancel retries. A signaling policy rejection closes all channels on the rejected client and stops automatic re-registration until an explicit sync restart. This requires updated clients connected to the same signaling instance: older clients, missed notifications, and multiple signaling instances still need release testing or additional control-plane coordination. An established direct WebRTC data channel cannot be forcibly shut down by a control-plane-only server, so do not promise instant cutoff for arbitrary clients. Entitlement changes are enforced at reconnection. Keep signaling messages limited to `Join`, `PeerList`, `Offer`, `Answer`, and `IceCandidate`.
- Client: present plan/device/household status, upgrade and manage-subscription actions, and actionable states for full device slots and downgrade selection. The client may hide controls for usability but is not the enforcement authority.
- Application data stays in Store5/repository code and moves between devices only over WebRTC data channels. No server entity sync or backup fallback is introduced by this policy.
- Model self-hosted as a separate deployment policy, not an extra RevenueCat entitlement. `/api/config` selects hosted versus self-hosted behavior; the self-hosted server does not contact RevenueCat or impose hosted device and household quotas.

## Data-preserving finance-space cutover

The current SQLDelight entity tables, receipt files, and Store5 keys are global to one local database. `ensureLocalSchemaReady` now stops on an unknown schema version or missing table while preserving existing data. A real migration must be written before adding a space column, and it must preserve the existing database as the first home space.

1. Add a `space_id` to each local entity, receipt-file, bookkeeping, and change-log key. Migrate existing rows to the device's cached active hosted space (or the local self-hosted space) inside one SQLite transaction. Rebuild primary and foreign keys around `(space_id, id)` without dropping user data. The web worker's IndexedDB-backed SQLDelight bootstrap needs the same versioned migration path.
2. Make every Store5 repository query, writer, and delete scope to its space. Recreate the repository graph and WebRTC runtime when a member switches spaces; stop the old runtime before the new space's stores are visible. Keep receipts and their binary files in the same scope.
3. Add a linked transfer aggregate at the Store5 boundary. One command writes a debit posting in the source space and a credit posting in the destination space with a shared link ID and explicit source/destination account IDs. The write is atomic in the one local database; each space's P2P sync broadcasts only its own entry. A retry must be idempotent, and deleting or editing either side must update the pair.
4. Only then enable server-side active-space switching, invitations, member removal, and Gold's multiple-space UI. The server enforces account membership count using the purchasing account's plan, while a space's effective capabilities use its highest subscribed member.

## Release work

1. Replace `ANONYMOUS/FREE/PAID` as the product plan model with account identity plus Free/Silver/Gold capabilities. Anonymous hosted accounts receive Free capabilities until upgraded; guest-to-email upgrade must preserve account identity and purchases.
2. Add server-owned household memberships/invitations and migrate existing `user_profiles.household_id` without losing household association. Registered devices and authenticated entitlement metadata are implemented.
3. Configure RevenueCat products and purchase links, then validate purchase, restore, and cross-platform identity flows in a real billing environment. Native SDK and web purchase entry points are implemented; webhook storage is optional with the current direct lookup design.
4. Enforce device slots and TURN entitlement in hosted control-plane code. Validate downgrade, member departure, stale webhooks, reconnection, and concurrent joins.
5. Add household switching and management UI, plan surfaces, and public copy matching actual direct-connection behavior.
6. Run `just guard-architecture` for implementation changes touching sync, server, or data flows.

Pricing approved on 2026-10-01: Silver USD $5/month or $50/year; Gold USD $20/month or $200/year. Free trials are not specified. A household may have multiple paid sponsors; each subscription remains owned and managed by its purchaser.

## Launch follow-up (2026-10-01)

- Hosted Supabase project: `shilling.finance` (`rjzjwibztzaovgwkdmlg`). Applied `20260926120000`, `20260926121000`, and `20260926122000` successfully. Restored the three already-applied later migration files unchanged from public main; preserve migration history rather than marking remote migrations reverted.
- Device removal implementation now closes existing connections on updated clients. JVM regression tests cover household-scoped removal notification, signaling target removal, and removal after the device already lost signaling. Native/browser channel closure, badges, self-removal, explicit re-registration, and queued file transfers still require runtime validation.
- RevenueCat project is `shilling.finance` (`projbb574ddb`). Created Silver entitlement `entle617697949` and Gold entitlement `entl80b0ac903f`, offerings `silver` (`ofrnge8a3e01c14`) and `gold` (`ofrngb3511cb42a`), and monthly/annual packages in each. No products are attached. Neither native store app is registered. Creating the web billing app failed because no Stripe account is linked. This session exposes no project/app/catalog listing or public-key retrieval MCP tools; product setup in the existing automatic Test Store requires its app ID.

### Billing setup remaining

1. Verify App Store Connect and Google Play apps and credentials. The repository identifiers are `finance.shilling.app` on iOS and `finance.shilling.android` on Android. Confirm those match the registered store apps.
2. Configure monthly and yearly products at the approved USD prices. Register each platform product in RevenueCat; product registration alone does not create a sellable store product. Configure a supported web billing engine and payment provider.
3. Create or reuse entitlements `silver` and `gold`. Attach Silver products to Silver and Gold products to Gold; the server grants Gold the Silver capabilities. Create offerings `silver` and `gold`, each with `$rc_monthly` and `$rc_annual` packages containing the matching platform products.
4. Put the project ID, secret API v2 key, entitlement resource IDs, native public SDK keys, offering identifiers, and production web purchase links into the hosted server's runtime variables listed in `.env.example`. Secret keys belong only in the deployment secret store. No billing secrets or production links have been configured by this session. The confirmed non-secret values are:

   ```dotenv
   SHILLING_REVENUECAT_PROJECT_ID=projbb574ddb
   SHILLING_REVENUECAT_SILVER_ENTITLEMENT_ID=entle617697949
   SHILLING_REVENUECAT_GOLD_ENTITLEMENT_ID=entl80b0ac903f
   SHILLING_REVENUECAT_SILVER_OFFERING_ID=silver
   SHILLING_REVENUECAT_GOLD_OFFERING_ID=gold
   ```
5. Validate hosted account ID continuity through guest upgrade, native restore, web checkout, desktop browser return, and cross-platform sign-in. Test purchase success/cancellation, pending payment, restore, renewal, expiry/refund, Silver downgrade, and Gold downgrade with multiple memberships. Sandbox checks precede any real charge.

### Infrastructure and runtime validation remaining

1. Review and merge the public branch through its required `guard` check. Build and deploy the server in Coolify after the migrations and runtime configuration are ready, then deploy the web client and distribute the updated native clients. No server deployment has been performed by this session.
2. Select an existing TURN deployment or provision one; configure URLs, shared secret, and TTL. Test two actual networks with relay off and on, inspect selected ICE candidate pairs, verify Free receives no hosted TURN credentials, and verify paid relay remains opt-in. TURN relays encrypted WebRTC only.
3. Remove a connected device from each supported settings surface. Confirm both ends close their channel, badges update, local data survives, and the removed app does not silently re-register. Repeat with signaling temporarily disconnected and during receipt transfer. Document the behavior of older clients and any deployment with more than one signaling instance.
4. Test concurrent Free registrations, downgrade with more than two registered devices, subscription lookup failure, and sponsor departure. Existing device selection after downgrade still needs a clear end-to-end acceptance check.

### Broader plan dependencies

1. Complete versioned, data-preserving SQLite and web IndexedDB migration to space-scoped entity, receipt, bookkeeping, and change-log keys. Scope every Store5 reader/writer/delete before exposing a second space; test equal entity IDs in different spaces, existing-user migration, and interrupted migration recovery.
2. Recreate and stop repository/sync graphs during space switching. Then add transactional invitations, accept/decline/expiry, owner/admin membership management, membership quotas, and last-owner transfer protection. Validate one sponsor grants the space shared capabilities without granting other accounts unlimited memberships.
3. Implement linked transfers at the Store5 boundary after isolation: atomic paired postings, stable link ID, idempotent retry, paired edits/deletes, and independent P2P sync of each space's entry.
4. Choose bank provider and launch region, then design consent, credential storage, reconnect/revocation, read-only import, deduplication, and ownership. Silver currently grants the entitlement only. Resolve the provider transport design against the architecture invariants before implementation.
