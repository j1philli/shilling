package io.github.j1philli.sqldelight

import java.nio.file.Path

/** Arguments passed from the task action to the forked [CodegenRunner] process as `key=value` pairs. */
internal data class CodegenRequest(
    val sourceDir: Path,
    val outputDir: Path,
    val moduleName: String,
    val packageName: String,
    val databaseName: String,
    val dialectArtifactId: String,
    val generateAsync: Boolean,
    val deriveSchemaFromMigrations: Boolean,
    val verifyMigrations: Boolean,
    val treatNullAsUnknownForEquality: Boolean,
    val expandSelectStar: Boolean,
    val codegenExcludedColumns: Set<String>,
) {
    fun toArgs(): List<String> = listOf(
        "sourceDir=$sourceDir",
        "outputDir=$outputDir",
        "moduleName=$moduleName",
        "packageName=$packageName",
        "databaseName=$databaseName",
        "dialectArtifactId=$dialectArtifactId",
        "generateAsync=$generateAsync",
        "deriveSchemaFromMigrations=$deriveSchemaFromMigrations",
        "verifyMigrations=$verifyMigrations",
        "treatNullAsUnknownForEquality=$treatNullAsUnknownForEquality",
        "expandSelectStar=$expandSelectStar",
        "codegenExcludedColumns=${codegenExcludedColumns.joinToString(",")}",
    )

    companion object {
        const val RUNNER_MAIN_CLASS = "io.github.j1philli.sqldelight.CodegenRunnerKt"

        fun fromArgs(args: Array<String>): CodegenRequest {
            val values = args.associate {
                val separator = it.indexOf('=')
                require(separator > 0) { "Malformed argument: $it" }
                it.substring(0, separator) to it.substring(separator + 1)
            }
            fun string(key: String) = values[key] ?: error("Missing argument: $key")
            fun boolean(key: String) = string(key).toBooleanStrict()

            return CodegenRequest(
                sourceDir = Path.of(string("sourceDir")),
                outputDir = Path.of(string("outputDir")),
                moduleName = string("moduleName"),
                packageName = string("packageName"),
                databaseName = string("databaseName"),
                dialectArtifactId = string("dialectArtifactId"),
                generateAsync = boolean("generateAsync"),
                deriveSchemaFromMigrations = boolean("deriveSchemaFromMigrations"),
                verifyMigrations = boolean("verifyMigrations"),
                treatNullAsUnknownForEquality = boolean("treatNullAsUnknownForEquality"),
                expandSelectStar = boolean("expandSelectStar"),
                codegenExcludedColumns = string("codegenExcludedColumns").split(',').filter { it.isNotBlank() }.toSet(),
            )
        }
    }
}
