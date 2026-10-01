# Canada and United States bank connection

Launch regions: Canada (`CA`) and United States (`US`), confirmed October 1, 2026.

Status: **live bank connections deferred** by the user on October 1, 2026. Bank data must stay off Shilling servers, including transient forwarding, logging, and storage. The proposed bank-only forwarding endpoint is rejected; it is not awaiting authorization.

The user approved clients fetching bank-provider data over HTTPS, but this does not authorize a Shilling bank-data proxy. Device-to-device sync remains WebRTC only, and any future imports must pass through Store5. The bank entitlement exists; no connection or live bank import is implemented. Do not advertise live banking as an available paid-plan feature.

## Provider decision

Plaid Transactions was evaluated for both launch countries ([institution coverage](https://plaid.com/docs/institutions/), [Transactions overview](https://plaid.com/docs/transactions/)). No provider is selected or being provisioned while live connections are deferred.

Plaid cannot be called safely with only a client-held access token: its financial-data endpoints also require the application's client ID and secret ([Transactions API](https://plaid.com/docs/api/products/transactions/), [token glossary](https://plaid.com/docs/quickstart/glossary/)). Do not embed that secret in iOS, Android, web, or desktop. The proposed Shilling forwarding endpoint would handle bank responses, which conflicts with the user's decision even without storing them.

## Rejected server approach

A bank-specific HTTPS endpoint was proposed to attach the server-held Plaid secret and return provider responses directly to the requesting client without storing or logging them. The user declined this approach. Do not implement bank-response forwarding, bank-data webhooks, scheduled server imports, or a hosted transaction ledger.

Reopening live connections requires an explicit user decision and a provider architecture that keeps financial responses between the provider and clients, with no provider secret embedded in distributed apps. These future requirements are retained for that review; they are not current implementation tasks.

## Future client/Store5 requirements if live connections resume

1. The person connecting a bank gives consent, selects provider accounts, and maps each to a local account in the selected space. Only a space owner/admin can establish or remove a connection. A paid sponsor unlocks capability; it does not automatically share another person's bank credentials.
2. Keep provider connection credentials on the connecting device in platform-secure storage. They are never included in a full-state snapshot, receipt transfer, or signaling message. Agree on a browser credential-storage policy before enabling web/desktop reconnect. Other members receive imported application records through their space's WebRTC channel.
3. Keep provider identity, transaction identity, import cursor, pending/posted mapping, and deleted-provider transaction tombstones scoped by space and connection. Apply a complete delta batch and advance its cursor in one Store5 SourceOfTruth transaction. Use stable provider identifiers to make retries idempotent. A reconnect/relink must preserve mappings rather than import duplicates.
4. Preserve user edits separately from provider values. Pending-to-posted reconciliation must replace the pending import, not count it twice. Provider removals/reversals must be visible and must not erase receipts or independently edited postings without a defined policy.
5. Import CAD and USD without implicit conversion. Existing Account/Posting models have no currency field, so currency must become explicit before importing mixed-currency balances or transactions. Do not use the global display symbol to infer transaction currency.
6. Refresh when the connecting client is open and online. Offline devices retain their Store5 data. No hosted entity catch-up, scheduled server transaction import, or REST device-sync fallback.
7. Revoking consent stops future provider requests and invalidates credentials; imported local history remains until the user deliberately deletes it. Membership removal stops subsequent WebRTC access, while copies already held on former members' devices remain.

## Provisioning and acceptance checks

Deferred: provider selection and credentials, production approval for US and CA, approved redirect URIs and package/bundle IDs, consent/privacy copy, supported-bank acceptance list, and reviewed provider costs against Silver's USD $5/month or $50/year pricing. These are not blockers for launching the currently implemented paid-plan features.

Before enabling the feature: test a US and Canadian institution, CAD and USD, pending-to-posted replacement, duplicate batches, pagination/cursor rollback, revoked consent, reconnect/relink, sponsor loss, member removal, network interruption, and updates on another device exclusively through WebRTC. No real bank login or financial information is requested in chat.
