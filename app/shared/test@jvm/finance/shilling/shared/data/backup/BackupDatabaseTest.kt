package finance.shilling.shared.data.backup

import finance.shilling.core.auth.AuthMode
import finance.shilling.core.backup.*
import finance.shilling.shared.data.store.backup.*
import kotlinx.coroutines.*
import java.sql.DriverManager
import kotlin.test.*
import kotlin.uuid.Uuid

class BackupDatabaseTest {
    @Test fun hostedStorageIsMandatoryAndRemoteTlsIsVerified() {
        assertEquals("supabase", BackupDatabase.storageMode(AuthMode.SUPABASE, null))
        assertEquals("supabase", BackupDatabase.storageMode(AuthMode.SUPABASE, "auto"))
        assertEquals("sqlite", BackupDatabase.storageMode(AuthMode.NONE, null))
        assertFails { BackupDatabase.storageMode(AuthMode.SUPABASE, "sqlite") }
        assertFails { BackupDatabase.storageMode(AuthMode.NONE, "unknown") }
        assertFails { BackupDatabase.supabase("jdbc:postgresql://example.com/postgres", "worker", "secret") }
        assertFails { BackupDatabase.supabase("jdbc:postgresql://example.com/postgres?sslmode=require", "worker", "secret") }
        assertFails { BackupDatabase.supabase("jdbc:postgresql://example.com/postgres?sslmode=verify-full&sslfactory=bad", "worker", "secret") }
        assertFails { BackupDatabase.supabase("jdbc:postgresql://example.com/postgres?sslmode=verify-full&sslmode=disable", "worker", "secret") }
    }

    @Test fun postgresIsolationConcurrencyAndPermissions(): Unit = runBlocking {
        val url = System.getenv("SHILLING_TEST_BACKUP_DATABASE_URL")
        org.junit.Assume.assumeTrue("Run scripts/test-supabase-backup.sh", url != null)
        val user = System.getenv("SHILLING_TEST_BACKUP_DATABASE_USER")!!
        val password = System.getenv("SHILLING_TEST_BACKUP_DATABASE_PASSWORD")!!
        BackupDatabase.supabase(url!!, user, password, 2).use { database ->
            database.checkReady()
            val household = Uuid.random().toString()
            val first = EncryptedBackupRepository(database, household)
            val second = EncryptedBackupRepository(database, household)
            val other = EncryptedBackupRepository(database, Uuid.random().toString())
            val id = Uuid.random().toString()
            // Identical object IDs in separate households must coexist without exposing either chunk.
            assertNull(first.handle(request(BackupRequest.Operation.BEGIN, id)).error)
            assertNull(other.handle(request(BackupRequest.Operation.BEGIN, id)).error)
            val a = java.util.Base64.getEncoder().encodeToString(ByteArray(40) { 1 })
            val b = java.util.Base64.getEncoder().encodeToString(ByteArray(40) { 2 })
            val results = awaitAll(async { first.handle(request(BackupRequest.Operation.PUT, id, a)) },
                async { second.handle(request(BackupRequest.Operation.PUT, id, b)) })
            assertEquals(1, results.count { it.error == null })
            val stored = if (results[0].error == null) a else b
            assertNull(first.handle(request(BackupRequest.Operation.COMMIT, id)).error)
            assertTrue(other.handle(request(BackupRequest.Operation.LIST)).backups.isEmpty())
            assertNotNull(other.handle(request(BackupRequest.Operation.GET, id)).error)
            assertNull(other.handle(request(BackupRequest.Operation.PUT, id, b)).error)
            assertNull(other.handle(request(BackupRequest.Operation.COMMIT, id)).error)
            assertEquals(stored, second.handle(request(BackupRequest.Operation.GET, id)).chunk)
            // Separate repositories cannot race around the two-staging-upload limit.
            val staging = (1..8).map { async { EncryptedBackupRepository(database, household).handle(request(BackupRequest.Operation.BEGIN, Uuid.random().toString())) } }.awaitAll()
            assertEquals(2, staging.count { it.error == null })
            val cloudHousehold = Uuid.random().toString()
            val cloudWriters = List(2) { EncryptedBackupRepository(database, cloudHousehold) }
            val ids = List(2) { Uuid.random().toString() }
            cloudWriters.forEachIndexed { index, writer ->
                assertNull(writer.handle(request(BackupRequest.Operation.BEGIN, ids[index])).error)
                assertNull(writer.handle(request(BackupRequest.Operation.PUT, ids[index], a)).error)
            }
            val commits = cloudWriters.mapIndexed { index, writer -> async {
                writer.handle(request(BackupRequest.Operation.COMMIT, ids[index]))
            } }.awaitAll()
            assertTrue(commits.all { it.error == null })
            assertNull(cloudWriters[0].handle(request(BackupRequest.Operation.COMMIT, ids[0])).error)
            val stream = cloudWriters[0].handle(request(BackupRequest.Operation.LIST)).backups
            assertEquals(listOf(1L, 2L), stream.map { it.sequence })
            assertEquals(ids.toSet(), stream.map { it.id }.toSet())
            assertEquals(1, cloudWriters[1].handle(request(BackupRequest.Operation.LIST).copy(cursor = 1)).backups.size)
            DriverManager.getConnection(url, user, password).use { db ->
                for (role in listOf("anon", "authenticated", "service_role")) {
                    db.prepareStatement("SELECT has_table_privilege(?, 'private_backup.cloud_chunks', 'SELECT')").use {
                        it.setString(1, role); it.executeQuery().use { r -> assertTrue(r.next()); assertFalse(r.getBoolean(1)) }
                    }
                }
                assertFails { db.createStatement().use { it.execute("CREATE TABLE private_backup.forbidden(id integer)") } }
                assertFails { db.createStatement().use { it.execute("SET ROLE postgres") } }
            }
        }
    }
    private fun request(op: BackupRequest.Operation, id: String = Uuid.random().toString(), chunk: String = "") =
        BackupRequest(Uuid.random().toString(), op, id, chunks = 1, chunk = chunk)
}
