package finance.shilling.shared.data.sync

enum class DataChannelRole {
    OWN_RECEIVE,
    PEER_SEND,
    IGNORE
}

fun resolveDataChannelRole(isOfferer: Boolean, label: String): DataChannelRole =
    when {
        isOfferer && label == "sync" -> DataChannelRole.OWN_RECEIVE
        !isOfferer && label == "reply" -> DataChannelRole.OWN_RECEIVE
        isOfferer && label == "reply" -> DataChannelRole.PEER_SEND
        !isOfferer && label == "sync" -> DataChannelRole.PEER_SEND
        else -> DataChannelRole.IGNORE
    }

/**
 * Prefer peer-created channel for outbound traffic on both sides:
 * - offerer sends on peer "reply" (fallback own "sync")
 * - answerer sends on peer "sync" (fallback own "reply")
 *
 * This keeps the common fast-path on channels created by the remote peer while still
 * supporting fallback when a peer-open event is missing on one platform.
 */
fun <T> selectOutboundChannel(
    isOfferer: Boolean,
    sendChannel: T?,
    receiveChannel: T?
): T? = sendChannel ?: receiveChannel

/**
 * Offerer may need to listen on peer "reply" for answerer-fallback sends.
 * Answerer intentionally avoids listening on peer "sync" due iOS receive instability
 * observed on channels surfaced only via DataChannelEvent.Open.
 */
fun shouldListenOnPeerSendChannel(isOfferer: Boolean): Boolean = isOfferer
