package finance.shilling.shared.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsList
import finance.shilling.core.backup.*
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.store.backup.*
import finance.shilling.shared.data.store.cloud.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.Connection
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.uuid.Uuid

class CloudObjectTest {
    private class MemoryObjects : CloudObjectStorage {
        val values = mutableMapOf<String, ByteArray>()
        var fail = false
        var failDeletes = false
        override fun read(path: String) = values[path]?.copyOf()
        override fun write(path: String, bytes: ByteArray) { check(!fail); values[path]?.let { check(it.contentEquals(bytes)) }; values[path] = bytes.copyOf() }
        override fun delete(path: String) { check(!failDeletes); values.remove(path) }
    }
    @Test fun receiptReuseCheckpointsOfflineMergeAndPublicationFailure(): Unit = runBlocking {
        for (mode in BackupEncryption.entries) {
            val objects = MemoryObjects()
            val local = BackupDatabase.sqlite(Files.createTempDirectory("cloud-objects"))
            val database = object : BackupDatabase() {
                override val objects = CloudObjectRepository(objects)
                override fun open(household: String): Connection = local.open(household)
            }
            val worker = EncryptedBackupRepository(database, "house", ByteArray(32) { 7 }) { mode }
            val source = CloudSyncTest.Budget("source"); val target = CloudSyncTest.Budget("target")
            source.init(); target.init()
            val crypto = BackupCrypto(); val key = if (mode == BackupEncryption.END_TO_END) crypto.newKey() else null
            val policy = BackupPolicy(enabled = true, available = true, encryption = mode)
            suspend fun call(op: BackupRequest.Operation, id: String = "", index: Int = 0, chunk: String = "", upload: CloudUpload? = null, blob: CloudObjectUpload? = null, cursor: Long = 0): BackupResponse = worker.handle(BackupRequest(
                Uuid.random().toString(), op, id, index, blob?.chunks?.size ?: upload?.chunks?.size ?: 0, chunk, mode,
                blob?.info?.keyId ?: upload?.keyId ?: "", cursor, blob?.info?.kind ?: upload?.kind ?: CloudObjectKind.DELTA,
                upload?.files?.map { it.objectInfo.id }?.distinct() ?: emptyList(), upload?.baseSequence ?: 0))
            suspend fun publish(upload: CloudUpload) {
                for (blob in upload.objects) {
                    if (call(BackupRequest.Operation.STAT, blob.info.id).backups.isNotEmpty()) continue
                    assertNull(call(BackupRequest.Operation.BEGIN, blob.info.id, blob = blob).error)
                    blob.chunks.forEachIndexed { i, part -> assertNull(call(BackupRequest.Operation.PUT, blob.info.id, i, part).error) }
                    assertNull(call(BackupRequest.Operation.COMMIT, blob.info.id).error)
                }
                assertNull(call(BackupRequest.Operation.BEGIN, upload.id, upload = upload).error)
                upload.chunks.forEachIndexed { i, part -> assertNull(call(BackupRequest.Operation.PUT, upload.id, i, part).error) }
            }
            suspend fun download(info: BackupInfo): ByteArray {
                val parts = (0 until info.chunks).map { index ->
                    val reply = call(BackupRequest.Operation.GET, info.id, index); assertNull(reply.error)
                    if (key == null) Base64.decode(reply.chunk!!) else crypto.decrypt(key, cloudContext("house", info.id, info.chunks, index), reply.chunk!!)
                }
                return parts.fold(ByteArray(0)) { a, b -> a + b }
            }
            suspend fun pull(budget: CloudSyncTest.Budget) {
                val page = call(BackupRequest.Operation.LIST, cursor = budget.cloud.cursor("cloud")); assertNull(page.error)
                for (info in listOfNotNull(page.checkpoint) + page.backups) budget.cloud.apply("cloud", "house", budget.device, info.sequence,
                    download(info), info.kind == CloudObjectKind.CHECKPOINT) { download(it.objectInfo) }
            }
            suspend fun flush() {
                val upload = source.cloud.prepare("cloud", "house", source.device, policy, key)!!
                publish(upload); assertNull(call(BackupRequest.Operation.COMMIT, upload.id).error)
                source.cloud.uploaded("cloud", upload.id); pull(source)
            }
            try {
                val receipt = Receipt("receipt", filePath = "receipts/receipt", originalName = "receipt.bin", addedAt = 1)
                val receiptBytes = ByteArray(70_000) { (it % 251).toByte() }
                val receipts = ReceiptRepository(source.notifier, createReceiptStore(source.db, source.deps), createPostingStore(source.db), createScheduleStore(source.db), source.deps)
                receipts.save(receipt); source.files.store(receipt.id, receipt.originalName, receiptBytes)
                source.accounts.upsert(Account("removed", "Delete me", 5.0)); flush()
                assertEquals(1, objects.values.size)
                assertTrue(objects.values.values.none { it.contentEquals(receiptBytes) })
                val objectId = source.cloud.prepare("cloud", "house", source.device, policy, key)
                assertNull(objectId)
                receipts.save(receipt.copy(notes = "Metadata only")); flush()
                assertEquals(1, objects.values.size, "Metadata changes must reuse the receipt object")
                source.accounts.delete("removed"); flush()
                target.accounts.upsert(Account("offline", "Keep my offline edit", 12.0))
                // Capture an old checkpoint, then append a concurrent change: compare-and-swap must reject it.
                val head = call(BackupRequest.Operation.LIST).headSequence
                val stale = source.cloud.prepareCheckpoint("cloud", "house", source.device, policy, key, head)!!
                source.accounts.upsert(Account("new", "Concurrent change", 23.0)); flush()
                publish(stale); assertNotNull(call(BackupRequest.Operation.COMMIT, stale.id).error)
                assertNull(call(BackupRequest.Operation.ABORT, stale.id).error); source.cloud.clearCheckpoint("cloud")
                val latest = call(BackupRequest.Operation.LIST).headSequence
                val checkpoint = source.cloud.prepareCheckpoint("cloud", "house", source.device, policy, key, latest)!!
                publish(checkpoint)
                objects.fail = true
                assertNotNull(call(BackupRequest.Operation.COMMIT, checkpoint.id).error)
                assertNull(call(BackupRequest.Operation.LIST).checkpoint)
                assertTrue(call(BackupRequest.Operation.LIST).backups.isNotEmpty(), "Failed storage publication must retain the old log")
                objects.fail = false
                assertNull(call(BackupRequest.Operation.COMMIT, checkpoint.id).error)
                assertTrue(call(BackupRequest.Operation.LIST).backups.isEmpty())
                assertEquals(latest, call(BackupRequest.Operation.LIST).checkpoint!!.sequence)
                assertNull(call(BackupRequest.Operation.COMMIT, checkpoint.id).error)
                val published = call(BackupRequest.Operation.LIST).checkpoint!!
                assertFails { target.cloud.apply("cloud", "house", target.device, latest + 1, download(published), true) { download(it.objectInfo) } }
                assertEquals(0L, target.cloud.cursor("cloud"))
                pull(target)
                assertEquals(setOf("new", "offline"), target.db.accountQueries.selectAll().awaitAsList().map { it.id }.toSet())
                assertContentEquals(receiptBytes, target.files.read("receipt"))
                assertEquals(latest, target.cloud.cursor("cloud"))
                // A lagging device's old entity must not resurrect a checkpoint tombstone.
                assertTrue(target.facade.cloudState("target").any { it.entityId == "removed" && it.op == finance.shilling.shared.data.sync.ChangeOp.DELETE })
                source.cloud.clearCheckpoint("cloud")
                source.accounts.upsert(Account("new", "Another version", 24.0)); flush()
                val newer = source.cloud.prepareCheckpoint("cloud", "house", source.device, policy, key, call(BackupRequest.Operation.LIST).headSequence)!!
                publish(newer); assertNull(call(BackupRequest.Operation.COMMIT, newer.id).error)
                assertEquals(3, objects.values.size, "Retired checkpoint must be queued before remote deletion")
                val probe = CloudObjectUpload(BackupInfo(Uuid.random().toString(), 1, 0, mode, kind = CloudObjectKind.RECEIPT), listOf(Base64.encode(ByteArray(40))))
                objects.failDeletes = true
                assertNotNull(call(BackupRequest.Operation.BEGIN, probe.info.id, blob = probe).error)
                assertEquals(newer.id, call(BackupRequest.Operation.LIST).checkpoint!!.id)
                objects.failDeletes = false
                assertNull(call(BackupRequest.Operation.BEGIN, probe.info.id, blob = probe).error)
                assertEquals(2, objects.values.size)
                assertTrue(objects.values.keys.none { it.endsWith("/${checkpoint.id}") })
                assertNull(call(BackupRequest.Operation.ABORT, probe.info.id).error)
                database.open("house").use { db -> db.createStatement().use { it.executeUpdate("UPDATE cloud_entries SET created=0 WHERE kind='RECEIPT'") } }
                assertNull(call(BackupRequest.Operation.BEGIN, probe.info.id, blob = probe).error)
                assertEquals(2, objects.values.size, "A live checkpoint must pin old receipt objects")
                assertNull(call(BackupRequest.Operation.ABORT, probe.info.id).error)
                target.files.delete("receipt"); pull(target)
                assertContentEquals(receiptBytes, target.files.read("receipt"))
                database.open("house").use { db -> db.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM cloud_chunks").use { r -> r.next(); assertEquals(0, r.getInt(1)) } } }
            } finally { source.close(); target.close(); database.close(); local.close() }
        }
    }
}
