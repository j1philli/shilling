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

- Server: authorize household membership and registered devices at signaling `Join`; issue TURN credentials only for households with an effective Silver/Gold plan; recheck when connections or credentials renew. An established direct WebRTC data channel cannot be forcibly shut down by a control-plane-only server, so entitlement changes are enforced at reconnection rather than promising instant cutoff. Keep signaling messages limited to `Join`, `PeerList`, `Offer`, `Answer`, and `IceCandidate`.
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

Pricing, billing periods, and free trials are not fixed by this policy. A household may have multiple paid sponsors; each subscription remains owned and managed by its purchaser.
