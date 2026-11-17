package finance.shilling.shared.data.backup

import finance.shilling.core.backup.*
import finance.shilling.shared.data.store.backup.*
import kotlinx.coroutines.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.uuid.Uuid

class HostedStorageTest {
    @Test fun realStoragePublicationPrivateAccessAndBinaryPersistence(): Unit = runBlocking {
        val url = System.getenv("SHILLING_TEST_BACKUP_DATABASE_URL")
        org.junit.Assume.assumeTrue("Run scripts/test-supabase-backup.sh", url != null)
        val endpoint = System.getenv("SHILLING_TEST_STORAGE_URL")!!
        val token = System.getenv("SHILLING_TEST_STORAGE_KEY")!!
        val user = System.getenv("SHILLING_TEST_BACKUP_DATABASE_USER")!!
        val password = System.getenv("SHILLING_TEST_BACKUP_DATABASE_PASSWORD")!!
        val storage = SupabaseObjectStorage(endpoint, token)
        storage.checkReady()
        BackupDatabase.supabase(url!!, user, password, 2, storage).use { db ->
            for (mode in BackupEncryption.entries) {
                val household = Uuid.random().toString()
                val worker = EncryptedBackupRepository(db, household, ByteArray(32) { 5 }) { mode }
                val id = Uuid.random().toString()
                val plain = ByteArray(40_000) { (it % 199).toByte() }
                val crypto = BackupCrypto(); val key = crypto.newKey()
                val chunks = plain.asList().chunked(BACKUP_CHUNK_BYTES).mapIndexed { index, bytes ->
                    if (mode == BackupEncryption.END_TO_END) crypto.encrypt(key, "test-$index", bytes.toByteArray()) else Base64.encode(bytes.toByteArray())
                }
                suspend fun call(op: BackupRequest.Operation, index: Int = 0, chunk: String = "") = worker.handle(BackupRequest(Uuid.random().toString(), op, id, index, chunks.size, chunk, mode, kind = CloudObjectKind.RECEIPT))
                assertNull(call(BackupRequest.Operation.BEGIN).error)
                chunks.forEachIndexed { index, chunk -> assertNull(call(BackupRequest.Operation.PUT, index, chunk).error) }
                assertNull(call(BackupRequest.Operation.COMMIT).error)
                assertNull(call(BackupRequest.Operation.COMMIT).error)
                val path = MessageDigest.getInstance("SHA-256").digest(household.toByteArray()).joinToString("") { "%02x".format(it) } + "/" + id
                val persisted = storage.read(path)!!
                assertFalse(persisted.contentEquals(plain))
                chunks.forEachIndexed { index, chunk -> assertEquals(chunk, call(BackupRequest.Operation.GET, index).chunk) }
                DriverManager.getConnection(url, user, password).use { sql -> sql.prepareStatement("SELECT count(*) FROM private_backup.cloud_chunks WHERE household=?").use {
                    it.setString(1, household); it.executeQuery().use { r -> r.next(); assertEquals(0, r.getInt(1)) }
                } }
                val public = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("$endpoint/storage/v1/object/public/cloud-peer/$path")).GET().build(), HttpResponse.BodyHandlers.discarding())
                assertTrue(public.statusCode() !in 200..299, "Private ciphertext must not be publicly downloadable")
                suspend fun publish(kind: CloudObjectKind, base: Long = 0): String {
                    val objectId = Uuid.random().toString()
                    val request = BackupRequest(Uuid.random().toString(), BackupRequest.Operation.BEGIN, objectId, chunks = 1, encryption = mode,
                        kind = kind, references = listOf(id), baseSequence = base)
                    assertNull(worker.handle(request).error)
                    assertNull(worker.handle(request.copy(operation = BackupRequest.Operation.PUT, chunk = chunks.first())).error)
                    assertNull(worker.handle(request.copy(operation = BackupRequest.Operation.COMMIT)).error)
                    return objectId
                }
                publish(CloudObjectKind.DELTA)
                val checkpointId = publish(CloudObjectKind.CHECKPOINT, 1)
                val page = worker.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.LIST))
                assertEquals(checkpointId, page.checkpoint!!.id)
                assertTrue(page.backups.isEmpty())
                assertEquals(1L, page.headSequence)
                // Client JWTs cannot bypass the private bucket through its authenticated endpoint either.
                fun encoded(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                val payload = encoded("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".toByteArray()) + "." + encoded(
                    "{\"role\":\"authenticated\",\"sub\":\"${Uuid.random()}\",\"exp\":${System.currentTimeMillis()/1000+300}}".toByteArray())
                val signature = javax.crypto.Mac.getInstance("HmacSHA256").apply {
                    init(javax.crypto.spec.SecretKeySpec(System.getenv("SHILLING_TEST_JWT_SECRET").toByteArray(), "HmacSHA256"))
                }.doFinal(payload.toByteArray())
                val clientResponse = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("$endpoint/storage/v1/object/authenticated/cloud-peer/$path"))
                    .header("Authorization", "Bearer $payload.${encoded(signature)}").GET().build(), HttpResponse.BodyHandlers.discarding())
                assertTrue(clientResponse.statusCode() !in 200..299)
                storage.delete(path)
            }
        }
    }
    @Test fun hostedPoliciesShareStateAndSerializeCapacityAcrossProcesses(): Unit = runBlocking {
        val url = System.getenv("SHILLING_TEST_BACKUP_DATABASE_URL")
        org.junit.Assume.assumeTrue("Run scripts/test-supabase-backup.sh", url != null)
        val password = System.getenv("SHILLING_TEST_BACKUP_DATABASE_PASSWORD")!!
        val worker = Uuid.random().toString()
        val a = CloudPolicyRepository(BackupDatabase.supabase(url!!, "shilling_cloud_control", password, 2), listOf(worker), true, 1)
        val b = CloudPolicyRepository(BackupDatabase.supabase(url, "shilling_cloud_control", password, 2), listOf(worker), true, 1)
        try {
            a.checkReady(); b.checkReady()
            val households = List(2) { Uuid.random().toString() }
            val outcomes = listOf(a,b).mapIndexed { index, repo -> async(Dispatchers.IO) {
                runCatching { repo.update(households[index], BackupPolicyUpdate(true, false, 0, BackupEncryption.TRUSTED_CLOUD, TRUSTED_CLOUD_CONSENT), true, "actor") }
            } }.awaitAll()
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(a.assignments(worker), b.assignments(worker))
            val household = a.assignments(worker).single().householdId
            val policy = b.get(household, true)
            assertEquals("actor", policy.trustedConsentBy)
            assertTrue(policy.turnEnabled)
            assertFails { b.update(household, BackupPolicyUpdate(false, false, 0), true, "actor") }
            a.update(household, BackupPolicyUpdate(false, false, policy.revision, policy.encryption), true, "actor")
            assertFalse(b.get(household, true).enabled)
            DriverManager.getConnection(url, "shilling_cloud_control", password).use { sql ->
                assertFails { sql.createStatement().executeQuery("SELECT * FROM private_backup.cloud_entries") }
                assertFails { sql.createStatement().executeQuery("SELECT * FROM private_backup.cloud_chunks") }
            }
            DriverManager.getConnection(url, "shilling_backup_worker", password).use { sql ->
                assertFails { sql.createStatement().executeQuery("SELECT * FROM private_backup.cloud_policies") }
            }
        } finally { a.close(); b.close() }
    }
}
