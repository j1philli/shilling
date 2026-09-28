package io.github.j1philli.sqldelight

import app.cash.sqldelight.core.SqlDelightCompilationUnit
import app.cash.sqldelight.core.SqlDelightDatabaseName
import app.cash.sqldelight.core.SqlDelightDatabaseProperties
import app.cash.sqldelight.core.SqlDelightEnvironment
import app.cash.sqldelight.core.SqlDelightSourceFolder
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import java.io.File
import java.util.ServiceLoader
import kotlin.system.exitProcess

/**
 * Entry point of the forked code generation process. Only runs with the SQLDelight compiler
 * classpath assembled by [generateSqlDelight]; never loaded in the Kotlin Toolchain JVM.
 */
fun main(args: Array<String>) {
    val request = CodegenRequest.fromArgs(args)
    val sourceDir = request.sourceDir.toFile()
    val outputDir = request.outputDir.toFile()

    val sourceFolder = object : SqlDelightSourceFolder {
        override val folder: File = sourceDir
        override val dependency: Boolean = false
    }

    val compilationUnit = object : SqlDelightCompilationUnit {
        override val name: String = "main"
        override val sourceFolders: Set<SqlDelightSourceFolder> = setOf(sourceFolder)
        override val outputDirectoryFile: File = outputDir
    }

    val properties = object : SqlDelightDatabaseProperties {
        override val packageName: String = request.packageName
        override val className: String = request.databaseName
        override val compilationUnits: List<SqlDelightCompilationUnit> = listOf(compilationUnit)
        override val dependencies: List<SqlDelightDatabaseName> = emptyList()
        override val deriveSchemaFromMigrations: Boolean = request.deriveSchemaFromMigrations
        override val treatNullAsUnknownForEquality: Boolean = request.treatNullAsUnknownForEquality
        override val rootDirectory: File = sourceDir
        override val generateAsync: Boolean = request.generateAsync
        override val expandSelectStar: Boolean = request.expandSelectStar
        override val codegenExcludedColumns: Set<String> = request.codegenExcludedColumns
    }

    val environment = SqlDelightEnvironment(
        properties = properties,
        compilationUnit = compilationUnit,
        verifyMigrations = request.verifyMigrations,
        dialect = loadDialect(request.dialectArtifactId),
        moduleName = request.moduleName,
        sourceFolders = listOf(sourceDir),
        dependencyFolders = emptyList(),
    )

    when (val status = environment.generateSqlDelightFiles { println("[SQLDelight] $it") }) {
        is SqlDelightEnvironment.CompilationStatus.Success -> Unit
        is SqlDelightEnvironment.CompilationStatus.Failure -> {
            System.err.println("SQLDelight code generation failed:")
            status.errors.forEach { System.err.println("  $it") }
            exitProcess(1)
        }
    }
}

/**
 * Dialects depend on the dialects they extend (e.g. SQLite 3.38 on 3.37), so several may be
 * registered; pick the one shipped by the configured artifact.
 */
private fun loadDialect(artifactId: String): SqlDelightDialect {
    val dialects = ServiceLoader.load(SqlDelightDialect::class.java).toList()
    if (dialects.isEmpty()) error("No SQLDelight dialect found on the classpath; check `plugins.sqldelight.dialect`")
    return dialects.singleOrNull()
        ?: dialects.firstOrNull { it.jarName().startsWith("$artifactId-") }
        ?: error("Cannot find dialect from `$artifactId` among ${dialects.map { "${it::class.qualifiedName} (${it.jarName()})" }}")
}

private fun SqlDelightDialect.jarName(): String =
    this::class.java.protectionDomain?.codeSource?.location?.path?.substringAfterLast('/').orEmpty()
