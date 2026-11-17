# Hosted cloud storage

User approved: hosted Postgres + private Supabase Storage; no prototype migrations.

- Keep device data exclusively on WebRTC and persistence behind Store5.
- Detach receipt bytes into immutable encrypted objects; reuse references for unchanged files.
- Store hosted receipt/checkpoint objects as binary in private Storage. Postgres holds compact deltas, metadata and transient resumable upload staging; SQLite remains self-contained.
- Publish device-created full checkpoints only against an unchanged server head. Preserve tombstones; lagging devices merge checkpoints before tail replay. Prune covered deltas only after durable object publication.
- Move hosted policy/consent/worker assignments to a separate restricted Postgres control-plane role.
- Validate ciphertext, dedup, restart/retry, checkpoint races, offline edits/deletes, orphan cleanup, permissions and both modes/backends. Use fresh setup SQL.

## Completed and verified

Implemented private binary Storage objects for receipts/checkpoints, household-scoped receipt reuse, bounded 1 MiB read-ahead with a 64 MiB worker cache, durable object garbage collection, authenticated device checkpoints with head compare-and-set and log compaction, and Postgres-backed hosted policy/consent/worker assignments with separate database roles. Self-hosted SQLite retains all ciphertext locally. Fresh setup SQL and deployment documentation are in `supabase/cloud-peer.sql`, `supabase/cloud-storage.sql`, and `docs/server/backups.mdx`; no migration or hosted deployment was performed.

Final verification:
- Shared JVM: 87 passed; three environment-gated tests exercised separately where applicable.
- Server JVM: 20 passed; two opt-in tests not enabled in the regular run.
- Disposable Postgres 17 plus actual Supabase Storage API: four integration tests passed, including private access and role separation.
- Real JVM server/worker processes over forced TURN: both encryption modes passed on Supabase and SQLite, including interrupted receipt upload, replay, hard restart, offline-source recovery, tamper rejection, household isolation, automatic checkpoint compaction, receipts, and tombstones.
- Shared/UI web, Android, and iOS device/simulator compilation passed before the final JVM-only ranged-read change; final JVM suites cover that change.
- Architecture guard and diff whitespace checks passed; owned test containers cleaned up.

Limits remain explicit: 512 MiB household ciphertext reservation plus 64 MiB checkpoint staging headroom; checkpoint manifests up to 50,000 entity versions, 512 receipt references, and 32 MiB. Larger states defer compaction. Garbage collection runs on subsequent uploads. Multi-instance signaling still needs household-affine routing. This was functional verification, not a production load benchmark; external integrations and historical/off-provider backups remain future work.

## Multi-client verification

Added three simultaneously connected JVM clients per encryption mode, each with its own database and durable outbox. They upload independent receipts, race conflicting edits, reconnect after an offline edit and a newer deletion, and converge after checkpoint compaction plus a hard worker restart. Passed on both SQLite and disposable Supabase. Bounded retries exercise the worker's upload-capacity and checkpoint-head contention rather than serializing the writers.

Added `scripts/test-cloud-browser.mjs` and Playwright as a development dependency. It uses three isolated headless Chrome contexts, production Wasm UI, forced TURN, and blocked direct `/ws/signal` connections. Both modes passed onboarding/consent or recovery-key creation/import, concurrent independent account writes, conflicting edits, and offline cloud-only catch-up with the other two browsers closed. Browser stats verify selected relay candidates. The user chose headless web in place of desktop-window testing; no desktop success is claimed.

The reload test found a recovery-setup race: BackupController published enabled policy before loading persisted key state. It now loads keys first. A gated-vault regression test proves enabled policy stays unpublished while the vault is loading; shared JVM suite passed 88 tests (three opt-in cases not enabled in that invocation), and the updated Wasm build passed. Browser reloads now retain the configured key without reopening setup.

Final combined command passed: `SHILLING_TEST_WEB_URL=http://127.0.0.1:8138 scripts/test-supabase-backup.sh`. Four Postgres/Storage integration cases passed, followed by the expanded live JVM scenarios and both headless browser modes against the same freshly provisioned fixture. No unhandled browser application errors. Combined artifacts: `/var/folders/51/p2c09kkx7176qzk6k_spch5w0000gn/T/shilling-live-backup-7465325273960928372/`. Architecture guard and whitespace checks passed. Browser receipt UI, other browser engines, and long-running load/soak tests remain outside this functional run.
