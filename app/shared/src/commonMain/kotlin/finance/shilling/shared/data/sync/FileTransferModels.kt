package finance.shilling.shared.data.sync

import finance.shilling.shared.data.ReceiptFileBlock
import kotlinx.serialization.Serializable

@Serializable
sealed class FileTransferMessage {
    @Serializable
    data class FileRequest(val receiptId: String) : FileTransferMessage()

    @Serializable
    data class FileHeader(
        val receiptId: String,
        val totalSize: Int,
        val chunkCount: Int,
        val mimeType: String? = null
    ) : FileTransferMessage()

    // Chunks use BinaryFileChunkCodec exclusively; only control messages are JSON.
    class FileChunk private constructor(
        val receiptId: String,
        val index: Int,
        private val payload: ByteArray,
        private val payloadOffset: Int,
        internal val payloadSize: Int
    ) : FileTransferMessage() {
        constructor(receiptId: String, index: Int, bytes: ByteArray) :
            this(receiptId, index, bytes, 0, bytes.size)

        init {
            require(payloadOffset >= 0 && payloadSize >= 0 && payloadOffset <= payload.size - payloadSize)
        }

        // Materialize only for callers that explicitly request a standalone chunk.
        val bytes: ByteArray get() = if (payloadOffset == 0 && payloadSize == payload.size) payload
            else payload.copyOfRange(payloadOffset, payloadOffset + payloadSize)

        internal fun copyPayloadInto(destination: ByteArray, destinationOffset: Int) {
            payload.copyInto(destination, destinationOffset, payloadOffset, payloadOffset + payloadSize)
        }

        internal fun payloadBlock() = ReceiptFileBlock(payload, payloadOffset, payloadSize)

        companion object {
            internal fun slice(receiptId: String, index: Int, bytes: ByteArray, offset: Int, size: Int) =
                FileChunk(receiptId, index, bytes, offset, size)
        }
    }

    @Serializable
    data class FileComplete(val receiptId: String) : FileTransferMessage()

    @Serializable
    data class FileNotAvailable(val receiptId: String) : FileTransferMessage()
}
