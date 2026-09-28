# SQLDelight plugin for the Kotlin Toolchain

A [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain) (formerly Amper) plugin that runs
[SQLDelight](https://github.com/sqldelight/sqldelight) code generation — the equivalent of the
`app.cash.sqldelight` Gradle plugin for projects built with `./kotlin`.

- Generates the database class and typesafe queries from `.sq` / `.sqm` files into the module's sources
- Works for JVM and Kotlin Multiplatform modules (JVM, Android, iOS, wasmJs, …)
- Any SQLDelight dialect: SQLite 3.18–3.38 bundled, PostgreSQL / HSQL / third-party via one setting
- Sync or async (`generateAsync`) APIs, migration-derived schemas, migration verification
- No vendored JARs: the SQLDelight compiler is resolved from Maven Central by the toolchain

| Plugin | SQLDelight | Kotlin Toolchain |
|--------|------------|------------------|
| 0.1.0  | 2.4.0      | 0.12.x           |

## Installation

The Kotlin Toolchain can't consume plugins from a repository yet
([planned](https://github.com/JetBrains/kotlin-toolchain/blob/main/docs/src/user-guide/plugins/overview.md#missing-functionality)),
so plugins live in your project as a local module. Vendor this repository at a release tag, using
either `git subtree` (no extra steps for collaborators or CI):

```sh
git subtree add --prefix=third_party/sqldelight-kotlin-toolchain \
  https://github.com/j1philli/sqldelight-kotlin-toolchain.git v0.1.0 --squash
```

or a submodule (collaborators and CI need `git submodule update --init`):

```sh
git submodule add https://github.com/j1philli/sqldelight-kotlin-toolchain.git third_party/sqldelight-kotlin-toolchain
git -C third_party/sqldelight-kotlin-toolchain checkout v0.1.0
```

Then register the plugin module in `project.yaml` (the repository's own `project.yaml` and
samples are ignored when nested in your project):

```yaml
modules:
  - third_party/sqldelight-kotlin-toolchain/sqldelight
  # ...your modules

plugins:
  - ./third_party/sqldelight-kotlin-toolchain/sqldelight
```

## Usage

Put your `.sq` files under `<module>/sqldelight/<package path>/` and enable the plugin in the module:

```yaml
# module.yaml
dependencies:
  - app.cash.sqldelight:runtime:2.4.0
  - app.cash.sqldelight:sqlite-driver:2.4.0   # or android-driver, native-driver, …

plugins:
  sqldelight:
    enabled: true
    packageName: com.example.db
    databaseName: AppDatabase
```

```kotlin
val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
AppDatabase.Schema.create(driver)
val players = AppDatabase(driver).playerQueries.selectAll().executeAsList()
```

Generated sources are produced by the `generate@sqldelight` task, which runs automatically before
compilation and only re-runs when `.sq`/`.sqm` files or settings change.

### Settings

| Setting                         | Default               | Description |
|---------------------------------|-----------------------|-------------|
| `packageName`                   | *(required)*          | Package of the generated database class. |
| `databaseName`                  | `Database`            | Name of the generated database class. |
| `sourceDir`                     | `sqldelight`          | Directory with `.sq`/`.sqm` files, relative to the module root. |
| `dialect`                       | `sqlite-3-18-dialect` | Dialect artifact ID. SQLite 3.18–3.38 are bundled; others also need `compilerDependencies`. |
| `compilerDependencies`          | `[]`                  | Extra Maven dependencies for the compiler: dialects, SQLDelight modules, newer compiler versions. |
| `generateAsync`                 | `false`               | Generate suspending APIs, for async drivers such as the web worker driver. |
| `deriveSchemaFromMigrations`    | `false`               | Build the schema from `.sqm` migrations instead of `CREATE` statements. |
| `verifyMigrations`              | `false`               | Check that `.sqm` migrations compile. |
| `treatNullAsUnknownForEquality` | `false`               | Keep SQL `NULL` semantics for `x = ?` with a null argument. |
| `expandSelectStar`              | `true`                | Expand `SELECT *` into explicit columns. |
| `codegenExcludedColumns`        | `[]`                  | `table.column` entries to leave out of generated code. |

The defaults match the SQLDelight Gradle plugin, except `sourceDir`, which follows the Kotlin
Toolchain layout (`sqldelight/` next to `src/`).

### Multiplatform

Point `sourceDir` wherever your `.sq` files live and use the drivers for your platforms. For example,
a KMP module targeting wasmJs (async web worker driver) with SQLite 3.38 syntax:

```yaml
plugins:
  sqldelight:
    enabled: true
    packageName: com.example.db
    databaseName: AppDatabase
    sourceDir: src/commonMain/sqldelight
    dialect: sqlite-3-38-dialect
    generateAsync: true
```

### Other dialects and modules

```yaml
dependencies:
  - app.cash.sqldelight:jdbc-driver:2.4.0

plugins:
  sqldelight:
    enabled: true
    packageName: com.example.db
    dialect: postgresql-dialect
    compilerDependencies:
      - app.cash.sqldelight:postgresql-dialect:2.4.0
```

SQLDelight modules (e.g. `app.cash.sqldelight:sqlite-json-module:2.4.0`) go in
`compilerDependencies` the same way. To try a newer SQLDelight before a plugin release, add
`app.cash.sqldelight:compiler-env:<version>` and `app.cash.sqldelight:gradle-plugin:<version>`
there too.

## Limitations

- One source directory per module, and no cross-module `.sq` dependencies.
- `mysql-dialect` 2.4.0 can't be resolved by the Kotlin Toolchain: it depends on the relocated
  `mysql:mysql-connector-java` artifact.
- No `generateSchema` / `verifySqlDelightMigration` equivalents yet.

## How it works

The SQLDelight compiler embeds an IntelliJ PSI environment with global state, so the plugin runs
it in a forked JVM (using the toolchain's JRE). Its classpath is exactly SQLDelight's
`compiler-env`, the SQLDelight compiler and the dialect, resolved by the toolchain through a
`Classpath` task input, plus a small runner from this plugin. `AllIconsStub.kt` provides the few
IntelliJ icon constants that dialects reference but the compiler environment doesn't ship.

## Development

```sh
./kotlin build   # builds the plugin and runs codegen for the samples
./kotlin test    # runs the sample tests against the generated code
```

`sample/` exercises the bundled SQLite dialect end to end. `sample-postgresql/` checks that a
non-bundled dialect works through `compilerDependencies`.

## Versioning and releases

The plugin follows [Semantic Versioning](https://semver.org). Changes to settings or generated
output that break existing configurations bump the major version (the minor version while in 0.x).
Releases are git tags (`vX.Y.Z`) with notes in [CHANGELOG.md](CHANGELOG.md).

To release, move the `Unreleased` changelog entries under a new `## [X.Y.Z] - YYYY-MM-DD`
heading, update the compatibility table above, then tag and push:

```sh
git tag vX.Y.Z && git push origin vX.Y.Z
```

The release workflow builds, tests, and publishes a GitHub release from the changelog section.

## License

Licensed under either of

- Apache License, Version 2.0 ([LICENSE-APACHE](LICENSE-APACHE))
- MIT license ([LICENSE-MIT](LICENSE-MIT))

at your option. Contributions are accepted under the same terms.

The `kotlin` / `kotlin.bat` wrapper scripts are © JetBrains s.r.o. and contributors, under the
Apache License 2.0. SQLDelight is © Square, Inc. and contributors, under the Apache License 2.0;
it is downloaded at build time, not redistributed here.
