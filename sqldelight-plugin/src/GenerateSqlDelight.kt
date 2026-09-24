package finance.shilling.build

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

@TaskAction
@OptIn(ExperimentalPathApi::class)
fun generateSqlDelight(
    @Input sqDir: Path,
    @Output outputDir: Path,
    packageName: String,
    databaseName: String,
) {
    outputDir.deleteRecursively()

    val sqDirFile = sqDir.toFile()
    if (!sqDirFile.exists() || !sqDirFile.isDirectory) {
        error("SQLDelight source directory does not exist: $sqDir")
    }

    val outputDirFile = outputDir.toFile()
    outputDirFile.mkdirs()

    val pluginDir = Path.of("sqldelight-plugin")
    val compilerEnvJar = pluginDir.resolve("libs/compiler-env-2.4.0.jar")

    if (!compilerEnvJar.exists()) {
        error("compiler-env JAR not found at $compilerEnvJar. Run: " +
            "curl -sL -o $compilerEnvJar https://repo1.maven.org/maven2/app/cash/sqldelight/compiler-env/2.4.0/compiler-env-2.4.0.jar")
    }

    // Build classpath for the forked JVM.
    val classpathEntries = mutableLinkedSetOf<String>()
    // compiler-env fat JAR goes first (provides IntelliJ PSI classes)
    classpathEntries.add(compilerEnvJar.toAbsolutePath().toString())

    // Get the plugin's own compiled classes location
    val thisClassUrl = object {}::class.java.protectionDomain?.codeSource?.location
    if (thisClassUrl != null) {
        classpathEntries.add(File(thisClassUrl.toURI()).absolutePath)
    }

    // Walk classloader chain for URLClassLoaders
    var cl: ClassLoader? = Thread.currentThread().contextClassLoader
    while (cl != null) {
        if (cl is URLClassLoader) {
            cl.urLs.forEach { url ->
                try {
                    classpathEntries.add(File(url.toURI()).absolutePath)
                } catch (_: Exception) {}
            }
        }
        cl = cl.parent
    }

    // Resolve JARs from Maven caches for all SQLDelight + sql-psi artifacts.
    mavenCaches().forEach { mavenCache ->
        listOf(
            mavenCache.resolve("app/cash/sqldelight"),
            mavenCache.resolve("app/cash/sql-psi"),
            mavenCache.resolve("dev/lysine/sql-psi"),
        ).forEach { dir ->
            if (dir.exists()) {
                dir.walk()
                    .filter { it.isRegularFile() && it.toString().endsWith(".jar") }
                    .forEach { classpathEntries.add(it.toAbsolutePath().toString()) }
            }
        }
    }

    // Fallback: java.class.path
    val sysCp = System.getProperty("java.class.path")
    if (!sysCp.isNullOrBlank()) {
        sysCp.split(File.pathSeparator).filter { it.isNotBlank() }.forEach { classpathEntries.add(it) }
    }

    val classpath = classpathEntries.joinToString(File.pathSeparator)

    val javaHome = System.getProperty("java.home")
    val javaBin = "$javaHome/bin/java"

    val process = ProcessBuilder(
        javaBin,
        "--add-modules", "java.sql",
        "-cp", classpath,
        "finance.shilling.build.CodegenRunnerKt",
        sqDir.toAbsolutePath().toString(),
        outputDir.toAbsolutePath().toString(),
        packageName,
        databaseName,
    )
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (output.isNotBlank()) println(output.trimEnd())

    if (exitCode != 0) {
        error("SQLDelight code generation failed (exit code $exitCode)")
    }
}

private fun <T> mutableLinkedSetOf(vararg elements: T): MutableSet<T> =
    LinkedHashSet<T>(elements.size).apply { addAll(elements) }

private fun mavenCaches(): List<Path> {
    val userHome = System.getProperty("user.home")
    return listOfNotNull(
        System.getenv("AMPER_SHARED_CACHES_ROOT"),
        System.getenv("KOTLIN_SHARED_CACHES_ROOT"),
        userHome?.let { "$it/.cache/JetBrains/Amper" },
        userHome?.let { "$it/.cache/JetBrains/Kotlin" },
        userHome?.let { "$it/Library/Caches/JetBrains/Amper" },
        userHome?.let { "$it/Library/Caches/JetBrains/Kotlin" },
        "/opt/shilling-ci/amper-cache",
    )
        .distinct()
        .map { Path.of(it).resolve(".m2.cache") }
        .filter { it.exists() }
}
