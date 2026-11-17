package finance.shilling.shared.data.backup

import finance.shilling.core.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** One acknowledged chunk in flight bounds application buffering and supports idempotent retries. */
class BackupPeerClient {
    lateinit var transport: BackupRtcTransport
    private val serial = Mutex()
    @kotlin.concurrent.Volatile private var pending: Pair<String, CompletableDeferred<BackupResponse>>? = null
    suspend fun receive(peer: String, text: String): String? {
        if (peer == BACKUP_WORKER_ID) {
            val response = Json.decodeFromString<BackupResponse>(text)
            pending?.takeIf { it.first == response.requestId }?.second?.complete(response)
        }
        return null
    }
    suspend fun request(
        operation: BackupRequest.Operation, backupId: String = "", index: Int = 0,
        chunks: Int = 0, chunk: String = "", encryption: BackupEncryption = BackupEncryption.END_TO_END, keyId: String = "", cursor: Long = 0,
        kind: CloudObjectKind = CloudObjectKind.DELTA, references: List<String> = emptyList(), baseSequence: Long = 0
    ): BackupResponse = serial.withLock {
        transport.awaitPeer(BACKUP_WORKER_ID)
        val id = Uuid.random().toString()
        val result = CompletableDeferred<BackupResponse>()
        pending = id to result
        try {
            val request = Json.encodeToString(BackupRequest(id, operation, backupId, index, chunks, chunk, encryption, keyId, cursor, kind, references, baseSequence))
            var response: BackupResponse? = null
            for (attempt in 0..2) {
                try {
                    transport.send(BACKUP_WORKER_ID, request)
                    response = withTimeout(30_000) { result.await() }
                    break
                } catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) throw e
                    if (attempt == 2) throw e
                } catch (e: Exception) { if (attempt == 2) throw e }
                transport.reconnect()
                transport.awaitPeer(BACKUP_WORKER_ID)
            }
            requireNotNull(response).also { check(it.error == null) { it.error ?: "Backup rejected" } }
        } finally { pending = null }
    }
}
