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

## SQLDelight compiler environment

- File: `sqldelight-plugin/libs/compiler-env-2.4.0.jar`
- Origin: [`app.cash.sqldelight:compiler-env:2.4.0`](https://repo.maven.apache.org/maven2/app/cash/sqldelight/compiler-env/2.4.0/)
- Developer: Square, Inc.
- License: Apache-2.0, as declared by the published Maven POM
- Changes: none; the checked-in JAR matches the published Maven artifact.
