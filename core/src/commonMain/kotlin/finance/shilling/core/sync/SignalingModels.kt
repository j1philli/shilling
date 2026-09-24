package finance.shilling.core.sync

import kotlinx.serialization.Serializable

@Serializable
sealed class SignalingMessage {
    @Serializable
    data class Offer(val fromDeviceId: String, val toDeviceId: String, val sdp: String) : SignalingMessage()
    @Serializable
    data class Answer(val fromDeviceId: String, val toDeviceId: String, val sdp: String) : SignalingMessage()
    @Serializable
    data class IceCandidate(
        val fromDeviceId: String,
        val toDeviceId: String,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : SignalingMessage()
    @Serializable
    data class Join(val deviceId: String, val householdId: String, val accessToken: String? = null) : SignalingMessage()
    @Serializable
    data class PeerList(val deviceIds: List<String>) : SignalingMessage()
}

@Serializable
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
    val credentialType: String? = null
)

@Serializable
data class IceServersResponse(
    val iceServers: List<IceServerConfig>,
    val ttlSeconds: Int
)
