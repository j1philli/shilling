# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-09-28

### Added

- `generate@sqldelight` task generating SQLDelight 2.4.0 code into the module's Kotlin sources.
- Settings: `packageName`, `databaseName`, `sourceDir`, `dialect`, `compilerDependencies`,
  `generateAsync`, `deriveSchemaFromMigrations`, `verifyMigrations`,
  `treatNullAsUnknownForEquality`, `expandSelectStar`, `codegenExcludedColumns`.
- Bundled SQLite 3.18–3.38 dialects; other dialects and SQLDelight modules via `compilerDependencies`.
- SQLDelight compiler classpath resolved by the Kotlin Toolchain from Maven Central.

[Unreleased]: https://github.com/j1philli/sqldelight-kotlin-toolchain/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/j1philli/sqldelight-kotlin-toolchain/releases/tag/v0.1.0
