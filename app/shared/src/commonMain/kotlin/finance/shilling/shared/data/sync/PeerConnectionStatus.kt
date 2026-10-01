package finance.shilling.shared.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The local device's open WebRTC data channels, never server presence. */
class PeerConnectionStatus {
    private val mutableConnectedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeerIds: StateFlow<Set<String>> = mutableConnectedPeerIds

    fun setConnected(peerIds: Set<String>) {
        mutableConnectedPeerIds.value = peerIds.toSet()
    }
}
