package finance.shilling.shared.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

interface PeerSyncManager {
    val incomingChanges: SharedFlow<ChangeMessage>
    val incomingFileMessages: SharedFlow<Pair<String, FileTransferMessage>> // peerId to message
    /** Emits a peer device ID whenever a P2P data channel becomes ready for communication. */
    val peerConnected: SharedFlow<String>
    fun start(scope: CoroutineScope)
    suspend fun broadcast(change: ChangeMessage)
    suspend fun sendToPeer(peerId: String, change: ChangeMessage)
    suspend fun sendFileMessage(peerId: String, message: FileTransferMessage)
    suspend fun broadcastFileMessage(message: FileTransferMessage)
    fun stop()
}
