@file:OptIn(org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)
package finance.shilling.shared.data.backup

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.core.backup.*
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.store.backup.*
import finance.shilling.shared.data.store.cloud.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.uuid.Uuid
import org.mobilenativefoundation.store.store5.*

class CloudSyncTest {
    @Test fun durableCloudStreamMergesOfflineChangesDeletesAndRetriesBothModes(): Unit = runBlocking {
        for (mode in BackupEncryption.entries) {
            val root = Files.createTempDirectory("cloud-stream")
            BackupDatabase.sqlite(root).use { database ->
                val serviceKey = ByteArray(32) { 9 }
                var worker = EncryptedBackupRepository(database, "house", serviceKey) { mode }
                val crypto = BackupCrypto(); val key = if (mode == BackupEncryption.END_TO_END) crypto.newKey() else null
                val policy = BackupPolicy(enabled = true, available = true, encryption = mode)
                val a = Budget("a"); val b = Budget("b")
                a.init(); b.init()
                try {
                    a.accounts.upsert(Account("shared", "From A", 100.0))
                    b.accounts.upsert(Account("only-b", "Offline B", 9.0))
                    val upload = a.cloud.prepare("cloud", "house", "a", policy, key)!!
                    suspend fun send(upload: CloudUpload) {
                        suspend fun request(op: BackupRequest.Operation, index: Int = 0, chunk: String = "") =
                            worker.handle(BackupRequest(Uuid.random().toString(), op, upload.id, index, upload.chunks.size, chunk, mode, upload.keyId))
                        assertNull(request(BackupRequest.Operation.BEGIN).error)
                        upload.chunks.forEachIndexed { i, chunk -> assertNull(request(BackupRequest.Operation.PUT, i, chunk).error) }
                        assertNull(request(BackupRequest.Operation.COMMIT).error)
                    }
                    send(upload)
                    // Crash after server commit but before local ACK. Durable ciphertext is replayed verbatim.
                    val restarted = CloudSyncRepository(a.db, a.facade, a.files, a.notifier)
                    assertEquals(upload, restarted.prepare("cloud", "house", "a", policy, key))
                    worker = EncryptedBackupRepository(database, "house", serviceKey) { mode }
                    send(upload)
                    restarted.uploaded("cloud", upload.id)
                    assertNull(restarted.prepare("cloud", "house", "a", policy, key))
                    suspend fun pull(target: Budget) {
                        val list = worker.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.LIST, cursor = target.cloud.cursor("cloud")))
                        assertNull(list.error)
                        for (info in list.backups) {
                            val bytes = mutableListOf<Byte>()
                            for (i in 0 until info.chunks) {
                                val chunk = worker.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.GET, info.id, i)).chunk!!
                                bytes += (if (key == null) Base64.decode(chunk) else crypto.decrypt(key, cloudContext("house", info.id, info.chunks, i), chunk)).toList()
                            }
                            target.cloud.apply("cloud", "house", target.device, info.sequence, bytes.toByteArray())
                        }
                    }
                    pull(b)
                    assertEquals(setOf("shared", "only-b"), b.db.accountQueries.selectAll("__local__").awaitAsList().map { it.id }.toSet())
                    val bUpload = b.cloud.prepare("cloud", "house", "b", policy, key)!!
                    send(bUpload); b.cloud.uploaded("cloud", bUpload.id); pull(a)
                    assertEquals(2, a.db.accountQueries.selectAll("__local__").awaitAsList().size)
                    a.accounts.delete("shared")
                    val deletion = a.cloud.prepare("cloud", "house", "a", policy, key)!!
                    send(deletion); a.cloud.uploaded("cloud", deletion.id); pull(b)
                    assertEquals(listOf("only-b"), b.db.accountQueries.selectAll("__local__").awaitAsList().map { it.id })
                    repeat(4) { version ->
                        a.accounts.upsert(Account("extra", "Version $version", version.toDouble()))
                        val next = a.cloud.prepare("cloud", "house", "a", policy, key)!!
                        send(next); a.cloud.uploaded("cloud", next.id)
                    }
                    val entries = worker.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.LIST)).backups
                    assertTrue(entries.size > 3) // Sync history must not use backup retention.
                    assertEquals((1L..entries.size.toLong()).toList(), entries.map { it.sequence })
                    assertNotNull(EncryptedBackupRepository(database, "other", serviceKey) { mode }
                        .handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.GET, upload.id)).error)
                } finally { a.close(); b.close() }
            }
        }
    }

    @Test fun invalidOrUnauthenticatedBatchesNeverAdvanceCheckpoint(): Unit = runBlocking {
        val b = Budget("b"); b.init()
        try {
            val change = ChangeMessage("change", EntityType.ACCOUNT, ChangeOp.UPSERT, "wrong-id", 1, "a", ChangePayload.AccountPayload(Account("account", "Bad", 1.0)))
            val bytes = Json.encodeToString(CloudDelta(household = "house", changes = listOf(change), files = emptyList())).encodeToByteArray()
            assertFails { b.cloud.apply("cloud", "house", "b", 1, bytes) { byteArrayOf(1, 2, 3) } }
            assertEquals(0L, b.cloud.cursor("cloud"))
            assertTrue(b.db.accountQueries.selectAll("__local__").awaitAsList().isEmpty())
        } finally { b.close() }
    }

    @Test fun mergePreservesNewerEditsAndRecoversMissingReceiptBytes(): Unit = runBlocking {
        val b = Budget("b"); b.init()
        try {
            val local = ChangeMessage("newer", EntityType.ACCOUNT, ChangeOp.UPSERT, "shared", 200, "b",
                ChangePayload.AccountPayload(Account("shared", "Keep my newer edit", 99.0)))
            b.facade.applyIncomingChange(local)
            val old = local.copy(id = "older", timestamp = 100, deviceId = "a", payload = ChangePayload.AccountPayload(Account("shared", "Old", 1.0)))
            val receipt = Receipt("receipt-1", null, "receipts/receipt-1.jpg", "receipt.jpg", 1)
            val attachment = ChangeMessage("receipt-change", EntityType.RECEIPT, ChangeOp.UPSERT, receipt.id, 100, "a", ChangePayload.ReceiptPayload(receipt))
            b.facade.applyIncomingChange(attachment)
            val delta = CloudDelta(household = "house", changes = listOf(old, attachment),
                files = listOf(CloudFile(receipt.id, receipt.originalName, BackupInfo(Uuid.random().toString(), 1, 0, kind = CloudObjectKind.RECEIPT), BackupCrypto().digest(byteArrayOf(1, 2, 3)), 3)))
            val bytes = Json.encodeToString(delta).encodeToByteArray()
            assertFails { b.cloud.apply("cloud", "house", "b", 2, bytes) }
            assertEquals(0L, b.cloud.cursor("cloud"))
            b.cloud.apply("cloud", "house", "b", 1, bytes) { byteArrayOf(1, 2, 3) }
            assertEquals("Keep my newer edit", b.db.accountQueries.selectAll("__local__").awaitAsList().single().name)
            assertContentEquals(byteArrayOf(1, 2, 3), b.files.read(receipt.id))
            assertEquals(1L, b.cloud.cursor("cloud"))
        } finally { b.close() }
    }

    @Test fun trustedCloudRequiresConsentToTheCurrentPolicy(): Unit = runBlocking {
        val vault = object : BackupKeyVault {
            var data: String? = null
            override suspend fun read(namespace: String) = data
            override suspend fun write(namespace: String, secret: String) { data = secret }
            override suspend fun delete(namespace: String) { data = null }
        }
        val b = Budget("b"); b.init()
        try {
            val keys = RecoveryKeyRepository(vault, "house")
            val session = CloudSyncSession(b.cloud, keys, "cloud", "house", "b")
            val policy = BackupPolicy(enabled = true, available = true, encryption = BackupEncryption.TRUSTED_CLOUD, encryptionRevision = 7)
            // An unconnected peer proves rejected consent never reaches the transport.
            assertFailsWith<IllegalArgumentException> { session.sync(policy, BackupPeerClient()) }
            keys.consentToTrustedCloud(6)
            assertFailsWith<IllegalArgumentException> { session.sync(policy, BackupPeerClient()) }
            keys.consentToTrustedCloud(7)
            assertEquals(7L, RecoveryKeyRepository(vault, "house").cloudConsentRevision())
            keys.consentToTrustedCloud(null)
            assertNull(RecoveryKeyRepository(vault, "house").cloudConsentRevision())
        } finally { b.close() }
    }

    @Test fun failedStoreWriteDoesNotAcknowledgeIncomingChange(): Unit = runBlocking {
        val b = Budget("b"); b.init()
        try {
            val delegate = createAccountStore(b.db)
            val failing = AccountStore(object : MutableStore<AccountKey, List<Account>> by delegate {
                override suspend fun <Response : Any> write(request: StoreWriteRequest<AccountKey, List<Account>, Response>): StoreWriteResponse =
                    StoreWriteResponse.Error.Message("Synthetic write failure")
            })
            val repository = CloudSyncRepository(b.db, SyncStoreFacade(b.db, accountStore = failing), b.files, b.notifier)
            val change = ChangeMessage("new", EntityType.ACCOUNT, ChangeOp.UPSERT, "account", 1, "a", ChangePayload.AccountPayload(Account("account", "Retry me", 1.0)))
            val bytes = Json.encodeToString(CloudDelta(household = "house", changes = listOf(change), files = emptyList())).encodeToByteArray()
            assertFails { repository.apply("cloud", "house", "b", 1, bytes) }
            assertEquals(0L, repository.cursor("cloud"))
            assertTrue(b.db.accountQueries.selectAll("__local__").awaitAsList().isEmpty())
            b.cloud.apply("cloud", "house", "b", 1, bytes) { byteArrayOf(1, 2, 3) }
            assertEquals("Retry me", b.db.accountQueries.selectAll("__local__").awaitAsList().single().name)
            assertEquals(1L, b.cloud.cursor("cloud"))
        } finally { b.close() }
    }

    internal class Budget(val device: String) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = ShillingDatabase(driver)
        val notifier = ChangeNotifier()
        val deps = StoreSyncDeps(db, null, device, object : IdGenerator { override fun newId() = Uuid.random().toString() })
        val facade = SyncStoreFacade(db)
        val accounts = AccountRepository(notifier, createAccountStore(db, deps), deps)
        val files = object : ReceiptFileStore {
            val values = mutableMapOf<String, ByteArray>()
            override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) { values[receiptId] = bytes.copyOf() }
            override suspend fun read(receiptId: String) = values[receiptId]
            override suspend fun openReader(receiptId: String): finance.shilling.shared.data.ReceiptFileReader? {
            val bytes = read(receiptId)?.copyOf() ?: return null
            return object : finance.shilling.shared.data.ReceiptFileReader {
                override val size = bytes.size.toLong()
                override suspend fun readRange(offset: Long, byteCount: Int) =
                    finance.shilling.shared.data.ReceiptFileBlock(bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + byteCount)))
            }
        }
        override suspend fun hasFile(receiptId: String) = receiptId in values
            override suspend fun delete(receiptId: String) { values.remove(receiptId) }
            override suspend fun clearAll() { values.clear() }
            override suspend fun openExternally(receiptId: String, originalName: String) = Unit
        }
        val cloud = CloudSyncRepository(db, facade, files, notifier)
        suspend fun init() { ShillingDatabase.Schema.create(driver).await() }
        fun close() = driver.close()
    }
}
