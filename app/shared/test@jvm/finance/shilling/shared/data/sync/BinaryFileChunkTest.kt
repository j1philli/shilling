package finance.shilling.shared.data.sync

import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.asReceiptReader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.*

class BinaryFileChunkTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun chunksCannotBeEncodedOrDecodedAsJson() {
        val chunk = FileTransferMessage.FileChunk("r", 0, byteArrayOf(1))
        assertFailsWith<SerializationException> { json.encodeToString<FileTransferMessage>(chunk) }
        val textChunk = """{"type":"finance.shilling.shared.data.sync.FileTransferMessage.FileChunk","receiptId":"r","index":0,"base64Data":"AQ=="}"""
        assertFailsWith<SerializationException> { json.decodeFromString<FileTransferMessage>(textChunk) }
    }

    @Test
    fun frameHasStableNetworkByteOrderAndRejectsMalformedInputs() {
        val message = FileTransferMessage.FileChunk("r", 258, byteArrayOf(7, 8))
        val frame = BinaryFileChunkCodec.encode(message)
        assertContentEquals(byteArrayOf(83, 72, 70, 1, 0, 1, 0, 0, 1, 2, 114, 7, 8), frame)
        val decoded = BinaryFileChunkCodec.decode(frame)
        assertEquals("r", decoded.receiptId)
        assertEquals(258, decoded.index)
        assertContentEquals(message.bytes, decoded.bytes)
        listOf(
            byteArrayOf(), frame.copyOf(10), frame.copyOf(11),
            frame.copyOf().also { it[3] = 2 },
            frame.copyOf().also { it[4] = 127 },
            frame.copyOf().also { it[6] = -1 },
            frame.copyOf().also { it[10] = -1 },
            ByteArray(FileTransferManager.CHUNK_SIZE + 1035)
        ).forEach { invalid -> assertFails { BinaryFileChunkCodec.decode(invalid) } }
    }

    @Test
    fun transfersAlwaysUseBinaryChunksAndRoundTrip() = runBlocking {
        val id = "r-\"雪\\"
        val bytes = ByteArray(FileTransferManager.CHUNK_SIZE * 2 + 7) { (it % 251).toByte() }
        val source = MemoryFiles().also { it.store(id, "test.bin", bytes) }
        val target = MemoryFiles()
        val receiver = FileTransferManager(target)
        val messages = FileTransferManager(source).prepareTransfer(id, "test.bin")!!
        var binaryCount = 0
        for ((_, encoded) in encodeFileMessages(messages, json).toList()) {
            val decoded = when (encoded) {
                is EncodedFileMessage.Binary -> {
                    binaryCount++
                    assertTrue(encoded.value.size <= FileTransferManager.CHUNK_SIZE + 1034)
                    BinaryFileChunkCodec.decode(encoded.value)
                }
                is EncodedFileMessage.Text -> json.decodeFromString<FileTransferMessage>(encoded.value)
            }
            when (decoded) {
                is FileTransferMessage.FileHeader -> receiver.handleHeader(decoded)
                is FileTransferMessage.FileChunk -> {
                    receiver.handleChunk(decoded)
                    receiver.handleChunk(decoded) // Duplicate delivery remains harmless.
                }
                is FileTransferMessage.FileComplete -> assertTrue(receiver.finalizeTransfer(id, "test.bin"))
                else -> fail("Unexpected transfer message")
            }
        }
        assertEquals(3, binaryCount)
        assertContentEquals(bytes, target.read(id))
    }

    @Test
    fun oversizedChunksAndInvalidReceiptIdsAreRejected() = runBlocking {
        val target = MemoryFiles()
        val receiver = FileTransferManager(target)
        receiver.handleHeader(FileTransferMessage.FileHeader("r", 1, 1))
        assertFalse(receiver.handleChunk(FileTransferMessage.FileChunk("r", 0, ByteArray(2))))
        assertFalse(receiver.finalizeTransfer("r", "file"))
        for (id in listOf("", "雪".repeat(400), "\uD800")) {
            val source = MemoryFiles().also { it.store(id, "file", byteArrayOf(1)) }
            assertNull(FileTransferManager(source).prepareTransfer(id, "file"))
        }
    }

    private class MemoryFiles : ReceiptFileStore {
        private val files = mutableMapOf<String, ByteArray>()
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) { files[receiptId] = bytes }
        override suspend fun read(receiptId: String) = files[receiptId]
        override suspend fun openReader(receiptId: String) = read(receiptId)?.asReceiptReader()
        override suspend fun hasFile(receiptId: String) = receiptId in files
        override suspend fun delete(receiptId: String) { files.remove(receiptId) }
        override suspend fun clearAll() { files.clear() }
        override suspend fun openExternally(receiptId: String, originalName: String) {}
    }
}
