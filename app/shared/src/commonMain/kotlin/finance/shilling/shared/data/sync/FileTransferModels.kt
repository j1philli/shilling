package finance.shilling.shared.data.sync

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

    @Serializable
    data class FileChunk(
        val receiptId: String,
        val index: Int,
        val base64Data: String
    ) : FileTransferMessage()

    @Serializable
    data class FileComplete(val receiptId: String) : FileTransferMessage()

    @Serializable
    data class FileNotAvailable(val receiptId: String) : FileTransferMessage()
}
