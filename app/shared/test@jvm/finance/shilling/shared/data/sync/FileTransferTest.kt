package finance.shilling.shared.data.sync

import finance.shilling.shared.data.ReceiptFileStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

        val result = ftm.prepareTransfer("nonexistent", "file.jpg")
        assertNull(result, "prepareTransfer should return null when file is not in store")
    }

    @Test
    fun prepareTransferCreatesCorrectMessageSequence() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        // Store a small file (less than one chunk)
        val bytes = ByteArray(1024) { it.toByte() }
        store.store("r-1", "photo.jpg", bytes)

        val messages = ftm.prepareTransfer("r-1", "photo.jpg")
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

        val messages = ftm.prepareTransfer("r-big", "big.pdf")
        assertNotNull(messages)

        val expectedChunks = (100_000 + FileTransferManager.CHUNK_SIZE - 1) / FileTransferManager.CHUNK_SIZE
        assertEquals(expectedChunks + 2, messages.size, "Should have header + $expectedChunks chunks + complete")

        val header = messages.first() as FileTransferMessage.FileHeader
        assertEquals(100_000, header.totalSize)
        assertEquals(expectedChunks, header.chunkCount)
        assertEquals("application/pdf", header.mimeType)
    }

    @Test
    fun serializedChunkStaysUnderSafeEnvelope() = runBlockingTest {
        val store = InMemoryFileStore()
        val ftm = FileTransferManager(store)

        // Exactly one full-sized transfer chunk.
        val bytes = ByteArray(FileTransferManager.CHUNK_SIZE) { (it % 256).toByte() }
        store.store("r-safe-chunk", "safe.bin", bytes)

        val messages = ftm.prepareTransfer("r-safe-chunk", "safe.bin")
        assertNotNull(messages)

        val chunk = messages.filterIsInstance<FileTransferMessage.FileChunk>().first()
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val encoded = json.encodeToString<FileTransferMessage>(chunk)

        assertTrue(
            encoded.length <= FileTransferManager.MAX_SAFE_SERIALIZED_CHUNK_CHARS,
            "Serialized chunk length=${encoded.length} exceeded " +
                "safe limit=${FileTransferManager.MAX_SAFE_SERIALIZED_CHUNK_CHARS}"
        )
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
        val messages = sender.prepareTransfer("r-test", "receipt.jpg")
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
        ftm.handleHeader(FileTransferMessage.FileHeader("r-partial", 100_000, 3))
        ftm.handleChunk(FileTransferMessage.FileChunk("r-partial", 0, "AAAA"))
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
