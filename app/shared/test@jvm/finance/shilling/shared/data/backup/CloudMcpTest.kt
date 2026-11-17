package finance.shilling.shared.data.backup

import finance.shilling.core.backup.*
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.store.backup.*
import finance.shilling.shared.data.store.cloud.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.uuid.Uuid

class CloudMcpTest {
    @Test fun trustedToolsPublishToDeviceStreamAndRetryAfterRestartWithoutDuplicates() = runBlocking {
        val root = Files.createTempDirectory("mcp-cloud")
        BackupDatabase.sqlite(root).use { database ->
            var mode = BackupEncryption.TRUSTED_CLOUD
            val storage = EncryptedBackupRepository(database, "house", ByteArray(32) { 5 }) { mode }
            val source = CloudSyncTest.Budget("source")
            source.init()
            try {
                source.accounts.upsert(Account("account", "Checking", 100.0))
                val policy = BackupPolicy(enabled = true, available = true, encryption = mode)
                suspend fun publish(upload: CloudUpload) {
                    suspend fun request(op: BackupRequest.Operation, index: Int = 0, chunk: String = "") = storage.handle(
                        BackupRequest(Uuid.random().toString(), op, upload.id, index, upload.chunks.size, chunk, mode,
                            kind = upload.kind, baseSequence = upload.baseSequence)).also { assertNull(it.error) }
                    request(BackupRequest.Operation.BEGIN)
                    upload.chunks.forEachIndexed { index, chunk -> request(BackupRequest.Operation.PUT, index, chunk) }
                    request(BackupRequest.Operation.COMMIT)
                }
                val delta = source.cloud.prepare("cloud", "house", "source", policy, null)!!
                publish(delta); source.cloud.uploaded("cloud", delta.id)
                val info = storage.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.LIST)).backups.single()
                val bytes = kotlin.io.encoding.Base64.decode(delta.chunks.single())
                source.cloud.apply("cloud", "house", "source", info.sequence, bytes)
                var grant: CloudMcpGrant? = CloudMcpGrant("test", "a".repeat(64), true, true, Long.MAX_VALUE, "owner")
                var mcp = CloudMcpRepository("house", storage)
                val list = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_accounts"}}"""
                assertFailsWith<IllegalArgumentException> { mcp.handle(list, null) { grant } }
                publish(source.cloud.prepareCheckpoint("cloud", "house", "source", policy, null, info.sequence)!!)
                val listed = mcp.handle(list, null) { grant }!!
                assertTrue("Checking" in listed)
                grant = grant!!.copy(write = false)
                val write = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"record_transaction","arguments":{"title":"MCP coffee","amount":4.5,"type":"EXPENSE","accountId":"account","date":"2026-10-01"}}}"""
                val key = "${System.currentTimeMillis()}.${Uuid.random()}"
                assertTrue("-32003" in mcp.handle(write, key) { grant }!!)
                grant = grant!!.copy(write = true)
                val response = mcp.handle(write, key) { grant }!!
                assertFalse(response.contains("\"isError\":true"), response)
                assertFalse(response.contains("\"error\":"), response)
                mcp = CloudMcpRepository("house", EncryptedBackupRepository(database, "house", ByteArray(32) { 5 }) { mode })
                assertEquals(response, mcp.handle(write, key) { grant })
                assertFailsWith<IllegalArgumentException> { mcp.handle(write.replace("4.5", "6.5"), key) { grant } }
                val page = storage.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.LIST, cursor = info.sequence))
                assertEquals(1, page.backups.size)
                val written = page.backups.single()
                val committed = storage.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.GET, written.id)).chunk!!
                val payload = kotlin.io.encoding.Base64.decode(committed)
                source.cloud.apply("cloud", "house", "source", written.sequence, payload)
                val rows = source.facade.cloudState("source")
                assertEquals(1, rows.count { it.entityType == finance.shilling.shared.data.sync.EntityType.POSTING })
                // Compaction must not erase a successful command's idempotency receipt.
                source.cloud.prepare("cloud", "house", "source", policy, null)?.let { publish(it); source.cloud.uploaded("cloud", it.id) }
                val head = storage.handle(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.LIST)).headSequence
                if (source.cloud.cursor("cloud") == head) {
                    source.cloud.clearCheckpoint("cloud")
                    publish(source.cloud.prepareCheckpoint("cloud", "house", "source", policy, null, head)!!)
                    assertEquals(response, mcp.handle(write, key) { grant })
                }
                grant = null
                assertFailsWith<McpAccessDenied> { mcp.handle(list, null) { grant } }
                mode = BackupEncryption.END_TO_END
                // Service-held storage keys do not override an E2E policy.
                assertFailsWith<IllegalArgumentException> { storage.command(sha256("a".repeat(64) + "|" + key)) }
                assertFalse(Files.readAllBytes(root.resolve(sha256("house") + ".sqlite")).decodeToString().contains("MCP coffee"))
            } finally { source.close() }
        }
    }
}
