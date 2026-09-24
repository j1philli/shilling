package finance.shilling.build

import app.cash.sqldelight.core.SqlDelightCompilationUnit
import app.cash.sqldelight.core.SqlDelightDatabaseName
import app.cash.sqldelight.core.SqlDelightDatabaseProperties
import app.cash.sqldelight.core.SqlDelightEnvironment
import app.cash.sqldelight.core.SqlDelightSourceFolder
import app.cash.sqldelight.dialects.sqlite_3_38.SqliteDialect
import java.io.File

/**
 * Standalone entry point for SQLDelight code generation.
 * Runs in a forked JVM process with the full classpath (compiler-env + java.sql).
 *
 * Args: <sqDir> <outputDir> <packageName> <databaseName>
 */
fun main(args: Array<String>) {
    require(args.size == 4) {
        "Usage: CodegenRunner <sqDir> <outputDir> <packageName> <databaseName>"
    }
    val sqDirFile = File(args[0])
    val outputDirFile = File(args[1])
    val packageName = args[2]
    val databaseName = args[3]

    val sourceFolder = object : SqlDelightSourceFolder {
        override val folder: File = sqDirFile
        override val dependency: Boolean = false
    }

    val compilationUnit = object : SqlDelightCompilationUnit {
        override val name: String = "commonMain"
        override val sourceFolders: Set<SqlDelightSourceFolder> = setOf(sourceFolder)
        override val outputDirectoryFile: File = outputDirFile
    }

    val properties = object : SqlDelightDatabaseProperties {
        override val packageName: String = packageName
        override val className: String = databaseName
        override val compilationUnits: List<SqlDelightCompilationUnit> = listOf(compilationUnit)
        override val dependencies: List<SqlDelightDatabaseName> = emptyList()
        override val deriveSchemaFromMigrations: Boolean = false
        override val treatNullAsUnknownForEquality: Boolean = false
        override val rootDirectory: File = sqDirFile
        override val generateAsync: Boolean = true
        override val expandSelectStar: Boolean = true
        override val codegenExcludedColumns: Set<String> = emptySet()
    }

    val environment = SqlDelightEnvironment(
        properties = properties,
        compilationUnit = compilationUnit,
        verifyMigrations = false,
        dialect = SqliteDialect(),
        moduleName = "shared",
        sourceFolders = listOf(sqDirFile),
        dependencyFolders = emptyList(),
    )

    val status = environment.generateSqlDelightFiles { message ->
        println("[SQLDelight] $message")
    }

    when (status) {
        is SqlDelightEnvironment.CompilationStatus.Success -> {
            println("[SQLDelight] Code generation succeeded")
        }
        is SqlDelightEnvironment.CompilationStatus.Failure -> {
            System.err.println("SQLDelight code generation failed:")
            status.errors.forEach { System.err.println("  $it") }
            System.exit(1)
        }
    }
}
