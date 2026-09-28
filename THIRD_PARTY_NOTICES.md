# Third-party notices

The project license in `LICENSE` covers Shilling's original code. The following
files include third-party work under the Apache License, Version 2.0. Their
copyright and license notices remain in effect. A copy of that license is in
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

## SQLDelight Android driver

- File: `app/android-app/src/finance/shilling/android/AndroidSqliteDriver.kt`
- Origin: `app.cash.sqldelight:android-driver:2.1.0` from
  [SQLDelight](https://github.com/sqldelight/sqldelight)
- Copyright (C) 2018 Square, Inc.
- License: Apache-2.0
- Changes: inlined and adapted for this application's Android database setup,
  avoiding duplicate Android AAR variants in the build.

## SQLDelight plugin for the Kotlin Toolchain

- Directory: `third_party/sqldelight-kotlin-toolchain/`
- Origin: [j1philli/sqldelight-kotlin-toolchain](https://github.com/j1philli/sqldelight-kotlin-toolchain)
  v0.1.0, vendored with `git subtree`
- License: MIT OR Apache-2.0; see the licenses in that directory
- Changes: none. The SQLDelight compiler it runs is downloaded from Maven
  Central at build time and not checked in.
