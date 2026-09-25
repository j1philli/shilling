package finance.shilling.shared.data.sync

import co.touchlab.kermit.Logger
import finance.shilling.shared.data.ReceiptFileStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val log = Logger.withTag("FileTransfer")

@OptIn(ExperimentalEncodingApi::class)
class FileTransferManager(
    private val fileStore: ReceiptFileStore
) {
    private val receiveSessions = mutableMapOf<String, ReceiveSession>()

    companion object {
        // Keep chunk payloads well below iOS WebRTC text-message fragmentation boundaries.
        // 16KB raw data serializes to ~22KB JSON (base64 + metadata), which is stable across
        // browser/native peers in our cross-platform tests and logs.
        const val CHUNK_SIZE = 16 * 1024
        const val MAX_SAFE_SERIALIZED_CHUNK_CHARS = 32 * 1024
        const val MAX_CHUNKS = 10_000
        const val MAX_FILE_BYTES = 50 * 1024 * 1024 // 50MB
    }

    suspend fun prepareTransfer(receiptId: String, originalName: String): List<FileTransferMessage>? {
        val bytes = fileStore.read(receiptId)
        if (bytes == null) {
            log.w { "prepareTransfer: fileStore.read($receiptId) returned null" }
            return null
        }
        log.i { "prepareTransfer: id=$receiptId size=${bytes.size}B name=$originalName" }
        val messages = mutableListOf<FileTransferMessage>()
        val chunkCount = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE

        val ext = originalName.substringAfterLast('.', "").lowercase()
        val mimeType = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "heic" -> "image/heic"
            else -> null
        }

        messages.add(FileTransferMessage.FileHeader(
            receiptId = receiptId,
            totalSize = bytes.size,
            chunkCount = chunkCount,
            mimeType = mimeType
        ))

        for (i in 0 until chunkCount) {
            val offset = i * CHUNK_SIZE
            val end = minOf(offset + CHUNK_SIZE, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            messages.add(FileTransferMessage.FileChunk(
                receiptId = receiptId,
                index = i,
                base64Data = Base64.encode(chunk)
            ))
        }

        messages.add(FileTransferMessage.FileComplete(receiptId))
        return messages
    }

    fun handleHeader(header: FileTransferMessage.FileHeader) {
        if (header.chunkCount <= 0 || header.chunkCount > MAX_CHUNKS) {
            log.w { "handleHeader: rejected ${header.receiptId} invalid chunkCount=${header.chunkCount}" }
            return
        }
        if (header.totalSize <= 0 || header.totalSize > MAX_FILE_BYTES) {
            log.w { "handleHeader: rejected ${header.receiptId} invalid totalSize=${header.totalSize}" }
            return
        }
        val expectedChunks = (header.totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (header.chunkCount != expectedChunks) {
            log.w { "handleHeader: rejected ${header.receiptId} inconsistent chunkCount=${header.chunkCount}" }
            return
        }
        // Don't overwrite an existing session if a peer retries a file request
        // while chunks are arriving over the P2P data channel.
        if (header.receiptId in receiveSessions) {
            log.d { "handleHeader: session already exists for ${header.receiptId}, ignoring duplicate" }
            return
        }
        receiveSessions[header.receiptId] = ReceiveSession(
            receiptId = header.receiptId,
            totalSize = header.totalSize,
            chunkCount = header.chunkCount,
            chunks = arrayOfNulls(header.chunkCount),
            receivedCount = 0
        )
        log.i { "handleHeader: started session ${header.receiptId} size=${header.totalSize} chunks=${header.chunkCount}" }
    }

    fun handleChunk(chunk: FileTransferMessage.FileChunk): Boolean {
        val session = receiveSessions[chunk.receiptId] ?: run {
            log.d { "handleChunk: no session for ${chunk.receiptId} index=${chunk.index}" }
            return false
        }
        if (chunk.index !in session.chunks.indices) {
            log.w {
                "handleChunk: index out of bounds for ${chunk.receiptId}: index=${chunk.index} expected=0..${session.chunkCount - 1}"
            }
            return false
        }
        if (chunk.base64Data.length > MAX_SAFE_SERIALIZED_CHUNK_CHARS) return false
        val decoded = runCatching { Base64.decode(chunk.base64Data) }.getOrNull() ?: return false
        val expectedSize = if (chunk.index == session.chunkCount - 1) {
            session.totalSize - chunk.index * CHUNK_SIZE
        } else {
            CHUNK_SIZE
        }
        if (decoded.size != expectedSize) return false
        val alreadyHadChunk = session.chunks[chunk.index] != null
        session.chunks[chunk.index] = decoded
        if (!alreadyHadChunk) session.receivedCount += 1
        if (!alreadyHadChunk && (session.receivedCount % 10 == 0 || session.receivedCount == session.chunkCount)) {
            log.d {
                "handleChunk: ${session.receiptId} progress=${session.receivedCount}/${session.chunkCount} lastIndex=${chunk.index}"
            }
        }
        return session.isComplete()
    }

    suspend fun finalizeTransfer(receiptId: String, originalName: String): Boolean {
        val session = receiveSessions.remove(receiptId)
        if (session == null) {
            log.w { "finalizeTransfer: no session for $receiptId" }
            return false
        }
        if (!session.isComplete()) {
            val received = session.chunks.count { it != null }
            log.w { "finalizeTransfer: incomplete session for $receiptId ($received/${session.chunkCount} chunks)" }
            return false
        }

        val bytes = ByteArray(session.totalSize)
        var offset = 0
        for (chunk in session.chunks) {
            val data = chunk ?: return false
            if (data.size > bytes.size - offset) return false
            data.copyInto(bytes, offset)
            offset += data.size
        }
        if (offset != session.totalSize) {
            log.w { "finalizeTransfer: size mismatch for $receiptId (got $offset, expected ${session.totalSize})" }
            return false
        }

        fileStore.store(receiptId, originalName, bytes)
        log.i { "finalizeTransfer: stored $receiptId (${bytes.size}B)" }
        return true
    }

    fun cancelTransfer(receiptId: String) {
        receiveSessions.remove(receiptId)
    }

    private class ReceiveSession(
        val receiptId: String,
        val totalSize: Int,
        val chunkCount: Int,
        val chunks: Array<ByteArray?>,
        var receivedCount: Int
    ) {
        fun isComplete(): Boolean = chunks.all { it != null }
    }
}
