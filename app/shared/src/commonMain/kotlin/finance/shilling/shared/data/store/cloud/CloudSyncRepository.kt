@file:OptIn(org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)
package finance.shilling.shared.data.store.cloud

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import finance.shilling.core.backup.*
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.backup.BackupCrypto
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mobilenativefoundation.store.store5.*
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

const val MAX_CLOUD_BATCH_BYTES = 32 * 1024 * 1024

@Serializable data class CloudFile(val id: String, val name: String, val objectInfo: BackupInfo, val digest: String, val size: Int)
@Serializable data class CloudDelta(val version: Int = 1, val household: String, val changes: List<ChangeMessage>, val files: List<CloudFile>, val checkpoint: Boolean = false, val baseSequence: Long = 0)
@Serializable data class CloudObjectUpload(val info: BackupInfo, val chunks: List<String>)
@Serializable data class CloudUpload(val id: String, val encryption: BackupEncryption, val keyId: String,
    val revision: Long, val chunks: List<String>, val marks: Map<String, String>,
    val objects: List<CloudObjectUpload> = emptyList(), val files: List<CloudFile> = emptyList(),
    val kind: CloudObjectKind = CloudObjectKind.DELTA, val baseSequence: Long = 0)
@Serializable private data class CloudState(val cursor: Long = 0, val marks: Map<String, String> = emptyMap(),
    val revision: Long = -1, val keyId: String = "", val pending: CloudUpload? = null,
    val blobs: Map<String, CloudFile> = emptyMap(), val checkpoint: CloudUpload? = null)

/** Local durable upload queue/checkpoint, with entity reads and merges through Store5. */
class CloudSyncRepository(private val db: ShillingDatabase, private val stores: SyncStoreFacade,
    private val files: ReceiptFileStore, private val notifier: ChangeNotifier) {
    private val mutex = Mutex()
    private val crypto = BackupCrypto()
    private val scannedVersions = mutableMapOf<String, Int>()
    private val stateStore = MutableStoreBuilder.from<String, List<String>, List<String>, List<String>>(
        fetcher = Fetcher.of { _: String -> error("Local cloud checkpoint") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key: String -> flow { emit(db.cloudStateQueries.selectState(key).awaitAsOneOrNull()?.let { listOf(it) } ?: emptyList()) } },
            writer = { key, values -> db.cloudStateQueries.putState(key, values.single()) }
        ), converter = Converter.Builder<List<String>, List<String>, List<String>>().fromNetworkToLocal { it }.fromOutputToLocal { it }.build()
    ).disableCache().build(updater = Updater.by<String, List<String>, Unit>(post = { _, _ -> UpdaterResult.Success.Typed(Unit) }))
    private suspend fun state(namespace: String) = stateStore.readLocalSourceOfTruth(namespace).firstOrNull()?.let { Json.decodeFromString<CloudState>(it) } ?: CloudState()
    private suspend fun save(namespace: String, value: CloudState) {
        val encoded = Json.encodeToString(value)
        stateStore.write(StoreWriteRequest.of<String, List<String>, Unit>(namespace, listOf(encoded)))
        check(stateStore.readLocalSourceOfTruth(namespace).single() == encoded)
    }
    suspend fun cursor(namespace: String): Long = mutex.withLock { state(namespace).cursor }
    private fun entityKey(change: ChangeMessage) = "${change.entityType}:${change.entityId}"
    private suspend fun mark(change: ChangeMessage, digest: String?) = crypto.digest(
        (Json.encodeToString(change.copy(deviceId = "")) + (digest ?: "")).encodeToByteArray())
    private fun blobKey(mode: BackupEncryption, keyId: String, digest: String) = "$mode:$keyId:$digest"
    private suspend fun encode(household: String, bytes: ByteArray, mode: BackupEncryption, key: String?, kind: CloudObjectKind): CloudObjectUpload {
        val id = Uuid.random().toString()
        val count = (bytes.size + BACKUP_CHUNK_BYTES - 1) / BACKUP_CHUNK_BYTES
        require(bytes.size in 1..MAX_CLOUD_BATCH_BYTES)
        val chunks = (0 until count).map { index ->
            val part = bytes.copyOfRange(index * BACKUP_CHUNK_BYTES, minOf(bytes.size, (index + 1) * BACKUP_CHUNK_BYTES))
            try { if (mode == BackupEncryption.END_TO_END) crypto.encrypt(requireNotNull(key), cloudContext(household, id, count, index), part) else Base64.encode(part) }
            finally { part.fill(0) }
        }
        return CloudObjectUpload(BackupInfo(id, count, 0, mode, key?.let { crypto.fingerprint(it) } ?: "", kind = kind), chunks)
    }
    suspend fun prepare(namespace: String, household: String, deviceId: String, policy: BackupPolicy, key: String?): CloudUpload? = mutex.withLock {
        require(policy.enabled)
        val keyId = if (policy.encryption == BackupEncryption.END_TO_END) crypto.fingerprint(requireNotNull(key)) else ""
        var state = state(namespace)
        if (state.revision != policy.encryptionRevision || state.keyId != keyId) {
            state = state.copy(marks = emptyMap(), pending = null, revision = policy.encryptionRevision, keyId = keyId)
            save(namespace, state); scannedVersions.remove(namespace)
        }
        state.pending?.let { return@withLock it }
        val version = notifier.version.value
        if (scannedVersions[namespace] == version) return@withLock null
        val selected = mutableListOf<ChangeMessage>()
        val attachments = mutableListOf<CloudFile>()
        val objects = mutableListOf<CloudObjectUpload>()
        val preparedBlobs = mutableMapOf<String, CloudObjectUpload>()
        val marks = mutableMapOf<String, String>()
        var size = 1024L
        for (change in stores.cloudState(deviceId).sortedBy { it.entityType.ordinal }) {
            val receipt = (change.payload as? ChangePayload.ReceiptPayload)?.receipt
            val bytes = receipt?.let { files.read(it.id)?.copyOf() }
            if (receipt != null && bytes == null) continue
            try {
                val digest = bytes?.let { crypto.digest(it) }
                val mark = mark(change, digest)
                if (state.marks[entityKey(change)] == mark) continue
                val cost = Json.encodeToString(change).encodeToByteArray().size + (bytes?.size ?: 0)
                require(cost + 1024 < MAX_CLOUD_BATCH_BYTES) { "A change exceeds the cloud transfer limit." }
                if (selected.isNotEmpty() && (size + cost >= MAX_CLOUD_BATCH_BYTES || selected.size >= 512)) break
                size += cost; selected += change; marks[entityKey(change)] = mark
                if (receipt != null && bytes != null && digest != null) {
                    val known = state.blobs[blobKey(policy.encryption, keyId, digest)]
                    val encoded = preparedBlobs[digest] ?: if (known == null) encode(household, bytes, policy.encryption, key, CloudObjectKind.RECEIPT) else {
                        // Reuse the object ID. STAT avoids transmission; exact retry ciphertext is still durable if the object needs repair.
                        val count = known.objectInfo.chunks
                        CloudObjectUpload(known.objectInfo, (0 until count).map { index ->
                            val part = bytes.copyOfRange(index * BACKUP_CHUNK_BYTES, minOf(bytes.size, (index + 1) * BACKUP_CHUNK_BYTES))
                            try { if (key == null) Base64.encode(part) else crypto.encrypt(key, cloudContext(household, known.objectInfo.id, count, index), part) }
                            finally { part.fill(0) }
                        })
                    }
                    if (digest !in preparedBlobs) objects += encoded
                    preparedBlobs[digest] = encoded
                    attachments += CloudFile(receipt.id, receipt.originalName, encoded.info, digest, bytes.size)
                }
            } finally { bytes?.fill(0) }
        }
        if (selected.isEmpty()) { scannedVersions[namespace] = version; return@withLock null }
        check(version == notifier.version.value) { "Budget changed during cloud preparation; retrying." }
        val bytes = Json.encodeToString(CloudDelta(household = household, changes = selected, files = attachments)).encodeToByteArray()
        try {
            val encoded = encode(household, bytes, policy.encryption, key, CloudObjectKind.DELTA)
            CloudUpload(encoded.info.id, policy.encryption, keyId, policy.encryptionRevision, encoded.chunks, marks, objects, attachments).also {
                save(namespace, state.copy(pending = it))
            }
        } finally { bytes.fill(0) }
    }
    suspend fun uploaded(namespace: String, id: String) = mutex.withLock {
        val state = state(namespace); val pending = requireNotNull(state.pending); require(pending.id == id)
        val blobs = state.blobs + pending.files.associateBy { blobKey(it.objectInfo.encryption, it.objectInfo.keyId, it.digest) }
        save(namespace, state.copy(marks = state.marks + pending.marks, pending = null, blobs = blobs))
    }
    suspend fun prepareCheckpoint(namespace: String, household: String, deviceId: String, policy: BackupPolicy, key: String?, head: Long): CloudUpload? = mutex.withLock {
        val state = state(namespace)
        state.checkpoint?.let { return@withLock it }
        if (state.cursor != head || state.pending != null || head == 0L) return@withLock null
        val version = notifier.version.value
        val changes = stores.cloudState(deviceId)
        if (changes.size > 50_000) return@withLock null
        val attachments = mutableListOf<CloudFile>()
        for (change in changes) {
            val receipt = (change.payload as? ChangePayload.ReceiptPayload)?.receipt
            val bytes = receipt?.let { files.read(it.id)?.copyOf() }
            try {
                if (receipt != null && bytes == null) return@withLock null
                val digest = bytes?.let { crypto.digest(it) }
                if (state.marks[entityKey(change)] != mark(change, digest)) return@withLock null
                if (receipt != null && digest != null) {
                    val known = state.blobs[blobKey(policy.encryption, state.keyId, digest)] ?: return@withLock null
                    attachments += known.copy(id = receipt.id, name = receipt.originalName)
                }
            } finally { bytes?.fill(0) }
        }
        if (attachments.size > 512 || notifier.version.value != version) return@withLock null
        val bytes = Json.encodeToString(CloudDelta(household = household, changes = changes, files = attachments, checkpoint = true, baseSequence = head)).encodeToByteArray()
        if (bytes.size > MAX_CLOUD_BATCH_BYTES) { bytes.fill(0); return@withLock null }
        try {
            val encoded = encode(household, bytes, policy.encryption, key, CloudObjectKind.CHECKPOINT)
            CloudUpload(encoded.info.id, policy.encryption, encoded.info.keyId, policy.encryptionRevision, encoded.chunks, emptyMap(), files = attachments,
                kind = CloudObjectKind.CHECKPOINT, baseSequence = head).also { save(namespace, state.copy(checkpoint = it)) }
        } finally { bytes.fill(0) }
    }
    suspend fun clearCheckpoint(namespace: String) = mutex.withLock { save(namespace, state(namespace).copy(checkpoint = null)) }
    suspend fun apply(namespace: String, household: String, deviceId: String, sequence: Long, bytes: ByteArray,
        checkpoint: Boolean = false, resolve: suspend (CloudFile) -> ByteArray = { error("Receipt resolver required") }) = mutex.withLock {
        val state = state(namespace)
        require(if (checkpoint) sequence > state.cursor else sequence == state.cursor + 1) { "Cloud history has a gap; cursor was not advanced." }
        val delta = decodeCloudDelta(household, bytes)
        require(delta.checkpoint == checkpoint)
        require(if (checkpoint) delta.baseSequence == sequence else delta.baseSequence == 0L) { "Checkpoint sequence authentication failed" }
        val attachments = delta.files.associateBy { it.id }
        for (change in delta.changes) {
            val file = if (change.entityType == EntityType.RECEIPT) attachments[change.entityId] else null
            stores.applyIncomingChange(change) { equivalent ->
                if (file != null && (!equivalent || !files.hasFile(file.id))) {
                    val contents = resolve(file)
                    try {
                        require(contents.size == file.size && crypto.digest(contents) == file.digest) { "Receipt integrity check failed" }
                        files.store(file.id, file.name, contents)
                    } finally { contents.fill(0) }
                }
                if (!equivalent && change.entityType == EntityType.RECEIPT && change.op == ChangeOp.DELETE) files.delete(change.entityId)
            }
        }
        val current = stores.cloudState(deviceId).associateBy { entityKey(it) }
        val marks = state.marks.toMutableMap()
        val blobs = state.blobs.toMutableMap()
        for (change in delta.changes) {
            val local = current[entityKey(change)] ?: continue
            if (local.id == change.id && local.timestamp == change.timestamp) {
                val file = if (change.entityType == EntityType.RECEIPT) attachments[change.entityId] else null
                marks[entityKey(change)] = mark(local, file?.digest)
                if (file != null) blobs[blobKey(file.objectInfo.encryption, file.objectInfo.keyId, file.digest)] = file
            }
        }
        save(namespace, state.copy(cursor = sequence, marks = marks, blobs = blobs))
        notifier.notifyChanged()
    }

}

fun cloudContext(household: String, id: String, count: Int, index: Int): String =
    Json.encodeToString(listOf("shilling.cloud.v1", household, id, count.toString(), index.toString()))

/** Validate a complete authenticated batch before applying anything, including file paths and payload identity. */
fun decodeCloudDelta(household: String, bytes: ByteArray): CloudDelta {
    require(bytes.size in 1..MAX_CLOUD_BATCH_BYTES)
    val delta = Json.decodeFromString<CloudDelta>(bytes.decodeToString(throwOnInvalidSequence = true))
    require(delta.version == 1 && delta.household == household && delta.changes.size in (if (delta.checkpoint) 0..50_000 else 1..512))
    require(delta.changes.map { it.entityType to it.entityId }.distinct().size == delta.changes.size)
    for (change in delta.changes) {
        require(change.id.isNotBlank() && change.id.length <= 256 && change.entityId.isNotBlank() && change.entityId.length <= 256 && change.timestamp >= 0)
        val identity = when (val p = change.payload) {
            is ChangePayload.AccountPayload -> EntityType.ACCOUNT to p.account.id
            is ChangePayload.CategoryPayload -> EntityType.CATEGORY to p.category.id
            is ChangePayload.SchedulePayload -> EntityType.SCHEDULE to p.schedule.id
            is ChangePayload.ScheduleExceptionPayload -> EntityType.SCHEDULE_EXCEPTION to "${p.exception.scheduleId}_${p.exception.date.toEpochDays()}"
            is ChangePayload.PostingPayload -> EntityType.POSTING to p.posting.id
            is ChangePayload.ReceiptPayload -> EntityType.RECEIPT to p.receipt.id
            null -> null
        }
        require(if (change.op == ChangeOp.DELETE) identity == null else identity == (change.entityType to change.entityId))
    }
    val receipts = delta.changes.mapNotNull { (it.payload as? ChangePayload.ReceiptPayload)?.receipt }.associateBy { it.id }
    require(delta.files.size == receipts.size && delta.files.map { it.id }.toSet() == receipts.keys)
    delta.files.forEach {
        require(it.id.length in 1..128 && it.id.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' })
        require(it.name == receipts[it.id]?.originalName && '/' !in it.name && '\\' !in it.name && it.name !in listOf(".", ".."))
        require(it.objectInfo.kind == CloudObjectKind.RECEIPT && validBackupId(it.objectInfo.id))
        require(it.objectInfo.chunks in 1..BACKUP_MAX_CHUNKS && it.size in 1..MAX_CLOUD_BATCH_BYTES && it.digest.length in 32..128)
    }
    return delta
}
