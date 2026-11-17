package finance.shilling.shared.data.sync

import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.asReceiptReader
import kotlinx.coroutines.flow.toList
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that verify the file transfer pipeline works correctly.
 *
 * Simulates the full FileRequest → FileHeader → FileChunk(s) → FileComplete flow
 * using in-memory file stores to validate that file bytes transfer correctly between
 * two FileTransferManager instances (sender and receiver).
 */
class FileTransferTest {

    /** Simple in-memory ReceiptFileStore for testing. */
    private class InMemoryFileStore : ReceiptFileStore {
        val files = mutableMapOf<String, ByteArray>()

        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
            files[receiptId] = bytes
        }

        override suspend fun read(receiptId: String): ByteArray? = files[receiptId]
        override suspend fun openReader(receiptId: String) = read(receiptId)?.asReceiptReader()

        override suspend fun hasFile(receiptId: String): Boolean = receiptId in files

        override suspend fun delete(receiptId: String) {
            files.remove(receiptId)
        }

        override suspend fun clearAll() {
            files.clear()
        }

        override suspend fun openExternally(receiptId: String, originalName: String) {}
    }

    @Test
    fun prepareTransferReturnsNullWhenFileNotInStore() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        val result = ftm.prepareTransfer("nonexistent", "file.jpg")?.toList()
        assertNull(result, "prepareTransfer should return null when file is not in store")
    }

    @Test
    fun prepareTransferCreatesCorrectMessageSequence() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        // Store a small file (less than one chunk)
        val bytes = ByteArray(1024) { it.toByte() }
        store.store("r-1", "photo.jpg", bytes)

        val messages = ftm.prepareTransfer("r-1", "photo.jpg")?.toList()
        assertNotNull(messages)

        // Should be: 1 header + 1 chunk + 1 complete = 3 messages
        assertEquals(3, messages.size, "Small file should produce exactly 3 messages")

        val header = messages[0]
        assertTrue(header is FileTransferMessage.FileHeader)
        assertEquals("r-1", header.receiptId)
        assertEquals(1024, header.totalSize)
        assertEquals(1, header.chunkCount)
        assertEquals("image/jpeg", header.mimeType)

        val chunk = messages[1]
        assertTrue(chunk is FileTransferMessage.FileChunk)
        assertEquals("r-1", chunk.receiptId)
        assertEquals(0, chunk.index)

        val complete = messages[2]
        assertTrue(complete is FileTransferMessage.FileComplete)
        assertEquals("r-1", complete.receiptId)
    }

    @Test
    fun prepareTransferChunksLargeFile() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        // Store a file larger than one chunk.
        val bytes = ByteArray(100_000) { (it % 256).toByte() }
        store.store("r-big", "big.pdf", bytes)

        val messages = ftm.prepareTransfer("r-big", "big.pdf")?.toList()
        assertNotNull(messages)

        val expectedChunks = (100_000 + FileTransferManager.CHUNK_SIZE - 1) / FileTransferManager.CHUNK_SIZE
        assertEquals(expectedChunks + 2, messages.size, "Should have header + $expectedChunks chunks + complete")

        val header = messages.first() as FileTransferMessage.FileHeader
        assertEquals(100_000, header.totalSize)
        assertEquals(expectedChunks, header.chunkCount)
        assertEquals("application/pdf", header.mimeType)
    }

    @Test
    fun binaryFrameStaysUnderSafeEnvelope() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        // Exactly one full-sized transfer chunk.
        val bytes = ByteArray(FileTransferManager.CHUNK_SIZE) { (it % 256).toByte() }
        store.store("r-safe-chunk", "safe.bin", bytes)

        val messages = ftm.prepareTransfer("r-safe-chunk", "safe.bin")?.toList()
        assertNotNull(messages)

        val chunk = messages.filterIsInstance<FileTransferMessage.FileChunk>().first()
        val encoded = BinaryFileChunkCodec.encode(chunk)
        assertTrue(encoded.size < 18 * 1024, "Binary frame exceeded safe envelope")
    }

    /**
     * Simulates the complete transfer pipeline:
     * 1. Sender prepares transfer messages from its file store
     * 2. Receiver processes header, chunks, and complete messages
     * 3. Receiver ends up with identical bytes in its file store
     */
    @Test
    fun endToEndFileTransfer() = runBlockingTest {
        val senderStore = InMemoryFileStore()
        val receiverStore = InMemoryFileStore()
        val sender = FileTransferManager(senderStore)
        val receiver = FileTransferManager(receiverStore)

        // Sender has a file
        val originalBytes = ByteArray(75_000) { (it % 256).toByte() }
        senderStore.store("r-test", "receipt.jpg", originalBytes)

        // Sender prepares transfer
        val messages = sender.prepareTransfer("r-test", "receipt.jpg")?.toList()
        assertNotNull(messages)

        // Receiver processes all messages
        for (msg in messages) {
            when (msg) {
                is FileTransferMessage.FileHeader -> receiver.handleHeader(msg)
                is FileTransferMessage.FileChunk -> receiver.handleChunk(msg)
                is FileTransferMessage.FileComplete -> {
                    val ok = receiver.finalizeTransfer(msg.receiptId, "receipt.jpg")
                    assertTrue(ok, "finalizeTransfer should succeed")
                }
                else -> {} // FileRequest/FileNotAvailable not part of transfer stream
            }
        }

        // Receiver now has the file
        assertTrue(receiverStore.hasFile("r-test"), "Receiver should have the file after transfer")
        val receivedBytes = receiverStore.read("r-test")
        assertNotNull(receivedBytes)
        assertEquals(originalBytes.size, receivedBytes.size, "File size should match")
        assertTrue(originalBytes.contentEquals(receivedBytes), "File bytes should be identical")
    }

    @Test
    fun streamedTransferAcceptsReorderingAndIgnoresDuplicates() = runBlockingTest {
        val source = InMemoryFileStore()
        val target = InMemoryFileStore()
        val original = ByteArray(FileTransferManager.CHUNK_SIZE * 2 + 7) { (it % 251).toByte() }
        source.store("r", "receipt.bin", original)
        val messages = FileTransferManager(source).prepareTransfer("r", "receipt.bin")!!.toList()
        val receiver = FileTransferManager(target)
        receiver.handleHeader(messages.first() as FileTransferMessage.FileHeader)
        val chunks = messages.filterIsInstance<FileTransferMessage.FileChunk>()
        for (chunk in chunks.reversed()) {
            receiver.handleChunk(chunk)
            receiver.handleChunk(chunk)
        }
        assertTrue(receiver.finalizeTransfer("r", "receipt.bin"))
        assertTrue(original.contentEquals(target.read("r")!!))
    }

    @Test
    fun invalidChunksCannotCompleteOrOverwriteAReceipt() = runBlockingTest {
        val target = InMemoryFileStore()
        val receiver = FileTransferManager(target)
        receiver.handleHeader(FileTransferMessage.FileHeader("r", 10, 1))
        assertFalse(receiver.handleChunk(FileTransferMessage.FileChunk("r", 0, byteArrayOf())))
        assertFalse(receiver.handleChunk(FileTransferMessage.FileChunk("r", 0, ByteArray(3))))
        assertFalse(receiver.finalizeTransfer("r", "receipt.bin"))
        assertFalse(target.hasFile("r"))
    }

    @Test
    fun finalizeTransferFailsWithMissingSession() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        val ok = ftm.finalizeTransfer("nonexistent", "file.jpg")
        assertFalse(ok, "finalizeTransfer should fail with no session")
    }

    @Test
    fun finalizeTransferFailsWithIncompleteChunks() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        // Start a session but don't send all chunks
        ftm.handleHeader(FileTransferMessage.FileHeader("r-partial", FileTransferManager.CHUNK_SIZE * 3, 3))
        ftm.handleChunk(FileTransferMessage.FileChunk("r-partial", 0, ByteArray(FileTransferManager.CHUNK_SIZE)))
        // Missing chunk 1 and 2

        val ok = ftm.finalizeTransfer("r-partial", "file.jpg")
        assertFalse(ok, "finalizeTransfer should fail with incomplete chunks")
        assertFalse(store.hasFile("r-partial"), "File should not be stored with incomplete transfer")
    }

    @Test
    fun cancelTransferCleansUpSession() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        ftm.handleHeader(FileTransferMessage.FileHeader("r-cancel", 1024, 1))
        ftm.cancelTransfer("r-cancel")

        // After cancel, finalize should fail (session removed)
        val ok = ftm.finalizeTransfer("r-cancel", "file.jpg")
        assertFalse(ok, "finalizeTransfer should fail after cancel")
    }

    @Test
    fun rejectsInconsistentHeaderAndMalformedChunks() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        ftm.handleHeader(FileTransferMessage.FileHeader("bad-header", 10, 2))
        assertFalse(ftm.handleChunk(FileTransferMessage.FileChunk("bad-header", 0, ByteArray(3))))
        assertFalse(ftm.finalizeTransfer("bad-header", "file.jpg"))

        ftm.handleHeader(FileTransferMessage.FileHeader("valid-header", 10, 1))
        assertFalse(ftm.handleChunk(FileTransferMessage.FileChunk("valid-header", 0, ByteArray(0))))
        assertFalse(ftm.handleChunk(FileTransferMessage.FileChunk("valid-header", 0, ByteArray(3))))
        assertFalse(ftm.handleChunk(FileTransferMessage.FileChunk("valid-header", 0, ByteArray(40_000))))
        assertFalse(ftm.finalizeTransfer("valid-header", "file.jpg"))
        assertFalse(store.hasFile("valid-header"))
    }
}

/** Simple blocking test helper for JVM tests. */
private fun runBlockingTest(block: suspend () -> Unit) {
    var error: Throwable? = null
    var completed = false
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) {
            error = result.exceptionOrNull()
            completed = true
        }
    })
    check(completed) { "Test coroutine suspended unexpectedly" }
    error?.let { throw it }
}
