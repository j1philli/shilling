package io.github.j1philli.sqldelight

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Runs SQLDelight code generation for one module.
 *
 * The SQLDelight compiler embeds an IntelliJ PSI environment with global state, so it runs
 * in a forked JVM whose classpath is exactly [extraClasspath] and [compilerClasspath]
 * (resolved by the Kotlin Toolchain from Maven) plus this plugin's own classes for [CodegenRunner].
 */
@TaskAction
@OptIn(ExperimentalPathApi::class)
fun generateSqlDelight(
    @Input sourceDir: Path,
    @Output outputDir: Path,
    @Input compilerClasspath: Classpath,
    @Input extraClasspath: Classpath,
    moduleName: String,
    packageName: String,
    databaseName: String,
    dialect: String,
    generateAsync: Boolean,
    deriveSchemaFromMigrations: Boolean,
    verifyMigrations: Boolean,
    treatNullAsUnknownForEquality: Boolean,
    expandSelectStar: Boolean,
    codegenExcludedColumns: List<String>,
) {
    require(sourceDir.isDirectory()) {
        "SQLDelight source directory does not exist: $sourceDir (configure `plugins.sqldelight.sourceDir`)"
    }

    outputDir.deleteRecursively()
    outputDir.createDirectories()

    val runnerLocation = CodegenRequest::class.java.protectionDomain?.codeSource?.location
        ?: error("Cannot locate the SQLDelight plugin classes")

    // compiler-env bundles the IntelliJ platform classes SQLDelight needs; it must win over any
    // partial copies pulled in transitively, so it goes first. User-provided dependencies come
    // before the bundled ones so they can override the SQLDelight version.
    val resolvedFiles = extraClasspath.resolvedFiles + compilerClasspath.resolvedFiles
    val (compilerEnv, rest) = resolvedFiles.partition { it.name.startsWith("compiler-env-") }
    val classpath = (compilerEnv + rest + listOf(Path.of(runnerLocation.toURI())))
        .distinct()
        .joinToString(File.pathSeparator) { it.absolutePathString() }

    val request = CodegenRequest(
        sourceDir = sourceDir.toAbsolutePath(),
        outputDir = outputDir.toAbsolutePath(),
        moduleName = moduleName,
        packageName = packageName,
        databaseName = databaseName,
        dialectArtifactId = dialect.split(':').getOrElse(1) { dialect },
        generateAsync = generateAsync,
        deriveSchemaFromMigrations = deriveSchemaFromMigrations,
        verifyMigrations = verifyMigrations,
        treatNullAsUnknownForEquality = treatNullAsUnknownForEquality,
        expandSelectStar = expandSelectStar,
        codegenExcludedColumns = codegenExcludedColumns.toSet(),
    )

    val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").absolutePathString()
    val process = ProcessBuilder(
        listOf(javaBin, "--add-modules", "java.sql", "-cp", classpath, CodegenRequest.RUNNER_MAIN_CLASS) +
            request.toArgs()
    )
        .redirectErrorStream(true)
        .start()

    // SQLDelight logs per-file progress; only surface the output when something goes wrong.
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        System.err.println(output.trimEnd())
        error("SQLDelight code generation failed (exit code $exitCode)")
    }
}
