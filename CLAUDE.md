# Shilling

Kotlin Multiplatform household budgeting app. Tauri desktop (wasmJs) + iOS. SQLite storage, Store5, Koin DI, Kotlin Toolchain (formerly Amper) build system.

## Build & Run

Uses [just](https://github.com/casey/just) as task runner and [direnv](https://direnv.net/) for PATH setup.

```sh
just desktop              # build wasmJs + launch Tauri desktop
just ios                  # launch on iOS Simulator (auto-downloads WebRTC framework)
just setup-webrtc         # download WebRTC.xcframework for iOS (idempotent)
just generate-icons       # regenerate local icon outputs from icons/source (required after fresh clone)
just apply-icons dev      # copy one generated variant into ignored platform asset dirs
just build-web            # build only wasmJs artifacts
just build-all            # compile everything (all platforms)
just guard-architecture   # fail on forbidden relay/server data-sync patterns
just test                 # run all tests
just clean                # remove build outputs
just deps                 # npm install (sql.js, js-joda)
just tasks web-app        # show Kotlin Toolchain tasks for a module
```

DB file: `~/.shilling/expenses.db` (auto-created on first run, desktop uses IndexedDB via sql.js).

`just desktop` and `just build-web` use a **dev database** (`shilling-dev` IndexedDB).
Production builds use `./build-web.sh` directly (defaults to `shilling` IndexedDB).
Set `SHILLING_DB_NAME` to override (e.g. `SHILLING_DB_NAME=shilling-staging ./build-web.sh`).

## Project Structure

```
project.yaml          # Kotlin Toolchain root — core, app/*, server, sqldelight-plugin
libs.versions.toml    # centralized dependency versions
core/                 # protocol/models shared by server + clients (auth config, signaling envelopes)
app/                  # client modules (JetBrains server-aware KMP layout)
app/shared/               # client shared library (JVM + wasmJs + iOS + android)
  module.yaml         # platforms: [jvm, wasmJs, iosArm64, iosSimulatorArm64]
  src/commonMain/kotlin/finance/shilling/shared/data/
    Models.kt           # all @Serializable data classes and enums
    PlatformServices.kt # IdGenerator + ReceiptFileStore interfaces (multiplatform-ready)
    Recurrence.kt       # recurrence pattern engine + occurrence generator
    CsvImporter.kt      # CSV parsing for bank imports
    store/
      ChangeNotifier.kt     # shared version signal for cross-entity reactivity
      StoreKeys.kt          # Store5 Key sealed classes per entity type
      ShillingStores.kt     # Store5 MutableStore factories + wrapper classes
      AccountRepository.kt  # account CRUD + reactive watchAll()
      CategoryRepository.kt # category CRUD + reactive watchAll()
      ScheduleRepository.kt # schedule + exception CRUD + reactive watches
      PostingRepository.kt  # posting CRUD, ad-hoc, bulk import, transfer pairs
      ReceiptRepository.kt  # receipt CRUD + reactive watches
      Mappers.kt            # extension functions to convert SQLDelight entities to domain models
    sync/
      SyncModels.kt         # entity sync payloads (ChangeMessage); signaling envelopes live in core
      SyncConfig.kt         # sync configuration (server URL, household/device IDs)
      ServerApi.kt          # control-plane API client (config + hosted household + ICE)
      SignalingClient.kt    # WebSocket client for WebRTC signaling
      PeerSyncManager.kt    # orchestrates peer-to-peer sync lifecycle
      IncomingChangeRouter.kt # applies remote changes to local SQLDelight database
    usecase/
      ComputeWindowUseCase.kt  # Friday window computation, balanceAt, cashCurve
      ComputeBudgetUseCase.kt  # monthly budget computation
  src/commonMain/sqldelight/finance/shilling/shared/db/
    Account.sq          # accounts table schema + queries
    Category.sq         # categories table schema + queries
    Schedule.sq         # schedules table schema + queries
    ScheduleException.sq # schedule_exceptions table schema + queries
    Posting.sq          # postings table schema + queries
    Receipt.sq          # receipts table schema + queries
    Bookkeeping.sq      # tracks failed writes for Store5 sync retry
  src/commonTest/kotlin/ # cross-platform unit tests (kotlin-test)
  src@nonJvm/          # non-JVM platform sources (wasmJs + iOS)
    finance/shilling/shared/data/sync/
      WebRtcConnectionManager.kt # WebRTC peer connection management
app/shared-ui/            # shared Compose UI (jvm, wasmJs, iOS)
  module.yaml
  src/commonMain/kotlin/finance/shilling/shared/ui/
    Navigation.kt        # ShillingScaffold, nav rail + route definitions
    WeeklyView.kt        # upcoming transactions (Friday-to-Friday window)
    HistoryView.kt       # transaction history + quick ad-hoc entry
    ReceiptsScreen.kt    # standalone receipt store (upload, attach, manage)
    ImportView.kt        # CSV import wizard
    AccountsView.kt     # account CRUD
    CategoriesView.kt   # category CRUD with color picker
    BudgetView.kt        # monthly budget projection
    ScheduleViews.kt     # expense/income/transfer schedule CRUD + AddScheduleForm
    Formatting.kt        # formatCurrency, describeRecurrence, colorFromHex
    DevView.kt           # seed demo data
app/web-app/              # Tauri desktop app (wasmJs)
  module.yaml         # product: wasm-js/app
  src/wasmJsMain/kotlin/finance/shilling/web/
    Main.kt              # entry point, Koin module, WebWorkerDriver setup
    WasmPlatformServices.kt # wasmJs IdGenerator + ReceiptFileStore stubs
app/ios-app/              # Compose Multiplatform iOS app
  module.yaml         # product: ios/app
  module.xcodeproj/   # Kotlin Toolchain–managed Xcode project (has -lsqlite3 linker flag)
  src/
    App.swift           # SwiftUI @main entry, wraps ComposeUIViewController
    MainViewController.kt  # ComposeUIViewController factory + Koin init
    IosPlatformServices.kt # IosIdGenerator (NSUUID), IosReceiptFileStore (NSFileManager), NativeSqliteDriver
    ShillingIosApp.kt      # receipt store UI (add, list, edit, attach, delete)
src-tauri/            # Tauri native shell (Rust)
  tauri.conf.json     # app config, bundle identifier, frontend dist path
  src/main.rs         # Rust entry point
server/               # Ktor JVM server for WebRTC signaling + hosted metadata
  module.yaml         # JVM server module (depends on ../core for shared protocol models)
  src/finance/shilling/server/
    Main.kt             # Ktor server entry point, CORS + routing setup
    SignalingHub.kt     # WebSocket-based WebRTC signaling coordinator
    HostedMetadata.kt   # hosted household lookup via Supabase user_profiles
    IceConfig.kt        # ICE server config endpoint + TURN credential generation
    SupabaseTokenVerifier.kt # JWKS / legacy HS256 verifier for hosted access tokens
sqldelight-plugin/    # custom Kotlin Toolchain plugin for SQLDelight code generation
  plugin.yaml         # Kotlin Toolchain plugin descriptor
  module.yaml
  libs/               # SQLDelight compiler JAR (compiler-env-2.1.0.jar)
  src/
    GenerateSqlDelight.kt # plugin entry point — forks JVM process to run codegen
    CodegenRunner.kt      # loads SQLDelight compiler, processes .sq files
```

## Architecture

**Hard invariants**:
- User data traversal must use WebRTC P2P data channels only.
- WebSocket signaling is control-plane only: `Join`, `PeerList`, `Offer`, `Answer`, `IceCandidate`.
- Store5 is the required data-management boundary for entity data.
- If a platform cannot support WebRTC P2P for user data, disable sync on that platform instead of adding a fallback transport.

**Data layer**: One repository per entity type (`AccountRepository`, `CategoryRepository`,
`ScheduleRepository`, `PostingRepository`, `ReceiptRepository`) wraps SQLDelight CRUD.
A shared `ChangeNotifier` (MutableStateFlow<Int>) signals cross-entity changes.
Complex cross-entity logic lives in use cases (`ComputeWindowUseCase`, `ComputeBudgetUseCase`).

**Store5**: Each entity type has a `MutableStore` wrapper (e.g. `AccountStore`) built with
Store5's `MutableStoreBuilder`. SourceOfTruth reads from SQLDelight and remote entity
fetchers are intentionally disabled. Cross-device propagation happens after local Store5
writes via `PeerSyncManager`. Bookkeeper uses `Bookkeeping.sq` to track failed writes for retry.

**Sync layer**: WebRTC-based peer-to-peer sync using Ktor client WebRTC on all platforms
(wasmJs + iOS). `SignalingClient` connects to the server via WebSocket for peer discovery,
`WebRtcConnectionManager` establishes data channels, and `IncomingChangeRouter` applies
remote changes to the local SQLDelight database. The signaling server must not relay user
data payloads. `PeerSyncManager` orchestrates the lifecycle.

**Server**: Ktor JVM server providing WebRTC signaling via WebSocket (`/ws/signal`),
hosted bootstrap config via `GET /api/config`, hosted household lookup via
`GET /api/household`, and dynamic ICE server configuration via `GET /api/ice-servers`.
The server is not part of the user-data traversal path and does not expose REST entity
sync. Hosted metadata lives in Supabase, not a local server database.

**ICE/TURN configuration**: Clients fetch ICE server config from `GET /api/ice-servers`
at startup instead of hardcoding STUN servers. The server reads STUN/TURN config from
env vars and generates time-limited TURN credentials using coturn's `use-auth-secret`
HMAC-SHA1 mechanism. When no TURN secret is configured (homelab), only STUN is returned.
Clients fall back to Google's public STUN if the server is unreachable.

**SQLDelight codegen**: Schema is defined in `.sq` files (one per table) under
`app/shared/src/commonMain/sqldelight/`. A custom Kotlin Toolchain plugin (`sqldelight-plugin/`)
runs SQLDelight code generation, producing `ShillingDatabase` and query classes.
`Mappers.kt` contains extension functions to convert SQLDelight entities to domain
models. `generateAsync = true` for wasmJs WebWorkerDriver compatibility.

**Platform abstractions**: `IdGenerator` and `ReceiptFileStore` are interfaces in
the shared module, implemented per-platform (wasmJs in `WasmPlatformServices.kt`,
iOS in `IosPlatformServices.kt`). Injected via Koin — no expect/actual needed.

**Migrations**: Database versioning uses `PRAGMA user_version`. On startup, if
`currentVersion < ShillingDatabase.Schema.version`, old tables are dropped and
the schema is recreated (acceptable pre-GA). For production migrations, use
SQLDelight migrations with version checks.

**Reactive data**: Repositories expose `Flow`-based watch methods using SQLDelight's
`asFlow().map { it.awaitAsList() }`. A shared `ChangeNotifier` increments a version
counter after writes; use cases use `notifier.version.flatMapLatest` to re-query
across entities.

**Compose state**: UI collects flows with `collectAsState`. Writes use
`rememberCoroutineScope` + `launch`. Navigation via `NavigationRail` + Jetpack
Navigation Compose.

**DI**: Koin provides `SqlDriver`, `ShillingDatabase`, `IdGenerator`, `ReceiptFileStore`,
`ChangeNotifier`, all repositories, all use cases, and all Store5 stores as singletons.
Injected in composables with `koinInject<T>()`.

## Database Schema

| Table                | Purpose                        | Key columns                    |
|----------------------|--------------------------------|--------------------------------|
| accounts             | bank/cash accounts             | id, name, balance              |
| categories           | labels with optional color hex | id, name, color                |
| schedules            | recurring + one-off definitions| 17 columns (see Models.kt)    |
| schedule_exceptions  | per-date skip/override         | schedule_id + date (composite PK) |
| postings             | settled transactions           | id, schedule_id, date, amount, pair_id, title, category_id |
| receipts             | uploaded receipt files          | id, posting_id (nullable), file_path, original_name, added_at, receipt_date, amount, notes |
| bookkeeping          | tracks failed writes for sync  | entity_type, entity_id, timestamp (composite PK) |
| expenses (legacy)    | deprecated — do not use        | id, title, amount, due_date    |

Dates: epoch-day integers. Types/frequencies: enum name strings.
Transfers create two postings (debit + credit) linked by `pair_id`.

## Key Domain Concepts

- **Schedule**: recurring or one-time planned transaction (expense/income/transfer)
- **Occurrence**: projected instance of a schedule for a date (computed, not stored)
- **Posting**: recorded/settled occurrence (stored in DB)
- **Exception**: per-date override (amount/account change) or skip for a schedule
- **Receipt**: uploaded file (image, PDF, etc.) — can exist independently or be attached to a posting
- **Friday window**: default weekly view runs Friday-to-Friday

## Conventions

- IDs: `IdGenerator.newId()` (wasmJs uses Kotlin UUID, iOS uses NSUUID)
- Currency: raw `Double`, formatted as `$%.2f`
- Dates: `kotlinx.datetime.LocalDate` — no java.time in shared code
- Models: data classes grouped in `Models.kt`, not one-file-per-class
- UI: one composable per file, `finance.shilling.shared.ui` package
- No string resources — all text is inline English

## Common Pitfalls

- **SQLDelight codegen**: Schema changes in `.sq` files require running `./kotlin build`
  to regenerate `ShillingDatabase` and query classes. The `sqldelight-plugin/`
  handles codegen automatically during Kotlin Toolchain builds.
- **`expenses` table is deprecated**: all new features use schedules + postings.
- **`balance` on Account is stored, not computed**: not derived from postings.
  Must be kept in sync manually if adding auto-reconciliation.
- **Domain model mapping**: SQLDelight generates entity classes from `.sq` files.
  Use the `toDomain()` extension functions in `Mappers.kt` to convert to domain
  models defined in `Models.kt`. Never modify generated SQLDelight classes.
- **Database migrations**: Pre-GA, the app drops and recreates tables on version
  mismatch. For production, add SQLDelight migration files (`.sqm`) and update
  the version check logic to preserve user data.
- **filekit**: declared in `libs.versions.toml` but must be added to consuming
  module's `module.yaml` dependencies before use.
- **Shared module is multiplatform**: `type: lib` targeting JVM + wasmJs + iOS. Shared
  code must stay free of JVM-specific imports (no java.* in commonMain). JVM platform
  is kept for unit tests and future server module.
- **iOS WebRTC framework**: `ktor-client-webrtc` on iOS requires a native
  `WebRTC.xcframework` (from `webrtc-sdk/Specs` GitHub releases, version
  137.7151.04). It's gitignored so each clone must run `./setup-webrtc.sh`
  (or `just setup-webrtc`) to download it. `just ios` runs this automatically.
  Override version with `WEBRTC_SDK_VERSION` env var if needed.
- **iOS Xcode project**: Kotlin Toolchain manages `app/ios-app/module.xcodeproj`. The
  `-lsqlite3` linker flag was manually added to `OTHER_LDFLAGS` — don't
  regenerate the project without re-adding it.
- **Icon assets are source-only in git**: `icons/source/` plus the icon scripts
  are tracked. Generated variants under `icons/{dev,beta,ga}/` and copied
  platform assets (`app/android-app/res/mipmap-*`, `app/ios-app/.../AppIcon.appiconset`,
  `src-tauri/icons/`, `app/web-app/favicon.ico`, `app/web-app/apple-touch-icon.png`)
  are gitignored. On a fresh clone, run `just generate-icons` and
  `just apply-icons dev` before builds that consume app icons.
- **Tauri build**: Run `./build-web.sh` to build wasmJs artifacts into `web-app-dist/`,
  then `cargo tauri dev` to launch the desktop app. The `tauri.conf.json` points
  `frontendDist` to `../web-app-dist`.
- **Store5 is experimental**: Uses `@ExperimentalStoreApi`. The library is at
  `5.1.0-alpha07`. Monitor for breaking API changes.
- **Dev vs production database**: `just desktop`/`just build-web` inject
  `SHILLING_DB_NAME=shilling-dev` so dev data stays separate from production.
  Production builds call `./build-web.sh` directly (no env var → defaults to
  `"shilling"`). The server has no local app database; hosted metadata lives in
  Supabase and the server is control-plane only.
- **ICE/TURN server env vars** (all optional):
  - `SHILLING_STUN_URLS`: Comma-separated STUN URLs (default: `stun:stun.l.google.com:19302`)
  - `SHILLING_TURN_URLS`: Comma-separated TURN URLs (default: empty = no TURN)
  - `SHILLING_TURN_SECRET`: coturn shared secret for `use-auth-secret` mode (default: empty = TURN disabled)
  - `SHILLING_TURN_TTL`: TURN credential validity in seconds (default: `86400` = 24h)

## iOS Simulator Testing

When I ask you to test a feature or verify a UI change:

1. **Build the app**: Run the build command and wait for success
2. **Launch logs observe script**: Run just ios-logs in an observable way to view logs for the simulator
3. **Launch in simulator**: Use `launch_app` with the bundle ID
4. **Navigate to the feature**: Use `ui_tap` and `ui_swipe` to get there (refer to sitemap.md available in the root directory)
5. **Verify the state**: Use the accessibility describe tools to read what’s on screen
6. **Take a screenshot**: Capture the result for confirmation
7. **Report back**: Tell me what you found

### When Something Looks Wrong
1. Read the accessibility tree
2. Compare expected vs actual element states
3. Take a screenshot for my review
4. Suggest a fix
