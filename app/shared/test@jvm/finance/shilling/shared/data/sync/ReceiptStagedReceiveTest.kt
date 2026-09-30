package finance.shilling.shared.data.sync

import finance.shilling.shared.data.ReceiptFileBlock
import finance.shilling.shared.data.store.StagedReceiptFileStorage
import finance.shilling.shared.data.store.Store5ReceiptFileStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptStagedReceiveTest {
    @Test
    fun stagedChunksStayInvisibleUntilCompleteAndAcceptReordering(): Unit = runBlocking {
        withTimeout(5_000) {
            val storage = MemoryStageStorage()
            val files = Store5ReceiptFileStore(storage, { _, _, _ -> })
            val receiver = FileTransferManager(files)
            files.store("r", "old", byteArrayOf(9, 9))
            val size = FileTransferManager.CHUNK_SIZE + 3
            receiver.handleHeader(FileTransferMessage.FileHeader("r", size, 2))
            receiver.handleHeader(FileTransferMessage.FileHeader("r", size, 2))
            assertContentEquals(byteArrayOf(9, 9), files.read("r"))
            val last = FileTransferMessage.FileChunk("r", 1, byteArrayOf(1, 2, 3))
            val first = FileTransferMessage.FileChunk("r", 0, ByteArray(FileTransferManager.CHUNK_SIZE) { 4 })
            assertFalse(receiver.handleChunk(last))
            assertFalse(receiver.handleChunk(last))
            assertTrue(receiver.handleChunk(first))
            assertTrue(receiver.handleChunk(first))
            assertEquals(2, storage.stageWrites)
            assertContentEquals(byteArrayOf(9, 9), files.read("r"))
            assertTrue(receiver.finalizeTransfer("r", "new"))
            assertContentEquals(ByteArray(FileTransferManager.CHUNK_SIZE) { 4 } + byteArrayOf(1, 2, 3), files.read("r"))
            assertTrue(storage.stages.isEmpty())
        }
    }

    @Test
    fun incompleteAndCancelledTransfersLeaveExistingFileIntact(): Unit = runBlocking {
        withTimeout(5_000) {
            val storage = MemoryStageStorage()
            val files = Store5ReceiptFileStore(storage, { _, _, _ -> })
            val receiver = FileTransferManager(files)
            files.store("r", "old", byteArrayOf(7))
            receiver.handleHeader(FileTransferMessage.FileHeader("r", FileTransferManager.CHUNK_SIZE + 1, 2))
            receiver.handleChunk(FileTransferMessage.FileChunk("r", 0, ByteArray(FileTransferManager.CHUNK_SIZE)))
            assertFalse(receiver.finalizeTransfer("r", "new"))
            assertTrue(storage.stages.isEmpty())
            assertContentEquals(byteArrayOf(7), files.read("r"))
            receiver.handleHeader(FileTransferMessage.FileHeader("r", 1, 1))
            receiver.cancelTransfer("r")
            assertTrue(storage.stages.isEmpty())
            assertContentEquals(byteArrayOf(7), files.read("r"))
        }
    }

    private class MemoryStageStorage : StagedReceiptFileStorage {
        val files = mutableMapOf<String, ByteArray>()
        val stages = mutableMapOf<String, ByteArray>()
        var stageWrites = 0
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) { files[receiptId] = bytes }
        override suspend fun read(receiptId: String) = files[receiptId]
        override suspend fun size(receiptId: String) = files[receiptId]?.size?.toLong()
        override suspend fun readRange(receiptId: String, offset: Long, byteCount: Int) =
            files[receiptId]?.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
        override suspend fun hasFile(receiptId: String) = receiptId in files
        override suspend fun delete(receiptId: String) { files.remove(receiptId) }
        override suspend fun clearAll() { files.clear(); stages.clear() }
        override suspend fun beginStage(token: String, size: Long) {
            check(stages.put(token, ByteArray(size.toInt())) == null)
        }
        override suspend fun writeStage(token: String, offset: Long, block: ReceiptFileBlock) {
            block.bytes.copyInto(requireNotNull(stages[token]), offset.toInt(), block.offset, block.offset + block.size)
            stageWrites++
        }
        override suspend fun commitStage(token: String, receiptId: String) {
            files[receiptId] = requireNotNull(stages.remove(token))
        }
        override suspend fun abortStage(token: String) { stages.remove(token) }
    }
}
