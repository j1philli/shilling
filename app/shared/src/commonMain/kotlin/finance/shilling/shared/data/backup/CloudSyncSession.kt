package finance.shilling.shared.data.backup

import finance.shilling.core.backup.*
import finance.shilling.shared.data.store.cloud.MAX_CLOUD_BATCH_BYTES
import finance.shilling.shared.data.store.backup.RecoveryKeyRepository
import finance.shilling.shared.data.store.cloud.*
import kotlin.io.encoding.Base64

/** One sync pass over the long-lived WebRTC channel; retry state lives in the repository. */
class CloudSyncSession(private val repository: CloudSyncRepository, private val keys: RecoveryKeyRepository,
    private val namespace: String, private val household: String, private val deviceId: String) {
    suspend fun sync(policy: BackupPolicy, peer: BackupPeerClient): Boolean {
        require(policy.enabled && policy.available)
        if (policy.encryption == BackupEncryption.TRUSTED_CLOUD) require(keys.cloudConsentRevision() == policy.encryptionRevision) {
            "Confirm cloud access on this device before syncing."
        }
        val key = if (policy.encryption == BackupEncryption.END_TO_END) requireNotNull(keys.activeKey()) { "Import or sync your recovery key first." } else null
        suspend fun publish(upload: CloudUpload) {
            for (blob in upload.objects) {
                val stored = peer.request(BackupRequest.Operation.STAT, blob.info.id).backups.firstOrNull()
                if (stored != null) {
                    require(stored.kind == CloudObjectKind.RECEIPT && stored.encryption == blob.info.encryption && stored.keyId == blob.info.keyId && stored.chunks == blob.info.chunks)
                    continue
                }
                peer.request(BackupRequest.Operation.BEGIN, blob.info.id, chunks = blob.chunks.size, encryption = blob.info.encryption, keyId = blob.info.keyId, kind = CloudObjectKind.RECEIPT)
                blob.chunks.forEachIndexed { index, chunk -> peer.request(BackupRequest.Operation.PUT, blob.info.id, index, chunk = chunk, encryption = blob.info.encryption) }
                peer.request(BackupRequest.Operation.COMMIT, blob.info.id, encryption = blob.info.encryption)
            }
            peer.request(BackupRequest.Operation.BEGIN, upload.id, chunks = upload.chunks.size, encryption = upload.encryption, keyId = upload.keyId,
                kind = upload.kind, references = upload.files.map { it.objectInfo.id }.distinct(), baseSequence = upload.baseSequence)
            upload.chunks.forEachIndexed { index, chunk -> peer.request(BackupRequest.Operation.PUT, upload.id, index, chunk = chunk, encryption = upload.encryption) }
            peer.request(BackupRequest.Operation.COMMIT, upload.id, encryption = upload.encryption)
        }
        for (batch in 0 until 4) {
            val upload = repository.prepare(namespace, household, deviceId, policy, key) ?: break
            publish(upload); repository.uploaded(namespace, upload.id)
        }
        val page = peer.request(BackupRequest.Operation.LIST, cursor = repository.cursor(namespace))
        suspend fun recovery(info: BackupInfo): String? = if (info.encryption == BackupEncryption.END_TO_END) requireNotNull(keys.key(info.keyId)) {
            "Import the recovery key for older cloud changes before continuing sync."
        } else null
        for (entry in listOfNotNull(page.checkpoint) + page.backups) {
            val bytes = downloadCloud(household, recovery(entry), entry, peer)
            try { repository.apply(namespace, household, deviceId, entry.sequence, bytes, entry.kind == CloudObjectKind.CHECKPOINT) { file ->
                downloadCloud(household, recovery(file.objectInfo), file.objectInfo, peer)
            } } finally { bytes.fill(0) }
        }
        val caughtUp = repository.cursor(namespace) == page.headSequence && repository.prepare(namespace, household, deviceId, policy, key) == null
        val needsTrustedBaseline = policy.encryption == BackupEncryption.TRUSTED_CLOUD && policy.mcpClients.isNotEmpty() &&
            peer.request(BackupRequest.Operation.LIST).checkpoint?.encryption != BackupEncryption.TRUSTED_CLOUD
        if (caughtUp && (needsTrustedBaseline || page.headSequence - page.checkpointSequence >= 64)) {
            repository.prepareCheckpoint(namespace, household, deviceId, policy, key, page.headSequence)?.let { checkpoint ->
                try { publish(checkpoint); repository.clearCheckpoint(namespace) }
                catch (e: IllegalStateException) {
                    peer.request(BackupRequest.Operation.ABORT, checkpoint.id)
                    repository.clearCheckpoint(namespace)
                    throw e
                }
            }
        }
        return caughtUp
    }
}

suspend fun downloadCloud(household: String, key: String?, info: BackupInfo, peer: BackupPeerClient): ByteArray {
    require(info.chunks in 1..((MAX_CLOUD_BATCH_BYTES + BACKUP_CHUNK_BYTES - 1) / BACKUP_CHUNK_BYTES))
    val result = ByteArray(minOf(info.chunks.toLong() * BACKUP_CHUNK_BYTES, MAX_CLOUD_BATCH_BYTES.toLong()).toInt())
    var size = 0
    try {
        for (index in 0 until info.chunks) {
            val stored = requireNotNull(peer.request(BackupRequest.Operation.GET, info.id, index).chunk)
            val part = if (info.encryption == BackupEncryption.END_TO_END) BackupCrypto().decrypt(requireNotNull(key), cloudContext(household, info.id, info.chunks, index), stored) else Base64.decode(stored)
            try {
                require(part.size in 1..BACKUP_CHUNK_BYTES && (index == info.chunks - 1 || part.size == BACKUP_CHUNK_BYTES))
                require(size + part.size <= result.size)
                part.copyInto(result, size); size += part.size
            } finally { part.fill(0) }
        }
        return result.copyOf(size)
    } finally { result.fill(0) }
}
