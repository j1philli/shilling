package finance.shilling.shared.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.Flow

interface PeerSyncManager {
    val incomingChanges: SharedFlow<ChangeMessage>
    val incomingFileMessages: SharedFlow<Pair<String, FileTransferMessage>> // peerId to message
    /** Emits a peer device ID whenever a P2P data channel becomes ready for communication. */
    val peerConnected: SharedFlow<String>
    fun start(scope: CoroutineScope)
    suspend fun broadcast(change: ChangeMessage)
    suspend fun sendToPeer(peerId: String, change: ChangeMessage)
    suspend fun sendFileMessage(peerId: String, message: FileTransferMessage)
    /** Send a flow in order; backpressure also bounds receipt storage reads. */
    suspend fun sendFileMessages(peerId: String, messages: Flow<FileTransferMessage>): Int {
        var sent = 0
        messages.collect { message ->
            sendFileMessage(peerId, message)
            sent++
            if (sent % 5 == 0) kotlinx.coroutines.yield()
        }
        return sent
    }
    suspend fun broadcastFileMessage(message: FileTransferMessage)
    fun stop()
}
