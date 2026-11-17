package finance.shilling.shared.data.backup

import finance.shilling.core.backup.*
import finance.shilling.shared.data.store.backup.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

class BackupSecurityTest {
    @Test fun authenticatedEncryptionBindsHouseholdAndRejectsWrongKeyOrTampering() = runBlocking {
        val crypto = BackupCrypto()
        val key = crypto.newKey()
        assertEquals(32, Base64.decode(key).size)
        val encrypted = crypto.encrypt(key, "house-a/snapshot/0", "private budget".encodeToByteArray())
        assertEquals("private budget", crypto.decrypt(key, "house-a/snapshot/0", encrypted).decodeToString())
        assertFails { crypto.decrypt(key, "house-b/snapshot/0", encrypted) }
        assertFails { crypto.decrypt(crypto.newKey(), "house-a/snapshot/0", encrypted) }
        val changed = Base64.decode(encrypted).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFails { crypto.decrypt(key, "house-a/snapshot/0", Base64.encode(changed)) }
        Unit
    }
    @Test fun ciphertextCommitIsAtomicIdempotentDurableAndHouseholdScoped() = runBlocking {
        val root = Files.createTempDirectory("backup-store")
        val store = EncryptedBackupRepository(root, "house-a")
        val crypto = BackupCrypto()
        val key = crypto.newKey()
        val id = Uuid.random().toString()
        suspend fun request(op: BackupRequest.Operation, index: Int = 0, data: String = "") = store.handle(BackupRequest(Uuid.random().toString(), op, id, index, 2, data))
        assertNull(request(BackupRequest.Operation.BEGIN).error)
        val part = crypto.encrypt(key, "context", "private budget".encodeToByteArray())
        assertNull(request(BackupRequest.Operation.PUT, 0, part).error)
        assertNotNull(request(BackupRequest.Operation.COMMIT).error)
        assertTrue(request(BackupRequest.Operation.LIST).backups.isEmpty())
        assertNull(request(BackupRequest.Operation.PUT, 0, part).error)
        assertNotNull(request(BackupRequest.Operation.PUT, 0, crypto.encrypt(key, "context", byteArrayOf(1))).error)
        assertNull(request(BackupRequest.Operation.PUT, 1, part).error)
        assertNull(request(BackupRequest.Operation.COMMIT).error)
        assertNull(request(BackupRequest.Operation.COMMIT).error)
        val reopened = EncryptedBackupRepository(root, "house-a")
        assertEquals(id, reopened.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.LIST)).backups.single().id)
        assertEquals(part, reopened.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.GET, id, 0)).chunk)
        assertNotNull(EncryptedBackupRepository(root, "house-b").handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.GET, id, 0)).error)
        Unit
    }
    @Test fun trustedCloudEncryptsAtRestAndOlderBackupsRetainTheirMode() = runBlocking {
        val root = Files.createTempDirectory("trusted-store")
        var mode = BackupEncryption.TRUSTED_CLOUD
        val masterKey = ByteArray(32) { it.toByte() }
        val store = EncryptedBackupRepository(root, "household", masterKey) { mode }
        val id = Uuid.random().toString()
        val plaintext = Base64.encode("my private receipt".encodeToByteArray())
        suspend fun request(op: BackupRequest.Operation, data: String = "") = store.handle(BackupRequest(Uuid.random().toString(), op, id, 0, 1, data, mode))
        assertNull(request(BackupRequest.Operation.BEGIN).error)
        assertNull(request(BackupRequest.Operation.PUT, plaintext).error)
        assertNull(request(BackupRequest.Operation.PUT, plaintext).error)
        assertNull(request(BackupRequest.Operation.COMMIT).error)
        val database = Files.list(root).use { it.findFirst().get() }
        DriverManager.getConnection("jdbc:sqlite:$database").use { db ->
            db.createStatement().executeQuery("SELECT ciphertext FROM cloud_chunks").use { rows -> rows.next(); assertNotEquals(plaintext, Base64.encode(rows.getBytes(1))) }
        }
        mode = BackupEncryption.END_TO_END
        assertEquals(plaintext, request(BackupRequest.Operation.GET).chunk)
        assertEquals(BackupEncryption.TRUSTED_CLOUD, request(BackupRequest.Operation.LIST).backups.single().encryption)
        assertNotNull(request(BackupRequest.Operation.BEGIN).error)
        Unit
    }
    @Test fun recoveryKeyRequiresApprovalAndAcknowledgementAfterDurableStorage() = runBlocking {
        val sourceVault = TestVault()
        val targetVault = TestVault()
        val sourceKeys = RecoveryKeyRepository(sourceVault, "house")
        val targetKeys = RecoveryKeyRepository(targetVault, "house")
        val key = sourceKeys.generate()
        val sourceOut = mutableListOf<String>()
        val targetOut = mutableListOf<String>()
        val source = RecoveryKeySync("house", "source", sourceKeys) { _, text -> sourceOut += text }
        val target = RecoveryKeySync("house", "target", targetKeys) { _, text -> targetOut += text }
        target.join(source.invitation())
        target.tick()
        source.receive("target", targetOut.single())
        source.tick()
        assertTrue(sourceOut.isEmpty())
        assertNull(targetKeys.activeKey())
        source.approve("target")
        source.tick()
        assertEquals(KeySyncStatus.PENDING, source.status.value["target"])
        targetVault.failWrites = true
        assertFails { target.receive("source", sourceOut.single()) }
        assertEquals(KeySyncStatus.PENDING, source.status.value["target"])
        targetVault.failWrites = false
        val ack = target.receive("source", sourceOut.single())!!
        assertEquals(key, targetKeys.activeKey())
        source.receive("target", ack)
        assertEquals(KeySyncStatus.SYNCED, source.status.value["target"])
        source.revoke("target")
        sourceOut.clear(); source.tick()
        assertTrue(sourceOut.isEmpty())
    }
    @Test fun repeatedAndConcurrentSetupReusesOneDurableKey(): Unit = runBlocking {
        val vault = TestVault()
        val keys = RecoveryKeyRepository(vault, "house")
        val generated = (1..8).map { async { keys.generate() } }.awaitAll()
        assertEquals(1, generated.toSet().size)
        assertFalse(keys.saved())
        keys.confirmSaved()
        val reopened = RecoveryKeyRepository(vault, "house")
        assertEquals(generated.first(), reopened.generate())
        assertTrue(reopened.saved())
    }

    private class TestVault : BackupKeyVault {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        override suspend fun read(namespace: String) = values[namespace]
        override suspend fun write(namespace: String, secret: String) { check(!failWrites); values[namespace] = secret }
        override suspend fun delete(namespace: String) { values.remove(namespace) }
    }
}
