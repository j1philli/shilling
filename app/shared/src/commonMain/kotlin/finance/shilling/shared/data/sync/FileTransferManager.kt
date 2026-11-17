package finance.shilling.shared.data.sync

import co.touchlab.kermit.Logger
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ReceiptFileReader
import finance.shilling.shared.data.ReceiptFileWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val log = Logger.withTag("FileTransfer")

class FileTransferManager(
    private val fileStore: ReceiptFileStore
) {
    private val receiveSessions = mutableMapOf<String, ReceiveSession>()

    companion object {
        // Keep framed payloads below 18 KiB across browser and native peers.
        const val CHUNK_SIZE = 16 * 1024
        const val MAX_CHUNKS = 10_000
        const val MAX_FILE_BYTES = 50 * 1024 * 1024 // 50MB
        const val READ_BLOCK_BYTES = ReceiptFileReader.MAX_READ_BYTES
    }

    suspend fun prepareTransfer(receiptId: String, originalName: String): Flow<FileTransferMessage>? {
        if (!BinaryFileChunkCodec.isValidReceiptId(receiptId)) return null
        val reader = fileStore.openReader(receiptId)
        if (reader == null) {
            log.w { "prepareTransfer: fileStore.openReader($receiptId) returned null" }
            return null
        }
        log.i { "prepareTransfer: id=$receiptId size=${reader.size}B name=$originalName" }
        if (reader.size !in 1L..MAX_FILE_BYTES.toLong()) return null
        val size = reader.size.toInt()
        val chunkCount = (size + CHUNK_SIZE - 1) / CHUNK_SIZE

        val ext = originalName.substringAfterLast('.', "").lowercase()
        val mimeType = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "heic" -> "image/heic"
            else -> null
        }

        // A Store5 block supplies several wire chunks. Native storage reads one small
        // block at a time; SQL storage supplies views of its buffered snapshot.
        return flow {
            emit(FileTransferMessage.FileHeader(receiptId, size, chunkCount, mimeType))
            var fileOffset = 0
            while (fileOffset < size) {
                val blockSize = minOf(READ_BLOCK_BYTES, size - fileOffset)
                val block = reader.readRange(fileOffset.toLong(), blockSize)
                check(block.size == blockSize) { "Receipt truncated during transfer" }
                var blockOffset = 0
                while (blockOffset < blockSize) {
                    val chunkSize = minOf(CHUNK_SIZE, blockSize - blockOffset)
                    emit(FileTransferMessage.FileChunk.slice(
                        receiptId, (fileOffset + blockOffset) / CHUNK_SIZE, block.bytes, block.offset + blockOffset, chunkSize
                    ))
                    blockOffset += chunkSize
                }
                fileOffset += blockSize
            }
            emit(FileTransferMessage.FileComplete(receiptId))
        }
    }

    suspend fun handleHeader(header: FileTransferMessage.FileHeader) {
        if (!BinaryFileChunkCodec.isValidReceiptId(header.receiptId)) return
        if (header.chunkCount <= 0 || header.chunkCount > MAX_CHUNKS) {
            log.w { "handleHeader: rejected ${header.receiptId} invalid chunkCount=${header.chunkCount}" }
            return
        }
        if (header.totalSize <= 0 || header.totalSize > MAX_FILE_BYTES) {
            log.w { "handleHeader: rejected ${header.receiptId} invalid totalSize=${header.totalSize}" }
            return
        }
        // Don't overwrite an existing session — a duplicate FileRequest (retried
        // after reconnect) can trigger a second header while chunks are arriving.
        if (header.receiptId in receiveSessions) {
            log.d { "handleHeader: session already exists for ${header.receiptId}, ignoring duplicate" }
            return
        }
        if (header.chunkCount != (header.totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE ||
            receiveSessions.values.sumOf { it.totalSize } + header.totalSize > MAX_FILE_BYTES
        ) {
            log.w { "handleHeader: invalid chunk layout or receive memory budget exhausted" }
            return
        }
        val writer = fileStore.openWriter(header.receiptId, header.totalSize.toLong())
        receiveSessions[header.receiptId] = ReceiveSession(
            receiptId = header.receiptId, totalSize = header.totalSize,
            chunkCount = header.chunkCount,
            bytes = if (writer == null) ByteArray(header.totalSize) else null,
            writer = writer,
            received = BooleanArray(header.chunkCount), receivedCount = 0
        )
        log.i { "handleHeader: started session ${header.receiptId} size=${header.totalSize} chunks=${header.chunkCount}" }
    }

    suspend fun handleChunk(chunk: FileTransferMessage.FileChunk): Boolean {
        val session = receiveSessions[chunk.receiptId] ?: run {
            log.d { "handleChunk: no session for ${chunk.receiptId} index=${chunk.index}" }
            return false
        }
        if (chunk.index !in session.received.indices) {
            log.w {
                "handleChunk: index out of bounds for ${chunk.receiptId}: index=${chunk.index} expected=0..${session.chunkCount - 1}"
            }
            return false
        }
        if (session.received[chunk.index]) return session.isComplete()
        val offset = chunk.index * CHUNK_SIZE
        val expectedSize = minOf(CHUNK_SIZE, session.totalSize - offset)
        if (chunk.payloadSize != expectedSize) return false
        try {
            val writer = session.writer
            if (writer == null) chunk.copyPayloadInto(requireNotNull(session.bytes), offset)
            else writer.writeRange(offset.toLong(), chunk.payloadBlock())
        } catch (failure: Exception) {
            cancelTransfer(chunk.receiptId)
            throw failure
        }
        session.received[chunk.index] = true
        session.receivedCount += 1
        return session.isComplete()
    }

    suspend fun finalizeTransfer(receiptId: String, originalName: String): Boolean {
        val session = receiveSessions.remove(receiptId)
        if (session == null) {
            log.w { "finalizeTransfer: no session for $receiptId" }
            return false
        }
        if (!session.isComplete()) {
            val received = session.receivedCount
            session.writer?.abort()
            log.w { "finalizeTransfer: incomplete session for $receiptId ($received/${session.chunkCount} chunks)" }
            return false
        }

        val writer = session.writer
        if (writer != null) {
            try { writer.commit(originalName) } catch (failure: Exception) {
                writer.abort()
                throw failure
            }
        } else {
            // SQL-backed targets still use one assembled buffer through Store5.
            fileStore.store(receiptId, originalName, requireNotNull(session.bytes))
        }
        log.i { "finalizeTransfer: stored $receiptId (${session.totalSize}B)" }
        return true
    }

    suspend fun cancelTransfer(receiptId: String) {
        receiveSessions.remove(receiptId)?.writer?.abort()
    }

    private class ReceiveSession(
        val receiptId: String,
        val totalSize: Int,
        val chunkCount: Int,
        val bytes: ByteArray?,
        val writer: ReceiptFileWriter?,
        val received: BooleanArray,
        var receivedCount: Int
    ) {
        fun isComplete(): Boolean = receivedCount == chunkCount
    }
}
