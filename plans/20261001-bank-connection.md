# Canada and United States bank connection

Launch regions: Canada (`CA`) and United States (`US`), confirmed October 1, 2026.

The user approved clients fetching bank-provider data over HTTPS. Device-to-device sync remains WebRTC only, and imports must pass through Store5. The bank entitlement exists; no connection or live bank import is implemented yet.

## Provider decision

Plaid Transactions is the proposed first provider because it covers both launch countries ([institution coverage](https://plaid.com/docs/institutions/), [Transactions overview](https://plaid.com/docs/transactions/)). Production approval, the actual supported bank list, commercial terms, and Canadian OAuth behavior must be checked against Shilling's target banks before committing to a contract.

Plaid cannot be called safely with only a client-held access token: its financial-data endpoints also require the application's client ID and secret ([Transactions API](https://plaid.com/docs/api/products/transactions/), [token glossary](https://plaid.com/docs/quickstart/glossary/)). Embedding that secret in iOS, Android, web, or desktop is unacceptable. This leaves a separate decision beyond the currently approved direct client HTTPS exception.

## Proposed narrow import endpoint, awaiting authorization

A bank-specific HTTPS endpoint, separate from signaling, would authenticate the Supabase account, verify current space membership and the space's sponsored Silver/Gold capability, and attach the server-held Plaid secret to a fixed set of provider requests. It would return the provider response directly to the requesting client. It would not store transactions, balances, provider response bodies, or a server catch-up ledger; request/response body logging and tracing must be disabled for these routes. Device sync would still use only WebRTC.

Only fixed Plaid operations would be allowed: create/update Link, exchange a public token, read the selected accounts, read transaction deltas, reconnect, and revoke the Item. No general-purpose URL proxy, payment initiation, or bank transfer API. This proposal requires a specific bank-ingestion exception to the server-backed entity transport rule before implementation.

## Client/Store5 implementation after that decision

1. The person connecting a bank gives consent, selects provider accounts, and maps each to a local account in the selected space. Only a space owner/admin can establish or remove a connection. A paid sponsor unlocks capability; it does not automatically share another person's bank credentials.
2. Keep provider connection credentials on the connecting device in platform-secure storage. They are never included in a full-state snapshot, receipt transfer, or signaling message. Agree on a browser credential-storage policy before enabling web/desktop reconnect. Other members receive imported application records through their space's WebRTC channel.
3. Keep provider identity, transaction identity, import cursor, pending/posted mapping, and deleted-provider transaction tombstones scoped by space and connection. Apply a complete delta batch and advance its cursor in one Store5 SourceOfTruth transaction. Use stable provider identifiers to make retries idempotent. A reconnect/relink must preserve mappings rather than import duplicates.
4. Preserve user edits separately from provider values. Pending-to-posted reconciliation must replace the pending import, not count it twice. Provider removals/reversals must be visible and must not erase receipts or independently edited postings without a defined policy.
5. Import CAD and USD without implicit conversion. Existing Account/Posting models have no currency field, so currency must become explicit before importing mixed-currency balances or transactions. Do not use the global display symbol to infer transaction currency.
6. Refresh when the connecting client is open and online. Offline devices retain their Store5 data. No hosted entity catch-up, scheduled server transaction import, or REST device-sync fallback.
7. Revoking consent stops future provider requests and invalidates credentials; imported local history remains until the user deliberately deletes it. Membership removal stops subsequent WebRTC access, while copies already held on former members' devices remain.

## Provisioning and acceptance checks

Required: a Plaid developer account, Transactions sandbox credentials, production approval/credentials for US and CA, approved redirect URIs and package/bundle IDs, consent/privacy copy, supported-bank acceptance list, and reviewed provider costs against Silver's USD $5/month or $50/year pricing.

Before enabling the feature: test a US and Canadian institution, CAD and USD, pending-to-posted replacement, duplicate batches, pagination/cursor rollback, revoked consent, reconnect/relink, sponsor loss, member removal, network interruption, and updates on another device exclusively through WebRTC. No real bank login or financial information is requested in chat.
