@file:OptIn(org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)
package finance.shilling.shared.data.store.backup

import finance.shilling.core.backup.*
import finance.shilling.shared.data.store.readLocalSourceOfTruth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store.store5.*
import java.nio.file.Path
import java.sql.Connection
import java.util.Base64
import java.io.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Store5 persistence boundary shared by local SQLite and hosted Supabase workers. */
class EncryptedBackupRepository(private val database: BackupDatabase, private val household: String,
    private val storageKey: ByteArray? = null,
    private val allowedEncryption: () -> BackupEncryption = { BackupEncryption.END_TO_END }
) {
    constructor(root: Path, household: String, storageKey: ByteArray? = null,
        allowedEncryption: () -> BackupEncryption = { BackupEncryption.END_TO_END }
    ) : this(BackupDatabase.sqlite(root), household, storageKey, allowedEncryption)

    private val mutex = Mutex()
    private val snapshots = database.prefix + "cloud_entries"
    private val chunks = database.prefix + "cloud_chunks"
    suspend fun handle(request: BackupRequest): BackupResponse = handleInternal(request)

    internal suspend fun commitCommand(request: BackupRequest, command: CloudCommand): BackupResponse {
        require(request.operation == BackupRequest.Operation.COMMIT || request.operation == BackupRequest.Operation.LIST)
        return handleInternal(request, command)
    }

    private suspend fun handleInternal(request: BackupRequest, command: CloudCommand? = null): BackupResponse = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                require(validBackupId(request.requestId))
                require(request.cursor >= 0)
                if (request.operation != BackupRequest.Operation.LIST) require(validBackupId(request.backupId))
                database.open(household).use { db ->
                    db.autoCommit = false
                    try {
                        database.lock(db, household)
                        if (request.operation == BackupRequest.Operation.BEGIN) collectGarbage(db)
                        val response = when (request.operation) {
                            BackupRequest.Operation.BEGIN -> {
                                require(request.chunks in 1..BACKUP_MAX_CHUNKS)
                                require(request.encryption == allowedEncryption())
                                require(request.keyId.length <= 64)
                                if (request.encryption == BackupEncryption.TRUSTED_CLOUD) require(storageKey?.size == 32)
                                // Bound staging to two uploads and expire abandoned data after 24 hours.
                                cleanup(db, request.references.toSet() + request.backupId)
                                require(request.references.size <= 512 && request.references.distinct().size == request.references.size)
                                require(request.references.all(::validBackupId) && request.baseSequence >= 0)
                                require(request.kind != CloudObjectKind.RECEIPT || request.references.isEmpty())
                                val existing = info(db, request.backupId)
                                if (existing != null) require(existing.chunks == request.chunks && existing.encryption == request.encryption && existing.keyId == request.keyId && existing.kind == request.kind && existing.references == request.references)
                                else {
                                    val pending = db.prepareStatement("SELECT COUNT(*) FROM $snapshots WHERE complete=0 AND household=?").use { it.setString(1, household); it.executeQuery().use { r -> r.next(); r.getInt(1) } }
                                    require(pending < 2) { "Two uploads are already in progress; retry after they complete or expire." }
                                    val reserved = db.prepareStatement("SELECT COALESCE(SUM(chunks),0) FROM $snapshots WHERE household=?").use {
                                        it.setString(1, household); it.executeQuery().use { r -> r.next(); r.getLong(1) }
                                    }
                                    val quota = (512L + if (request.kind == CloudObjectKind.CHECKPOINT) 64 else 0) * 1024 * 1024
                                    require((reserved + request.chunks) * 36 * 1024 <= quota) { "Cloud sync quota reached." }
                                    db.prepareStatement("INSERT INTO $snapshots(id,chunks,created,mode,key_id,household,kind,refs,base_sequence) VALUES(?,?,?,?,?,?,?,?,?)").use {
                                        it.setString(1, request.backupId); it.setInt(2, request.chunks); it.setLong(3, System.currentTimeMillis()); it.setString(4, request.encryption.name); it.setString(5, request.keyId); it.setString(6, household); it.setString(7, request.kind.name); it.setString(8, Json.encodeToString(request.references)); it.setLong(9, request.baseSequence); it.executeUpdate()
                                    }
                                }
                                BackupResponse(request.requestId)
                            }
                            BackupRequest.Operation.PUT -> {
                                val snapshot = info(db, request.backupId) ?: error("Unknown backup")
                                require(request.index in 0 until snapshot.chunks)
                                require(snapshot.encryption == request.encryption && snapshot.encryption == allowedEncryption())
                                require(request.chunk.length <= 36 * 1024)
                                val bytes = Base64.getDecoder().decode(request.chunk)
                                require(bytes.size in (if (snapshot.encryption == BackupEncryption.END_TO_END) 28 else 1)..(BACKUP_CHUNK_BYTES + 28))
                                val store = chunkStore(db)
                                val key = ChunkKey(request.backupId, request.index)
                                val old = if (complete(db, request.backupId) && external(snapshot)) storedChunk(db, snapshot, request.index) else store.readLocalSourceOfTruth(key).firstOrNull()
                                if (old != null) require(readChunk(snapshot, request.index, old) == request.chunk) { "A chunk cannot be overwritten." }
                                else {
                                    require(!complete(db, request.backupId))
                                    store.write(StoreWriteRequest.of<ChunkKey, List<String>, Unit>(key, listOf(if (snapshot.encryption == BackupEncryption.TRUSTED_CLOUD) encryptAtRest(snapshot, request.index, bytes) else request.chunk)))
                                    check(readChunk(snapshot, request.index, store.readLocalSourceOfTruth(key).single()) == request.chunk)
                                }
                                BackupResponse(request.requestId)
                            }
                            BackupRequest.Operation.COMMIT -> {
                                val snapshot = info(db, request.backupId) ?: error("Unknown backup")
                                require(snapshot.encryption == allowedEncryption())
                                if (!complete(db, snapshot.id)) {
                                    val count = db.prepareStatement("SELECT COUNT(*) FROM $chunks WHERE snapshot=? AND household=?").use {
                                        it.setString(1, request.backupId); it.setString(2, household); it.executeQuery().use { r -> r.next(); r.getInt(1) }
                                    }
                                    require(count == snapshot.chunks) { "Upload is incomplete." }
                                    for (id in snapshot.references) {
                                        val blob = requireNotNull(info(db, id)) { "Missing receipt object" }
                                        require(blob.kind == CloudObjectKind.RECEIPT && complete(db, id))
                                    }
                                    val head = head(db)
                                    val sequence = when (snapshot.kind) {
                                        CloudObjectKind.RECEIPT -> 0
                                        CloudObjectKind.DELTA -> head + 1
                                        CloudObjectKind.CHECKPOINT -> {
                                            val base = db.prepareStatement("SELECT base_sequence FROM $snapshots WHERE household=? AND id=?").use {
                                                it.setString(1, household); it.setString(2, snapshot.id); it.executeQuery().use { r -> r.next(); r.getLong(1) }
                                            }
                                            require(base == head && base > 0) { "Cloud changed while preparing checkpoint; retry after catch-up." }
                                            base
                                        }
                                    }
                                    if (external(snapshot)) {
                                        val parts = (0 until snapshot.chunks).map { index ->
                                            Base64.getDecoder().decode(chunkStore(db).readLocalSourceOfTruth(ChunkKey(snapshot.id, index)).single())
                                        }
                                        val packed = ByteArrayOutputStream()
                                        DataOutputStream(packed).use { out ->
                                            out.writeInt(snapshot.chunks)
                                            parts.forEach { out.writeInt(it.size) }
                                            parts.forEach { out.write(it) }
                                        }
                                        database.objects!!.write(household, snapshot.id, packed.toByteArray())
                                        db.prepareStatement("DELETE FROM $chunks WHERE household=? AND snapshot=?").use { it.setString(1, household); it.setString(2, snapshot.id); it.executeUpdate() }
                                    }
                                    db.prepareStatement("UPDATE $snapshots SET complete=1,sequence=? WHERE household=? AND id=?").use {
                                        it.setLong(1, sequence); it.setString(2, household); it.setString(3, snapshot.id); it.executeUpdate()
                                    }
                                    if (snapshot.kind == CloudObjectKind.CHECKPOINT) {
                                        val covered = db.prepareStatement("SELECT id FROM $snapshots WHERE household=? AND complete=1 AND kind<>'RECEIPT' AND sequence<=? AND id<>?").use {
                                            it.setString(1, household); it.setLong(2, sequence); it.setString(3, snapshot.id)
                                            it.executeQuery().use { r -> buildList { while(r.next()) add(r.getString(1)) } }
                                        }
                                        covered.forEach { deleteEntry(db, it) }
                                    }
                                }
                                BackupResponse(request.requestId)
                            }
                            BackupRequest.Operation.LIST -> {
                                val checkpoint = db.prepareStatement("SELECT id FROM $snapshots WHERE household=? AND complete=1 AND kind='CHECKPOINT' ORDER BY sequence DESC LIMIT 1").use {
                                    it.setString(1, household); it.executeQuery().use { r -> if (r.next()) info(db, r.getString(1)) else null }
                                }
                                val cursor = maxOf(request.cursor, checkpoint?.sequence ?: 0)
                                val entries = db.prepareStatement("SELECT id FROM $snapshots WHERE household=? AND complete=1 AND kind='DELTA' AND sequence>? ORDER BY sequence LIMIT 64").use {
                                    it.setString(1, household); it.setLong(2, cursor)
                                    it.executeQuery().use { r -> buildList { while (r.next()) add(requireNotNull(info(db, r.getString(1))).copy(references = emptyList())) } }
                                }
                                BackupResponse(request.requestId, backups = entries, checkpoint = checkpoint?.takeIf { it.sequence > request.cursor }?.copy(references = emptyList()), headSequence = head(db), checkpointSequence = checkpoint?.sequence ?: 0)
                            }
                            BackupRequest.Operation.STAT -> BackupResponse(request.requestId,
                                backups = info(db, request.backupId)?.takeIf { complete(db, it.id) }?.let { listOf(it.copy(references = emptyList())) } ?: emptyList())
                            BackupRequest.Operation.ABORT -> {
                                if (info(db, request.backupId) != null && !complete(db, request.backupId)) deleteEntry(db, request.backupId)
                                BackupResponse(request.requestId)
                            }
                            BackupRequest.Operation.GET -> {
                                val snapshot = info(db, request.backupId) ?: error("Unknown backup")
                                require(complete(db, request.backupId) && request.index in 0 until snapshot.chunks)
                                BackupResponse(request.requestId, chunk = readChunk(snapshot, request.index, storedChunk(db, snapshot, request.index)))
                            }
                        }
                        if (command != null) {
                            require(allowedEncryption() == BackupEncryption.TRUSTED_CLOUD)
                            val store = commandStore(db)
                            require(store.readLocalSourceOfTruth(command.id).isEmpty()) { "Command was already committed" }
                            store.write(StoreWriteRequest.of<String, List<CloudCommand>, Unit>(command.id, listOf(command)))
                        }
                        db.commit()
                        response
                    } catch (e: Exception) { db.rollback(); throw e }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { BackupResponse(request.requestId, error = "Cloud request rejected. Check storage quota, connection, encryption mode, and upload completeness.") }
        }
    }
    internal suspend fun command(id: String): CloudCommand? = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(allowedEncryption() == BackupEncryption.TRUSTED_CLOUD)
            database.open(household).use { commandStore(it).readLocalSourceOfTruth(id).singleOrNull() }
        }
    }

    private fun commandStore(db: Connection): MutableStore<String, List<CloudCommand>> = MutableStoreBuilder.from<String, List<CloudCommand>, List<CloudCommand>, List<CloudCommand>>(
        fetcher = Fetcher.of { _: String -> error("Commands use the encrypted local source of truth") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { id: String -> flowOf(db.prepareStatement("SELECT ciphertext FROM ${database.prefix}cloud_commands WHERE household=? AND id=? AND expires>?").use {
                it.setString(1, household); it.setString(2, id); it.setLong(3, System.currentTimeMillis())
                it.executeQuery().use { rows -> if (!rows.next()) emptyList() else {
                    val encoded = Base64.getEncoder().encodeToString(rows.getBytes(1))
                    val bytes = Base64.getDecoder().decode(readChunk(commandInfo(id), -1, encoded))
                    try { listOf(Json.decodeFromString<CloudCommand>(bytes.decodeToString())) } finally { bytes.fill(0) }
                } }
            }) },
            writer = { id, values ->
                val command = values.single()
                require(command.id == id && id.matches(Regex("[a-f0-9]{64}")))
                val bytes = Json.encodeToString(command).encodeToByteArray()
                try {
                    require(bytes.size <= 64 * 1024)
                    db.prepareStatement("DELETE FROM ${database.prefix}cloud_commands WHERE household=? AND expires<=?").use {
                        it.setString(1, household); it.setLong(2, System.currentTimeMillis()); it.executeUpdate()
                    }
                    val count = db.prepareStatement("SELECT count(*) FROM ${database.prefix}cloud_commands WHERE household=?").use {
                        it.setString(1, household); it.executeQuery().use { r -> r.next(); r.getInt(1) }
                    }
                    require(count < 4096) { "MCP command quota reached" }
                    db.prepareStatement("INSERT INTO ${database.prefix}cloud_commands(household,id,expires,ciphertext) VALUES(?,?,?,?)").use {
                        it.setString(1, household); it.setString(2, id); it.setLong(3, command.expires)
                        it.setBytes(4, Base64.getDecoder().decode(encryptAtRest(commandInfo(id), -1, bytes))); it.executeUpdate(); Unit
                    }
                } finally { bytes.fill(0) }
            }
        ), converter = Converter.Builder<List<CloudCommand>, List<CloudCommand>, List<CloudCommand>>().fromNetworkToLocal { it }.fromOutputToLocal { it }.build()
    ).disableCache().build(updater = Updater.by<String, List<CloudCommand>, Unit>(post = { _, _ -> UpdaterResult.Success.Typed(Unit) }))
    private fun commandInfo(id: String) = BackupInfo(id, 1, 0, BackupEncryption.TRUSTED_CLOUD)

    private fun aad(snapshot: BackupInfo, index: Int) = "cloud|$household|${snapshot.id}|${snapshot.chunks}|$index".toByteArray()
    private fun encryptAtRest(snapshot: BackupInfo, index: Int, bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(requireNotNull(storageKey), "AES"))
        cipher.updateAAD(aad(snapshot, index))
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(bytes))
    }
    private fun readChunk(snapshot: BackupInfo, index: Int, stored: String): String {
        if (snapshot.encryption == BackupEncryption.END_TO_END) return stored
        val bytes = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(requireNotNull(storageKey), "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(snapshot, index))
        return Base64.getEncoder().encodeToString(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }
    private fun external(info: BackupInfo) = database.objects != null && info.kind != CloudObjectKind.DELTA
    private suspend fun storedChunk(db: Connection, info: BackupInfo, index: Int): String {
        if (!external(info)) return chunkStore(db).readLocalSourceOfTruth(ChunkKey(info.id, index)).single()
        return Base64.getEncoder().encodeToString(database.objects!!.chunk(household, info.id, info.chunks, index))
    }
    private fun head(db: Connection): Long = db.prepareStatement("SELECT COALESCE(MAX(sequence),0) FROM $snapshots WHERE household=? AND complete=1").use {
        it.setString(1, household); it.executeQuery().use { r -> r.next(); r.getLong(1) }
    }
    private fun deleteEntry(db: Connection, id: String) {
        val value = info(db, id) ?: return
        if (external(value)) db.prepareStatement("INSERT INTO ${database.prefix}cloud_gc(household,id) VALUES(?,?) ON CONFLICT DO NOTHING").use {
            it.setString(1, household); it.setString(2, id); it.executeUpdate()
        }
        db.prepareStatement("DELETE FROM $snapshots WHERE household=? AND id=?").use { it.setString(1, household); it.setString(2, id); it.executeUpdate() }
    }
    private fun cleanup(db: Connection, protected: Set<String>) {
        val cutoff = System.currentTimeMillis() - 86_400_000
        val expired = db.prepareStatement("SELECT id FROM $snapshots WHERE household=? AND complete=0 AND created<?").use {
            it.setString(1, household); it.setLong(2, cutoff); it.executeQuery().use { r -> buildList { while(r.next()) add(r.getString(1)) } }
        }
        expired.filterNot { it in protected }.forEach { deleteEntry(db, it) }
        val references = db.prepareStatement("SELECT refs FROM $snapshots WHERE household=? AND kind<>'RECEIPT'").use {
            it.setString(1, household); it.executeQuery().use { r -> buildSet { while(r.next()) addAll(Json.decodeFromString<List<String>>(r.getString(1))) } }
        }
        val unreferenced = db.prepareStatement("SELECT id FROM $snapshots WHERE household=? AND kind='RECEIPT' AND complete=1 AND created<?").use {
            it.setString(1, household); it.setLong(2, cutoff); it.executeQuery().use { r -> buildList { while(r.next()) if (r.getString(1) !in references) add(r.getString(1)) } }
        }
        unreferenced.filterNot { it in protected }.forEach { deleteEntry(db, it) }
        // Only reclaim previously committed garbage. Newly queued objects wait for the next request:
        // storage deletion cannot be rolled back with this SQL transaction.
    }
    private suspend fun collectGarbage(db: Connection) {
        val garbage = db.prepareStatement("SELECT id FROM ${database.prefix}cloud_gc WHERE household=?").use {
            it.setString(1, household); it.executeQuery().use { r -> buildList { while(r.next()) add(r.getString(1)) } }
        }
        for (id in garbage) {
            check(info(db, id) == null)
            database.objects?.delete(household, id)
            db.prepareStatement("DELETE FROM ${database.prefix}cloud_gc WHERE household=? AND id=?").use { it.setString(1, household); it.setString(2, id); it.executeUpdate() }
        }
    }
    private fun info(db: Connection, id: String): BackupInfo? = db.prepareStatement("SELECT chunks,created,mode,key_id,sequence,kind,refs FROM $snapshots WHERE id=? AND household=?").use {
        it.setString(1, id); it.setString(2, household); it.executeQuery().use { r -> if (r.next()) BackupInfo(id, r.getInt(1), r.getLong(2), BackupEncryption.valueOf(r.getString(3)), r.getString(4), r.getLong(5), CloudObjectKind.valueOf(r.getString(6)), Json.decodeFromString(r.getString(7))) else null }
    }
    private fun complete(db: Connection, id: String): Boolean = db.prepareStatement("SELECT complete FROM $snapshots WHERE id=? AND household=?").use {
        it.setString(1, id); it.setString(2, household); it.executeQuery().use { r -> r.next() && r.getInt(1) == 1 }
    }
    private data class ChunkKey(val snapshot: String, val index: Int)
    private fun chunkStore(db: Connection): MutableStore<ChunkKey, List<String>> = MutableStoreBuilder.from<ChunkKey, List<String>, List<String>, List<String>>(
        fetcher = Fetcher.of { _: ChunkKey -> error("Backup chunks are read through the database source of truth") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key: ChunkKey -> flowOf(db.prepareStatement("SELECT ciphertext FROM $chunks WHERE snapshot=? AND idx=? AND household=?").use {
                it.setString(1, key.snapshot); it.setInt(2, key.index); it.setString(3, household)
                it.executeQuery().use { r -> if (r.next()) listOf(Base64.getEncoder().encodeToString(r.getBytes(1))) else emptyList() }
            }) },
            writer = { key, values -> db.prepareStatement("INSERT INTO $chunks(snapshot,idx,ciphertext,household) VALUES(?,?,?,?)").use {
                it.setString(1, key.snapshot); it.setInt(2, key.index); it.setBytes(3, Base64.getDecoder().decode(values.single())); it.setString(4, household); it.executeUpdate(); Unit
            } }
        ),
        converter = Converter.Builder<List<String>, List<String>, List<String>>().fromNetworkToLocal { it }.fromOutputToLocal { it }.build()
    ).disableCache().build(updater = Updater.by<ChunkKey, List<String>, Unit>(post = { _, _ -> UpdaterResult.Success.Typed(Unit) }))
}

@kotlinx.serialization.Serializable
internal data class CloudCommand(val id: String, val requestHash: String, val response: String, val expires: Long)
