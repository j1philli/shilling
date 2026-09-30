package finance.shilling.shared.data.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlin.test.*

class FileMessageEncodingTest {
    @Test
    fun preparationPreservesWireOrderAndEscapedIdentifiers() = runBlocking {
        val id = "receipt-\"雪\\"
        val messages = listOf(
            FileTransferMessage.FileHeader(id, 1, 1, null),
            FileTransferMessage.FileChunk(id, 0, byteArrayOf(1)),
            FileTransferMessage.FileComplete(id)
        )
        val decoded = encodeFileMessages(messages.asFlow(), Json).toList().map { (_, encoded) ->
            when (encoded) {
                is EncodedFileMessage.Text -> Json.decodeFromString<FileTransferMessage>(encoded.value)
                is EncodedFileMessage.Binary -> BinaryFileChunkCodec.decode(encoded.value)
            }
        }
        assertEquals(messages.first(), decoded.first())
        assertEquals(messages.last(), decoded.last())
        val chunk = assertIs<FileTransferMessage.FileChunk>(decoded[1])
        assertEquals(id, chunk.receiptId)
        assertEquals(0, chunk.index)
        assertContentEquals(byteArrayOf(1), chunk.bytes)
    }

    @Test
    fun blockedSenderBoundsReadAheadAndCancellationStopsProducer() = runBlocking {
        withTimeout(5_000) {
            val advances = Channel<Int>(Channel.UNLIMITED)
            val count = AtomicInteger()
            val source = flow {
                repeat(10_000) {
                    advances.trySend(count.incrementAndGet())
                    emit(FileTransferMessage.FileChunk("bounded", it, byteArrayOf(1)))
                }
            }
            val blocked = CompletableDeferred<Unit>()
            val sender = launch {
                encodeFileMessages(source, Json).collect { blocked.await() }
            }
            // One item at the consumer, four queued, one producer suspended at emit.
            repeat(6) { advances.receive() }
            assertEquals(6, count.get())
            sender.cancelAndJoin()
            assertEquals(6, count.get(), "Cancellation must not drain the remaining 9,994 chunks")
        }
    }
}
