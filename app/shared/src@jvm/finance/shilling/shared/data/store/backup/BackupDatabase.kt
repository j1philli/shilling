package finance.shilling.shared.data.store.backup

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import finance.shilling.core.auth.AuthMode
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** One database provider per worker, shared by all household Store5 repositories. */
abstract class BackupDatabase : AutoCloseable {
    internal abstract fun open(household: String): Connection
    internal open val prefix = ""
    open val objects: CloudObjectRepository? = null
    internal open fun lock(db: Connection, household: String) = Unit
    open fun checkReady() = Unit
    override fun close() = Unit

    companion object {
        fun storageMode(authMode: AuthMode, configured: String?): String {
            val mode = configured?.takeUnless { it == "auto" } ?: if (authMode == AuthMode.SUPABASE) "supabase" else "sqlite"
            require(mode in setOf("sqlite", "supabase")) { "Backup storage must be auto, sqlite, or supabase" }
            require(authMode != AuthMode.SUPABASE || mode == "supabase") { "Hosted backups require Supabase storage" }
            return mode
        }

        fun sqlite(root: Path): BackupDatabase = object : BackupDatabase() {
            init { Files.createDirectories(root); require(Files.isWritable(root)) }
            override fun open(household: String): Connection {
                val name = MessageDigest.getInstance("SHA-256").digest(household.toByteArray()).joinToString("") { "%02x".format(it) }
                val db = DriverManager.getConnection("jdbc:sqlite:${root.resolve("$name.sqlite")}")
                try {
                    db.createStatement().use { s ->
                        s.execute("PRAGMA busy_timeout=5000")
                        s.execute("PRAGMA foreign_keys=ON")
                        s.execute("CREATE TABLE IF NOT EXISTS cloud_entries (household TEXT NOT NULL, id TEXT PRIMARY KEY, chunks INTEGER NOT NULL, created INTEGER NOT NULL, complete INTEGER NOT NULL DEFAULT 0, mode TEXT NOT NULL, key_id TEXT NOT NULL, sequence BIGINT NOT NULL DEFAULT 0, kind TEXT NOT NULL DEFAULT 'DELTA', refs TEXT NOT NULL DEFAULT '[]', base_sequence BIGINT NOT NULL DEFAULT 0)")
                        s.execute("CREATE TABLE IF NOT EXISTS cloud_chunks (household TEXT NOT NULL, snapshot TEXT NOT NULL REFERENCES cloud_entries(id) ON DELETE CASCADE, idx INTEGER NOT NULL, ciphertext BLOB NOT NULL, PRIMARY KEY(snapshot,idx))")
                        s.execute("CREATE TABLE IF NOT EXISTS cloud_commands (household TEXT NOT NULL, id TEXT NOT NULL, expires BIGINT NOT NULL, ciphertext BLOB NOT NULL, PRIMARY KEY(household,id))")
                        s.execute("CREATE TABLE IF NOT EXISTS cloud_gc (household TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(household,id))")
                        s.execute("CREATE INDEX IF NOT EXISTS cloud_cursor ON cloud_entries(household,sequence)")
                    }
                    return db
                } catch (e: Exception) { db.close(); throw e }
            }
        }

        fun supabase(url: String, user: String, password: String, poolSize: Int = 8, objectStorage: CloudObjectStorage? = null): BackupDatabase {
            require(url.startsWith("jdbc:postgresql://")) { "Supabase requires a PostgreSQL JDBC URL" }
            val uri = URI(url.removePrefix("jdbc:"))
            require(uri.userInfo == null && uri.host != null) { "Use separate database user/password settings" }
            val parameters = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') }
            require(parameters.distinct().size == parameters.size && parameters.all { it in setOf("sslmode", "sslrootcert") }) { "Only sslmode and sslrootcert are allowed in the database URL" }
            val local = uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            require(local || uri.rawQuery.orEmpty().split('&').contains("sslmode=verify-full")) { "Remote Supabase connections require sslmode=verify-full" }
            require(poolSize in 1..64 && user.isNotBlank() && password.isNotBlank())
            val pool = HikariDataSource(HikariConfig().apply {
                jdbcUrl = url; username = user; this.password = password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = poolSize; minimumIdle = 0
                connectionTimeout = 5_000; validationTimeout = 3_000; initializationFailTimeout = -1
                poolName = "backup-storage"
                addDataSourceProperty("prepareThreshold", "0") // Supavisor transaction pooling compatibility.
                addDataSourceProperty("connectTimeout", "5")
                addDataSourceProperty("socketTimeout", "15")
                addDataSourceProperty("tcpKeepAlive", "true")
            })
            return object : BackupDatabase() {
                override val prefix = "private_backup."
                override val objects = objectStorage?.let(::CloudObjectRepository)
                override fun open(household: String): Connection = pool.connection
                override fun lock(db: Connection, household: String) {
                    db.createStatement().use { it.execute("SET LOCAL statement_timeout = '10s'") }
                    db.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                        it.setString(1, "shilling-backup:$household"); it.execute()
                    }
                }
                override fun checkReady() {
                    objects?.checkReady()
                    pool.connection.use { db -> db.createStatement().use { s ->
                        s.executeQuery("SELECT ciphertext FROM private_backup.cloud_chunks LIMIT 0").close()
                        s.executeQuery("SELECT sequence FROM private_backup.cloud_entries LIMIT 0").close()
                    } }
                }
                override fun close() = pool.close()
            }
        }
    }
}
