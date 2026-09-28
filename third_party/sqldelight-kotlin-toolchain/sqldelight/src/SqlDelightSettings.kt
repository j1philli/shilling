package io.github.j1philli.sqldelight

import org.jetbrains.amper.plugins.Configurable
import org.jetbrains.amper.plugins.Dependency

/**
 * User-facing settings, configured under `plugins.sqldelight` in a module's `module.yaml`.
 *
 * Mirrors the options of SQLDelight's Gradle `SqlDelightDatabase` extension where the
 * Kotlin Toolchain configuration model allows it.
 */
@Configurable
interface SqlDelightSettings {
    /** Package of the generated database class and queries, e.g. `com.example.db`. */
    val packageName: String

    /** Simple name of the generated database class. */
    val databaseName: String get() = "Database"

    /** Directory containing `.sq`/`.sqm` files, relative to the module root. */
    val sourceDir: String get() = "sqldelight"

    /**
     * Artifact ID of the SQL dialect to generate code for. The SQLite dialects
     * (`sqlite-3-18-dialect` through `sqlite-3-38-dialect`) are bundled; for any other dialect,
     * e.g. `postgresql-dialect`, also add its coordinates to [compilerDependencies].
     */
    val dialect: String get() = "sqlite-3-18-dialect"

    /**
     * Extra Maven dependencies for the SQLDelight compiler: third-party dialects, SQLDelight
     * modules such as `app.cash.sqldelight:sqlite-json-module:2.4.0`, or newer SQLDelight
     * compiler artifacts (these take precedence over the bundled ones).
     */
    val compilerDependencies: List<Dependency> get() = emptyList()

    /** Generate suspending query APIs (required for async drivers such as the web worker driver). */
    val generateAsync: Boolean get() = false

    /** Derive the schema from `.sqm` migration files instead of `CREATE` statements in `.sq` files. */
    val deriveSchemaFromMigrations: Boolean get() = false

    /** Verify that `.sqm` migration files compile against the schema. */
    val verifyMigrations: Boolean get() = false

    /** Treat `NULL` as unknown in equality comparisons (`WHERE x = ?` with a null argument). */
    val treatNullAsUnknownForEquality: Boolean get() = false

    /** Expand `SELECT *` into explicit column lists in generated queries. */
    val expandSelectStar: Boolean get() = true

    /** Columns (as `table.column`) to exclude from generated code. */
    val codegenExcludedColumns: List<String> get() = emptyList()
}
