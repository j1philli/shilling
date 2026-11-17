package finance.shilling.shared.data.store

import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ReceiptFileReader
import finance.shilling.shared.data.ReceiptFileBlock
import finance.shilling.shared.data.ReceiptFileWriter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/** Platform persistence used exclusively by the receipt file SourceOfTruth. */
interface ReceiptFileStorage {
    suspend fun store(receiptId: String, fileName: String, bytes: ByteArray)
    suspend fun read(receiptId: String): ByteArray?
    suspend fun hasFile(receiptId: String): Boolean
    suspend fun delete(receiptId: String)
    suspend fun clearAll()
}

/** Backends with efficient bounded reads. SQL blobs retain their buffered snapshot path. */
interface RangedReceiptFileStorage : ReceiptFileStorage {
    suspend fun size(receiptId: String): Long?
    suspend fun readRange(receiptId: String, offset: Long, byteCount: Int): ByteArray?
}

/** Atomic native receive staging; all operations are dispatched through Store5. */
interface StagedReceiptFileStorage : RangedReceiptFileStorage {
    suspend fun beginStage(token: String, size: Long)
    suspend fun writeStage(token: String, offset: Long, block: ReceiptFileBlock)
    suspend fun commitStage(token: String, receiptId: String)
    suspend fun abortStage(token: String)
}

private sealed interface FileKey {
    data class Contents(val id: String) : FileKey
    data class Presence(val id: String) : FileKey
    data class Info(val id: String) : FileKey
    data class Range(val id: String, val version: FileVersion, val offset: Long, val byteCount: Int) : FileKey
    data class StageBegin(val token: String) : FileKey
    data class StageWrite(val token: String, val sequence: Int) : FileKey
    data class StageCommit(val token: String) : FileKey
    data class StageAbort(val token: String) : FileKey
    data object All : FileKey
}

private class FileVersion

private data class FileValue(
    val bytes: ByteArray? = null,
    val fileName: String = "",
    val exists: Boolean = false,
    val size: Long? = null,
    val version: FileVersion? = null,
    val receiptId: String = "",
    val offset: Long = 0,
    val block: ReceiptFileBlock? = null,
    val readFailure: Exception? = null
)

/** Uncached to avoid retaining receipt blobs and to keep presence checks cheap. */
@OptIn(ExperimentalStoreApi::class)
class Store5ReceiptFileStore(
    private val storage: ReceiptFileStorage,
    private val openFile: suspend (String, String, ByteArray) -> Unit,
    private val storageContext: CoroutineContext = EmptyCoroutineContext
) : ReceiptFileStore {
    // Lock only local I/O operations; no lock or file descriptor spans a network send.
    private val storageMutex = Mutex()
    private val versions = mutableMapOf<String, FileVersion>()
    private val store = MutableStoreBuilder.from<FileKey, FileValue, FileValue, FileValue>(
        fetcher = Fetcher.of { _: FileKey -> error("Receipt files are local-only") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key -> flow {
                // Store5's reader scope must survive a failed file read so a retry can
                // succeed. Carry errors as values and throw them at the repository edge.
                val value = try { accessStorage {
                    when (key) {
                        is FileKey.Contents -> FileValue(bytes = storage.read(key.id))
                        is FileKey.Presence -> FileValue(exists = storage.hasFile(key.id))
                        is FileKey.Info -> (storage as RangedReceiptFileStorage).size(key.id)?.let { size -> FileValue(
                            size = size, version = versions.getOrPut(key.id) { FileVersion() }
                        ) } ?: FileValue()
                        is FileKey.Range -> {
                            check(versions[key.id] === key.version) { "Receipt changed during read" }
                            FileValue(bytes = (storage as RangedReceiptFileStorage).readRange(key.id, key.offset, key.byteCount))
                        }
                        is FileKey.StageBegin, is FileKey.StageWrite,
                        is FileKey.StageCommit, is FileKey.StageAbort -> FileValue()
                        FileKey.All -> FileValue()
                    }
                } } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    FileValue(readFailure = failure)
                }
                emit(value)
            } },
            writer = { key, value -> accessStorage {
                when (key) {
                    is FileKey.Contents -> {
                        storage.store(key.id, value.fileName, requireNotNull(value.bytes))
                        versions.remove(key.id)
                    }
                    is FileKey.StageBegin -> (storage as StagedReceiptFileStorage).beginStage(key.token, requireNotNull(value.size))
                    is FileKey.StageWrite -> (storage as StagedReceiptFileStorage).writeStage(
                        key.token, value.offset, requireNotNull(value.block)
                    )
                    is FileKey.StageCommit -> {
                        (storage as StagedReceiptFileStorage).commitStage(key.token, value.receiptId)
                        versions.remove(value.receiptId)
                    }
                    is FileKey.StageAbort -> (storage as StagedReceiptFileStorage).abortStage(key.token)
                    else -> error("Receipt read keys are read-only")
                }
            } },
            delete = { key -> accessStorage {
                when (key) {
                    is FileKey.Contents -> { versions.remove(key.id); storage.delete(key.id) }
                    FileKey.All -> { versions.clear(); storage.clearAll() }
                    else -> error("Receipt read keys are read-only")
                }
            } }
        ),
        converter = Converter.Builder<FileValue, FileValue, FileValue>()
            .fromNetworkToLocal { it }.fromOutputToLocal { it }.build()
    ).disableCache().build(
        updater = Updater.by(post = { _: FileKey, _: FileValue -> UpdaterResult.Success.Typed(Unit) })
    )

    override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
        store.writeLocally(StoreWriteRequest.of<FileKey, FileValue, Unit>(
            FileKey.Contents(receiptId), FileValue(bytes, fileName, true)
        ))
    }

    override suspend fun read(receiptId: String): ByteArray? =
        readValue(FileKey.Contents(receiptId)).bytes

    override suspend fun openReader(receiptId: String): ReceiptFileReader? {
        if (storage !is RangedReceiptFileStorage) {
            val bytes = read(receiptId) ?: return null
            return object : ReceiptFileReader {
                override val size = bytes.size.toLong()
                override suspend fun readRange(offset: Long, byteCount: Int): ReceiptFileBlock {
                    require(offset >= 0 && byteCount in 1..ReceiptFileReader.MAX_READ_BYTES && offset <= size - byteCount)
                    return ReceiptFileBlock(bytes, offset.toInt(), byteCount)
                }
            }
        }
        val info = readValue(FileKey.Info(receiptId))
        val fileSize = info.size ?: return null
        val version = requireNotNull(info.version)
        return object : ReceiptFileReader {
            override val size = fileSize
            override suspend fun readRange(offset: Long, byteCount: Int): ReceiptFileBlock {
                require(offset >= 0 && byteCount in 1..ReceiptFileReader.MAX_READ_BYTES && offset <= size - byteCount)
                val bytes = readValue(FileKey.Range(receiptId, version, offset, byteCount)).bytes
                check(bytes != null && bytes.size == byteCount) { "Receipt missing or truncated during read" }
                return ReceiptFileBlock(bytes)
            }
        }
    }

    override suspend fun openWriter(receiptId: String, size: Long): ReceiptFileWriter? {
        if (storage !is StagedReceiptFileStorage) return null
        require(size in 1L..(50L * 1024 * 1024))
        val token = Random.nextLong().toULong().toString(16)
        writeStage(FileKey.StageBegin(token), FileValue(size = size))
        return object : ReceiptFileWriter {
            private var sequence = 0
            private var finished = false

            override suspend fun writeRange(offset: Long, block: ReceiptFileBlock) {
                check(!finished)
                require(block.size in 1..16_384 && offset >= 0 && offset <= size - block.size)
                writeStage(FileKey.StageWrite(token, sequence++), FileValue(offset = offset, block = block))
            }

            override suspend fun commit(fileName: String) {
                check(!finished)
                writeStage(FileKey.StageCommit(token), FileValue(receiptId = receiptId, fileName = fileName))
                finished = true
            }

            override suspend fun abort() {
                if (finished) return
                writeStage(FileKey.StageAbort(token), FileValue())
                finished = true
            }
        }
    }

    private suspend fun writeStage(key: FileKey, value: FileValue) {
        store.writeLocally(StoreWriteRequest.of<FileKey, FileValue, Unit>(key, value))
    }

    override suspend fun hasFile(receiptId: String): Boolean =
        readValue(FileKey.Presence(receiptId)).exists

    private suspend fun readValue(key: FileKey): FileValue =
        store.readLocalSourceOfTruth(key).also { it.readFailure?.let { failure -> throw failure } }

    private suspend fun <T> accessStorage(block: suspend () -> T): T =
        withContext(storageContext) { storageMutex.withLock { block() } }

    override suspend fun delete(receiptId: String) = store.clear(FileKey.Contents(receiptId))
    override suspend fun clearAll() = store.clear(FileKey.All)

    override suspend fun openExternally(receiptId: String, originalName: String) {
        val bytes = read(receiptId) ?: return
        openFile(receiptId, originalName, bytes)
    }
}
