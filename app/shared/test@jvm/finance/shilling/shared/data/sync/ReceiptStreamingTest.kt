package finance.shilling.shared.data.sync

import finance.shilling.shared.data.store.RangedReceiptFileStorage
import finance.shilling.shared.data.store.Store5ReceiptFileStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlin.test.*

class ReceiptStreamingTest {
    @Test
    fun largeTransferReadsBoundedRangesAndPreservesEveryByte() = runBlocking {
        val storage = PatternStorage(FileTransferManager.READ_BLOCK_BYTES * 3L + 13)
        val files = Store5ReceiptFileStore(storage, { _, _, _ -> })
        val messages = assertNotNull(FileTransferManager(files).prepareTransfer("r", "file.bin"))
        assertTrue(storage.ranges.isEmpty(), "Preparing a header must not read file bytes")
        val encoded = encodeFileMessages(messages, Json).toList()
        val chunks = encoded.mapNotNull { (_, message) ->
            (message as? EncodedFileMessage.Binary)?.let { BinaryFileChunkCodec.decode(it.value) }
        }
        assertEquals(storage.fileSize, chunks.sumOf { it.bytes.size.toLong() })
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.index)
            val offset = index.toLong() * FileTransferManager.CHUNK_SIZE
            assertContentEquals(ByteArray(chunk.bytes.size) { ((offset + it) % 251).toByte() }, chunk.bytes)
        }
        assertIs<FileTransferMessage.FileComplete>(encoded.last().first)
        assertEquals(4, storage.ranges.size)
        assertTrue(storage.ranges.all { it.second <= FileTransferManager.READ_BLOCK_BYTES })
    }

    @Test
    fun cancellationStopsReadingAndOversizeFilesAreRejectedBeforeReading() = runBlocking {
        val storage = PatternStorage(FileTransferManager.MAX_FILE_BYTES.toLong())
        val files = Store5ReceiptFileStore(storage, { _, _, _ -> })
        val messages = assertNotNull(FileTransferManager(files).prepareTransfer("r", "file.bin"))
        val firstMessages = encodeFileMessages(messages, Json).take(3).toList()
        assertEquals(3, firstMessages.size)
        assertEquals(1, storage.ranges.size, "Cancelled send must not read later blocks")
        storage.fileSize++
        storage.ranges.clear()
        assertNull(FileTransferManager(files).prepareTransfer("r", "file.bin"))
        assertTrue(storage.ranges.isEmpty())
    }

    private class PatternStorage(var fileSize: Long) : RangedReceiptFileStorage {
        val ranges = mutableListOf<Pair<Long, Int>>()
        override suspend fun size(receiptId: String) = fileSize
        override suspend fun readRange(receiptId: String, offset: Long, byteCount: Int): ByteArray {
            ranges += offset to byteCount
            return ByteArray(byteCount) { ((offset + it) % 251).toByte() }
        }
        override suspend fun read(receiptId: String): ByteArray = error("Whole-file read is forbidden in this fixture")
        override suspend fun hasFile(receiptId: String) = true
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) = error("Read-only fixture")
        override suspend fun delete(receiptId: String) = error("Read-only fixture")
        override suspend fun clearAll() = error("Read-only fixture")
    }
}
