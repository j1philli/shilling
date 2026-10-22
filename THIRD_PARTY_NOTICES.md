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

## GLib Rust bindings security backport

- Directory: `third_party/glib/`
- Origin: `glib` 0.18.5 from [gtk-rs](https://github.com/gtk-rs/gtk-rs-core)
- License: MIT; copyright and license are preserved in that directory
- Original crate SHA-256: `233daaf6e83ae6a12a52055f568f9d7cf4671dabb78ff9560ab6da230ce00ee5`
- Changes: backport [upstream PR #1343](https://github.com/gtk-rs/gtk-rs-core/pull/1343)
  for [RUSTSEC-2024-0429](https://rustsec.org/advisories/RUSTSEC-2024-0429.html).
  In `src/variant_iter.rs`, `VariantStrIter::impl_get` uses a mutable pointer
  variable and passes `&mut p` to the C out-parameter instead of `&p`. All other
  crate files are unchanged. Cargo patches the registry dependency to this local
  source; desktop build contexts include it.
- Removal: when Tauri's GTK dependency permits a patched GLib release (0.20+),
  remove the Cargo patch, this directory, and the corresponding build-context
  copies together. Version-only scanners may still flag 0.18.5; inspect the
  resolved source and the backport rather than suppressing all GLib findings.
- Regression check: `just test-glib-security` runs the upstream iterator tests
  with release optimizations, which expose the original undefined behavior.
