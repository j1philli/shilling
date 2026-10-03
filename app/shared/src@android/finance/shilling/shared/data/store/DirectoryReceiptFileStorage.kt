package finance.shilling.shared.data.store

import finance.shilling.shared.data.ReceiptFileBlock
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Native file backing for the Store5 source of truth, shared with the isolated fixture. */
class DirectoryReceiptFileStorage(private val directory: File) : StagedReceiptFileStorage {
    init {
        directory.mkdirs()
        // No stage survives process death. Only the exact internal token pattern is removed.
        directory.listFiles()?.filter { it.name.matches(Regex("\\.incoming-[0-9a-f]{1,16}")) }
            ?.forEach { it.delete() }
    }

    private fun stageFile(token: String): File {
        require(token.matches(Regex("[0-9a-f]{1,16}")))
        return File(directory, ".incoming-$token")
    }

    override suspend fun beginStage(token: String, size: Long) {
        require(size > 0)
        val file = stageFile(token)
        check(file.createNewFile()) { "Receipt stage already exists" }
        try {
            RandomAccessFile(file, "rw").use { it.setLength(size) }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    override suspend fun writeStage(token: String, offset: Long, block: ReceiptFileBlock) {
        require(offset >= 0 && block.size > 0)
        val file = stageFile(token)
        RandomAccessFile(file, "rw").use { output ->
            require(offset <= output.length() - block.size)
            output.seek(offset)
            output.write(block.bytes, block.offset, block.size)
        }
    }

    override suspend fun commitStage(token: String, receiptId: String) {
        val destination = File(directory, receiptId)
        require(destination.canonicalFile.parentFile == directory.canonicalFile)
        val source = stageFile(token)
        check(source.isFile) { "Receipt stage missing" }
        try {
            Files.move(source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun abortStage(token: String) {
        stageFile(token).delete()
    }

    override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
        File(directory, receiptId).writeBytes(bytes)
    }

    override suspend fun read(receiptId: String): ByteArray? =
        File(directory, receiptId).takeIf { it.exists() }?.readBytes()

    override suspend fun size(receiptId: String): Long? =
        File(directory, receiptId).takeIf { it.exists() }?.length()

    override suspend fun readRange(receiptId: String, offset: Long, byteCount: Int): ByteArray? {
        require(offset >= 0 && byteCount > 0)
        val file = File(directory, receiptId).takeIf { it.exists() } ?: return null
        return RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            ByteArray(byteCount).also { input.readFully(it) }
        }
    }

    override suspend fun hasFile(receiptId: String) = File(directory, receiptId).exists()
    override suspend fun delete(receiptId: String) { File(directory, receiptId).delete() }
    override suspend fun clearAll() { directory.listFiles()?.forEach { it.delete() } }
}
