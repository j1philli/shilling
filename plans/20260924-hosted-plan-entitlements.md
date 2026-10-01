# Hosted Plan Entitlements

Date: 2026-09-24
Status: hosted entitlement/device/purchase code, invitations/membership, isolated Store5 switching, and paired linked transfers implemented; launch configuration, bank integration, and physical-device acceptance checks remain

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
- Space owners/admins manage invitations, members, and devices through implemented UI and transactional server actions. Existing profiles are backfilled into server-owned space membership metadata. Removing the last owner is blocked; a sole owner may close a space with no other members while retaining local records.
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

SQLDelight entity tables, receipt files, and Store5 keys are now scoped to an immutable finance-space identity. The versioned migration preserves the published legacy database as the first local space, then adopts it into the first hosted space. `ensureLocalSchemaReady` rejects unknown schemas and invalid references without discarding existing data. The implemented cutover follows these requirements:

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

- Hosted Supabase project: `shilling.finance` (`rjzjwibztzaovgwkdmlg`). Applied `20260926120000`, `20260926121000`, `20260926122000`, and the verified `20261001183000` space-management migration successfully. Restored the three already-applied later migration files unchanged from public main; preserve migration history rather than marking remote migrations reverted.
- Device removal implementation now closes existing connections on updated clients. JVM regression tests cover household-scoped removal notification, signaling target removal, and removal after the device already lost signaling. Native/browser channel closure, badges, self-removal, explicit re-registration, and queued file transfers still require runtime validation.
- RevenueCat project is `shilling.finance` (`projbb574ddb`). Created Silver entitlement `entle617697949` and Gold entitlement `entl80b0ac903f`, offerings `silver` (`ofrnge8a3e01c14`) and `gold` (`ofrngb3511cb42a`), and monthly/annual packages in each. Both native RevenueCat app records and eight subscription product records are now created and attached to their entitlement and monthly/annual package. The actual store apps are not registered. Creating the web billing app failed because no Stripe account is linked. This session exposes no project/app/catalog listing or public-key retrieval MCP tools; the existing automatic Test Store still requires its app ID for a sandbox catalog.

### Billing setup remaining

1. Register App Store Connect and Google Play apps and credentials; neither store app is registered yet. The repository identifiers are `finance.shilling.app` on iOS and `finance.shilling.android` on Android.
2. Create the native store subscriptions at the approved USD prices using the identifiers recorded below; RevenueCat product registration alone does not create a sellable store product. Configure a supported web billing engine and payment provider.
3. Verify the created entitlements, offerings, packages, and native product mappings against the actual store catalog. Silver products are attached to Silver and Gold products to Gold; the server grants Gold the Silver capabilities. Both offerings already contain monthly and annual packages with their matching native products.
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

1. Review and merge the finance-space branch through its required `guard` check. Build and deploy the server in Coolify after the migrations and runtime configuration are ready, then deploy the web client and distribute the updated native clients. No server deployment has been performed by this session.
2. Select an existing TURN deployment or provision one; configure URLs, shared secret, and TTL. Test two actual networks with relay off and on, inspect selected ICE candidate pairs, verify Free receives no hosted TURN credentials, and verify paid relay remains opt-in. TURN relays encrypted WebRTC only.
3. Remove a connected device from each supported settings surface. Confirm both ends close their channel, badges update, local data survives, and the removed app does not silently re-register. Repeat with signaling temporarily disconnected and during receipt transfer. Document the behavior of older clients and any deployment with more than one signaling instance.
4. Test concurrent Free registrations, downgrade with more than two registered devices, subscription lookup failure, and sponsor departure. Existing device selection after downgrade still needs a clear end-to-end acceptance check.

### Broader plan dependencies

1. Local space isolation/migration is implemented. JVM tests cover published-v1 upgrades, receipt bytes, invalid-reference rollback, identical IDs in different spaces, stale editor retirement, and scoped snapshots. The real sql.js worker test covers rollback, IndexedDB commit failure/retry/reload, and foreign-key enforcement. SQLite 3.22 compatibility is verified with the actual old engine, including parent updates and foreign-key cascades during first-space adoption; grouped writes are awaited explicitly. Still run physical existing-user upgrades on iOS/Android and browser acceptance.
2. Switching and membership management are implemented. `20261001183000_hosted_space_management.sql` is applied to the hosted project; deploy the new server and clients together after review. Isolated PostgreSQL tests cover quotas, invited-email enforcement, role permissions, revoked codes, last-owner protection, and removed-member device registration, and fresh explicit space selection after Gold downgrade. Test real sponsor departure and multi-device switching after deployment.
3. Linked transfer Store5 aggregate and UI are implemented: atomic paired postings/version records, stable IDs, idempotent retry/deletion, paired edits/deletes, and space-private snapshots. JVM tests verify rollback when the destination account is missing. Remote delivery is independent per space, so peers can temporarily see one side. Test concurrent offline edits and receipt detachment on actual devices.
4. Canada and United States are selected; client HTTPS bank-provider ingestion is authorized. Proposed provider and remaining authentication/currency/consent/import decisions are in `plans/20261001-bank-connection.md`. Silver currently grants the entitlement only.


### Native RevenueCat catalog created October 1, 2026

| Platform/app | Plan | Store identifier | RevenueCat product | USD price to configure in store |
| --- | --- | --- | --- | --- |
| iOS `app6e8c06929e` | Silver monthly | `finance.shilling.silver.monthly` | `prodaddd63ef97` | $5/month |
| iOS | Silver annual | `finance.shilling.silver.annual` | `prod8d28c99542` | $50/year |
| iOS | Gold monthly | `finance.shilling.gold.monthly` | `prodb15382504c` | $20/month |
| iOS | Gold annual | `finance.shilling.gold.annual` | `prod7242d2b85f` | $200/year |
| Android `appf42a31ccc7` | Silver monthly | `silver:monthly` | `prod6f342ab445` | $5/month |
| Android | Silver annual | `silver:annual` | `prodf720ba0839` | $50/year |
| Android | Gold monthly | `gold:monthly` | `prod6983741b58` | $20/month |
| Android | Gold annual | `gold:annual` | `prod8affb6a040` | $200/year |

Use one Apple subscription group and two Google subscriptions (`silver`, `gold`) with monthly/annual base plans; configure crossgrades/downgrades in the stores and verify them in sandbox. App Store Connect subscription/API keys and Google service-account credentials are not configured. Public SDK keys still require retrieval from the RevenueCat dashboard because the exposed MCP app response does not contain them.
