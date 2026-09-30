package finance.shilling.shared.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface EncodedFileMessage {
    data class Text(val value: String) : EncodedFileMessage
    data class Binary(val value: ByteArray) : EncodedFileMessage
}

internal fun encodeFileMessage(message: FileTransferMessage, json: Json): EncodedFileMessage =
    if (message is FileTransferMessage.FileChunk) {
        EncodedFileMessage.Binary(BinaryFileChunkCodec.encode(message))
    } else {
        EncodedFileMessage.Text(json.encodeToString(message))
    }

/** Bounded preparation; cancellation of the sender also stops advancing the lazy source. */
internal fun encodeFileMessages(
    messages: Flow<FileTransferMessage>,
    json: Json
): Flow<Pair<FileTransferMessage, EncodedFileMessage>> = messages
    .map { message -> message to encodeFileMessage(message, json) }
    .buffer(4).flowOn(Dispatchers.Default)
