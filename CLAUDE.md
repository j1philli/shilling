# Shilling

Kotlin Multiplatform household budgeting app. Tauri desktop (wasmJs) + iOS. SQLite storage, Store5, Koin DI, Kotlin Toolchain (formerly Amper) build system.

## Build & Run

Uses [just](https://github.com/casey/just) as task runner and [direnv](https://direnv.net/) for PATH setup.

```sh
just desktop              # build wasmJs + launch Tauri desktop
just ios                  # launch on iOS Simulator (auto-downloads WebRTC framework)
just setup-webrtc         # download WebRTC.xcframework for iOS (idempotent)
just generate-icons       # regenerate committed platform icons after icons/source changes
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
project.yaml          # Kotlin Toolchain root — core, app/*, server, SQLDelight plugin
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
  src/commonMain/kotlin/finance/shilling/shared/session/
    Credentials.kt       # sign-in modes/results for onboarding
    SessionState.kt      # SessionPhase + SessionState (AppSession's phase, readable from view models)
  src/commonMain/kotlin/finance/shilling/shared/presentation/  # UI-agnostic, used by Compose and SwiftUI
    Formatting.kt        # formatCurrency/formatDate/describeRecurrence + domain labels (no Compose)
    DisplayPreferences.kt # theme mode, currency symbol, week start (StateFlow, persisted in Settings)
    HomeViewModel.kt     # Home state + copy (HomeUiState), HomeDestination
    SettingsViewModel.kt # Settings state + copy (account variants, sync status, developer info) and actions
    ActivityViewModel.kt # Activity rows (transfer legs merged), day sections, search, range, empty copy
    ReceiptsViewModel.kt # Receipts rows, filter, "N not attached" subtitle, empty copy
    PlanNavigation.kt    # PlanPeriod/PlanSection/PlanRequest + PlanRequests (Home tiles → Plan section)
    PlanOverviewViewModel.kt # Plan overview: period/anchor, by day / by category, summary, row copy, actions
    PlanListViewModels.kt # Schedules (type filter), Categories, Accounts list state
    OccurrenceActions.kt # mark paid / unmark / skip / change amount, each returning an Undoable
    SimpleEditorViewModels.kt # Category + Account editors (form state, validation, save/delete copy)
    ScheduleEditorViewModel.kt # schedule editor: fields, recurrence options, validation, next-occurrence preview
    TransactionEditorViewModel.kt # posting editor: fields, transfer legs, delete/undo, attached receipts
    ReceiptEditorViewModel.kt # receipt add/edit: file, metadata, attach/detach + transaction picker
    ImportViewModel.kt   # CSV import: column/date-format guessing, duplicate detection, per-row review
    CredentialsCopy.kt   # email/password form + confirm-email copy (Welcome and Settings)
    SampleData.kt        # seedDemoData (developer tools)
  src/commonMain/sqldelight/finance/shilling/shared/db/
    Account.sq          # accounts table schema + queries
    Category.sq         # categories table schema + queries
    Schedule.sq         # schedules table schema + queries
    ScheduleException.sq # schedule_exceptions table schema + queries
    Posting.sq          # postings table schema + queries
    Receipt.sq          # receipts table schema + queries
    Bookkeeping.sq      # tracks failed writes for Store5 sync retry
  src/commonTest/kotlin/ # NOTE: not run by Amper; put runnable tests in test@jvm/
  src@nonJvm/          # non-JVM platform sources (android + wasmJs + iOS); JVM is tests only
    finance/shilling/shared/data/sync/
      WebRtcConnectionManager.kt # WebRTC peer connection management
      WebRtcPlatform.kt     # platform WebRTC client factory + sync delay (bound per app)
    finance/shilling/shared/session/
      AppSession.kt         # onboarding state, hosted bootstrap, sync runtime; exposes SessionPhase
      SessionModule.kt      # Koin: AppSession + Settings callbacks (reset, sign out, server/household)
app/shared-ui/            # shared Compose UI (jvm, wasmJs, iOS)
  module.yaml
  src/commonMain/kotlin/finance/shilling/shared/ui/
    AppBootstrap.kt      # renders AppSession.phase: onboarding, loading, or the main scaffold
    Navigation.kt        # ShillingScaffold: NavHost, rail / bottom bar (Settings pinned), long-press tab reorder
    AppRoutes.kt         # @Serializable navigation-compose routes (tabs + detail routes)
    AppPaths.kt          # route <-> URL path codec (web browser history, deep links)
    ScreenScaffold.kt    # standard screen header + content padding, Spacing scale
    EditorScaffold.kt    # create/edit chrome (Save/Cancel/Delete+confirm), Loadable, ReadableColumn
    ListDetailLayout.kt  # two-pane list/detail on wide screens, single pane + routes on phones
    FormFields.kt        # AmountField, DateField/DatePickerModal, DropdownField, TextInputField
    Components.kt        # ConfirmDialog, EmptyState, AddButton, EntityListItem, ListSectionHeader
    SnackbarController.kt # app-level snackbar + undo (LocalSnackbarController)
    ComposeFormatting.kt # Compose-only helpers: amountColor, netColor, colorFromHex
    ShillingTheme.kt     # Material theme; provides LocalDisplayPrefs (recomposes on pref changes)
    PlanView.kt          # Plan tab: Overview (week/month, by day / by category) + Schedules, Categories, Accounts sections
    ActivityView.kt      # Activity tab: recorded transactions (search, ranges, list-detail)
    ImportView.kt        # CSV import, opened from Activity (ImportRoute)
    HomeView.kt
    ReceiptsScreen.kt    # list + editor
    AccountsView.kt / CategoriesView.kt / ScheduleViews.kt # list + editor, rendered as Plan sections
    TransactionEditor.kt # view/edit/create a posting, transfer pairs, attached receipts
    SettingsView.kt      # appearance, account, sync status, hidden developer tools
app/web-app/              # Tauri desktop app (wasmJs)
  module.yaml         # product: wasm-js/app
  src/wasmJsMain/kotlin/finance/shilling/web/
    Main.kt              # entry point, platform Koin module, WebWorkerDriver setup
    WasmPlatformServices.kt # wasmJs IdGenerator + ReceiptFileStore stubs
app/ios-app/              # Compose Multiplatform iOS app
  module.yaml         # product: ios/app
  module.xcodeproj/   # Kotlin Toolchain–managed Xcode project (has -lsqlite3 linker flag)
  src/
    App.swift           # SwiftUI @main entry, starts Koin, hosts ShillingTabBarController
    ShillingTabBarController.swift # native UITabBarController around the shared Compose UI
    NativeTabBridge.kt  # tab list/selection bridge between Compose and the native tab bar
    MainViewController.kt  # startIosKoin (platform Koin module) + ComposeUIViewController factory
    IosViewModelHost.kt  # base for Swift-facing screen models (owns a ViewModelStore)
    HomeScreenModel.kt   # Swift-facing HomeViewModel facade (@NativeCoroutinesState)
    HomeModel.swift / HomeScreen.swift # native SwiftUI Home
    SettingsScreenModel.kt / SettingsScreen.swift # native SwiftUI Settings (+ ToastView snackbar stand-in)
    ActivityScreenModel.kt / ActivityScreen.swift # native SwiftUI Activity
    ReceiptsScreenModel.kt / ReceiptsScreen.swift # native SwiftUI Receipts
    PlanScreenModel.kt / PlanScreen.swift # native SwiftUI Plan (Overview + Schedules/Categories/Accounts)
    Toast.swift          # snackbar stand-in with optional action (Undo)
    SimpleEditorScreenModels.kt / SimpleEditors.swift # native Category/Account editors; FlowModel + EditorChrome helpers
    ScheduleEditorScreenModel.kt / ScheduleEditorScreen.swift # native schedule editor
    TransactionEditorScreenModel.kt / TransactionEditorScreen.swift # native transaction editor (+ UndoHandle, NSData bridge)
    ReceiptEditorScreenModel.kt / ReceiptEditorScreen.swift # native receipt editor + attach sheet
    ImportScreenModel.kt / ImportScreen.swift # native CSV import (pushed from Activity)
    ReceiptPickers.swift # camera / photo library / file picker buttons for receipts
    DateBridge.swift     # epoch day ↔ Date (Kotlin dates cross into Swift as epoch days)
    DisplayPreferencesBridge.kt / AppearanceModel.swift # app theme mode → SwiftUI preferredColorScheme
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
third_party/sqldelight-kotlin-toolchain/ # SQLDelight codegen plugin (git subtree, don't edit here)
  sqldelight/         # the plugin module registered in project.yaml
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
`app/shared/src/commonMain/sqldelight/`. The [sqldelight-kotlin-toolchain](https://github.com/j1philli/sqldelight-kotlin-toolchain)
plugin, vendored at `third_party/sqldelight-kotlin-toolchain/` and configured under
`plugins.sqldelight` in `app/shared/module.yaml`, runs SQLDelight code generation,
producing `ShillingDatabase` and query classes.
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

**Compose state**: UI collects flows with `remember { repo.watchX() }.collectAsState()`
(always remember the Flow). Writes use `rememberCoroutineScope` + `launch` and report
results through `LocalSnackbarController` (with Undo where reversible). Screen state that
should survive process death uses `rememberSaveable`.

**Navigation**: Jetpack Navigation Compose (JetBrains KMP) with type-safe routes in
`AppRoutes.kt`. Each `ShelfDestination` is a top-level route; detail/editor routes live at
the graph root so any tab can open them. Entity screens use `ListDetailLayout`: two panes
on wide windows, navigate to the detail route on phones. System back is handled by
the NavHost; don't use screen-local boolean "pages". Two-pane detail panes close on Back via
`ListDetailLayout`. Web: `AppPaths` maps every route to a `#/path` URL and
`web-app/.../BrowserHistory.kt` syncs it with browser back/forward/reload — add new routes to
`AppPaths` or they won't appear in the URL.

**Headers**: `ScreenScaffold` has a fixed 64dp title row (`titleLarge`, same position on every
screen); a subtitle sits on its own line below. Don't add custom header rows.

**UI conventions**: every screen uses `ScreenScaffold`; every create/edit form uses
`EditorScaffold`. Use the shared fields (`AmountField`, `DateField`, `DropdownField`)
rather than raw text fields for money, dates, or selects. Destructive actions need a
`ConfirmDialog` (EditorScaffold's `delete` does this). Format with `formatCurrency` /
`formatDate` / `formatSigned`; never show raw ISO dates or enum names.

**Native iOS UI (migration in progress)**: iOS is moving to a fully native SwiftUI front end,
one screen at a time; web/desktop and Android stay on Compose. Screen logic and copy live in
shared view models (`shared/presentation`, AndroidX multiplatform `ViewModel`, registered with
Koin `viewModel {}`); Compose gets them with `koinViewModel()`. On iOS, a Kotlin facade in
`ios-app` (subclass of `IosViewModelHost`) exposes the view model's `StateFlow` with
`@NativeCoroutinesState`, and a Swift `ObservableObject` consumes it with
`asyncSequence(for: model.stateFlow)` from `KMPNativeCoroutinesAsync`. Keep KMP-NativeCoroutines
annotations out of `app/shared`: its compiler plugin crashes non-Apple compilations and the
toolchain can't scope `compilerPlugins` per platform, so it's only enabled in `ios-app`.
`ShillingTabBarController` shows a SwiftUI screen for ported tabs (`nativeScreen(for:)`) and the
shared Compose UI for the rest (all tabs are native now: Home, Plan, Activity, Receipts, Settings; all editors and CSV import are native; onboarding is still Compose). Native screens push their
editors onto their own `NavigationStack`. Home tiles still switch tabs through `NativeTabBridge` →
`PlatformTabBar`; while a Compose detail route is open (`detailOpen`), the tab
controller shows Compose over the native screen and `ComposeOverlay` drops native toolbar items
(the iPhone Duo lifts them into the side column). SwiftUI screens follow the app's
Light/Dark/System choice through `AppearanceModel` (`preferredColorScheme` at the app root). The Compose view controller must stay in the window at all
times (Compose Multiplatform disposes its scene when it leaves and crashes on re-entry, and
the Compose tabs would lose their state), so on native tabs it sits hidden under the SwiftUI screen.

Editor view models take the item id (null = new) as a Koin parameter (`viewModel { params -> … }`);
Compose passes it with `koinViewModel(key = …) { parametersOf(id) }` (key per id so two-pane
selection changes get a fresh editor) and iOS facades with `viewModel<VM>(id)`. Native editors are
pushed onto the native screen's own `NavigationStack` (no Compose bridge).

**App session**: `AppSession` (`shared/src@nonJvm/.../session`) owns the app lifecycle outside
any UI: first-launch onboarding state, hosted bootstrap (auth + household resolution, retries),
the WebRTC sync runtime (`SyncState` attach, signaling connect, ICE fetch), and the Settings
actions (reset, sign out, server/household changes). It's a process-wide singleton started by
each platform entry point right after `initKoin`; every UI renders `session.phase`
(`Onboarding` / `Starting` / `Ready`) and calls its actions. Its state is confined to
`AppSessionConfig.dispatcher` (Main); it re-evaluates everything in `reconcile()` after each
change, with keyed `Effect`s standing in for Compose's `LaunchedEffect`/`DisposableEffect`.
Platform options (self-hosted-only web distribution, log tag) come from an `AppSessionConfig`
bound in the platform module.

**DI**: Each app calls `initKoin(platformModule)` (`AppModule.kt`) from its entry point before
showing UI (iOS: `startIosKoin()` from `App.init`; web: once the database opens; Android:
`MainActivity.onCreate`), then starts `AppSession`. The platform module provides `ShillingDatabase`, `Settings`,
`IdGenerator`, `ReceiptFileStore`, `HttpClient`, and `WebRtcPlatform` (the WebRTC client factory
plus the sync delay). `initKoin` starts one global Koin graph for the process: the platform module
plus the shared `dataModule` (`DeviceIdentity`, `ChangeNotifier`, `StoreSyncDeps`, Store5 stores,
`SyncStoreFacade`, repositories, use cases, `LocalDataWiper`); later calls return the running
graph, and loads `DisplayPreferences`. Koin is started outside Compose so native (Swift) code can
resolve from the same graph.
Apps pass `sessionModule` too (it needs `WebRtcPlatform`, so it lives in the non-JVM sources);
it binds `AppSession`, `HostedBootstrapState` and the Settings callbacks. Definitions resolve their dependencies through `get()`, not captured
instances. Composables use `koinInject<T>()`. Don't construct repositories or stores by hand
outside tests. Session-scoped objects (sync runtime, `ServerApi`, `AuthService`) are owned by
`AppSession`, which rebuilds them when the server or auth changes.

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
- **Week window**: Plan's week view shows 7 days starting on the next week-start day (Friday by default, configurable in Settings)

## Conventions

- IDs: `IdGenerator.newId()` (wasmJs uses Kotlin UUID, iOS uses NSUUID)
- Currency: raw `Double`, formatted as `$%.2f`
- Dates: `kotlinx.datetime.LocalDate` — no java.time in shared code
- Models: data classes grouped in `Models.kt`, not one-file-per-class
- UI: one composable per file, `finance.shilling.shared.ui` package
- No string resources — all text is inline English

## Common Pitfalls

- **SQLDelight codegen**: Schema changes in `.sq` files require running `./kotlin build`
  to regenerate `ShillingDatabase` and query classes. The SQLDelight plugin
  handles codegen automatically during Kotlin Toolchain builds.
- **SQLDelight plugin is vendored**: `third_party/sqldelight-kotlin-toolchain/` is a
  `git subtree` of a separate public repo. Fix bugs upstream, then update with
  `git subtree pull --prefix=third_party/sqldelight-kotlin-toolchain https://github.com/j1philli/sqldelight-kotlin-toolchain.git vX.Y.Z --squash`.
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
  regenerate the project without re-adding it. Swift packages are declared as `swiftPackage:`
  dependencies in `app/ios-app/module.yaml`; the toolchain links them through the generated
  `KotlinMultiplatformLinkedPackage/` (generated from `module.yaml`; committed because the Xcode
  project references it, along with `project.xcworkspace/.../Package.resolved` — don't hand-edit). The first build
  after adding a Swift package can fail with `Cannot cast ... PBXObject to ... PBXBuildFile`;
  building again succeeds.
- **KMP-NativeCoroutines version is tied to Kotlin**: `kmp-nativecoroutines` in
  `libs.versions.toml`, the compiler plugin and the Swift package in `app/ios-app/module.yaml`
  must all use the release built for the project's Kotlin version (1.0.6 = Kotlin 2.4.20).
- **Icons are generated, then committed**: `icons/source/base-1024.png` is the
  master. `just generate-icons` (ImageMagick + `cargo tauri`) rewrites every
  platform asset; commit the results. Builds never generate or copy icons; they
  pick a committed variant (`dev`/`beta`/`ga`):
  - Beta is the early-release channel. On iOS (TestFlight) and Android (Play testing
    tracks) the beta binary is promoted to production as-is, so it uses the GA icon;
    only desktop and web ship a separate beta artifact with its own icon.
  - iOS: `AppIcon` (GA) and `AppIcon-Dev` sets, chosen per Xcode configuration via
    `ASSETCATALOG_COMPILER_APPICON_NAME` (Debug → Dev, Release → GA).
  - Desktop: `src-tauri/icons/{ga,beta,dev}/`; `tauri.conf.json` uses GA,
    `tauri.dev.conf.json` / `tauri.beta.conf.json` overlays switch via `--config`.
  - Web: `app/web-app/icons/<variant>/` copied into `web-app-dist` by `build-web.sh`
    (`SHILLING_ICON_VARIANT`, defaults to `ga`; `just` recipes use `dev`).
  - Android: GA in `app/android-app/res/mipmap-*`; dev in `app/android-app/src/debug/res/`
    (standard Android debug source set, merged over main for debug builds only).
  - In-app logo: `app/shared-ui/composeResources/drawable/app_logo.png`
    (Compose resource, `Res.drawable.app_logo`).
- **Tauri build**: Run `./build-web.sh` to build wasmJs artifacts into `web-app-dist/`,
  then `cargo tauri dev` to launch the desktop app. The `tauri.conf.json` points
  `frontendDist` to `../web-app-dist`. Direct builds use the optimized Release
  package; `just desktop` and `just web` use the Debug package for development.
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
