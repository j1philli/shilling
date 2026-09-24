package finance.shilling.server

import co.touchlab.kermit.Logger
import finance.shilling.core.sync.SignalingMessage
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val log = Logger.withTag("Signaling")

class SignalingHub {
    private val households = ConcurrentHashMap<String, MutableMap<String, WebSocketSession>>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun register(householdId: String, deviceId: String, session: WebSocketSession) {
        val peers = households.getOrPut(householdId) { ConcurrentHashMap() }
        peers[deviceId] = session
        log.i { "[HUB] REGISTER device=$deviceId household=$householdId (total=${peers.size} peers=${peers.keys})" }

        val existingPeerIds = peers.keys.filter { it != deviceId }
        if (existingPeerIds.isNotEmpty()) {
            log.i { "[HUB] Sending PeerList to $deviceId: $existingPeerIds" }
            session.send(json.encodeToString<SignalingMessage>(
                SignalingMessage.PeerList(existingPeerIds)
            ))
            existingPeerIds.forEach { peerId ->
                log.i { "[HUB] Notifying $peerId about new peer $deviceId" }
                try {
                    peers[peerId]?.send(json.encodeToString<SignalingMessage>(
                        SignalingMessage.PeerList(listOf(deviceId))
                    ))
                } catch (e: Exception) {
                    log.w { "[HUB] Failed to notify $peerId: ${e::class.simpleName}: ${e.message}" }
                }
            }
        } else {
            log.i { "[HUB] No existing peers in household $householdId — $deviceId is first" }
        }
    }

    fun unregister(householdId: String, deviceId: String) {
        households[householdId]?.remove(deviceId)
        val remaining = households[householdId]?.keys ?: emptySet()
        log.i { "[HUB] UNREGISTER device=$deviceId household=$householdId (remaining=${remaining.size}: $remaining)" }
    }

    suspend fun relay(householdId: String, fromDeviceId: String, message: SignalingMessage) {
        val peers = households[householdId] ?: run {
            log.w { "[HUB] Relay: no household $householdId found" }
            return
        }

        // Extract target device ID for directed signaling messages
        val toDeviceId = when (message) {
            is SignalingMessage.Offer -> message.toDeviceId
            is SignalingMessage.Answer -> message.toDeviceId
            is SignalingMessage.IceCandidate -> message.toDeviceId
            else -> null
        }

        val msgType = when (message) {
            is SignalingMessage.Offer -> "Offer(to=${message.toDeviceId}, sdpLen=${message.sdp.length})"
            is SignalingMessage.Answer -> "Answer(to=${message.toDeviceId}, sdpLen=${message.sdp.length})"
            is SignalingMessage.IceCandidate -> "IceCandidate(to=${message.toDeviceId}, ${message.candidate.take(50)})"
            is SignalingMessage.PeerList -> "PeerList(${message.deviceIds})"
            is SignalingMessage.Join -> "Join"
        }

        if (toDeviceId != null) {
            // Targeted relay to specific peer
            val targetSession = peers[toDeviceId]
            if (targetSession == null) {
                log.w { "[HUB] RELAY $msgType from $fromDeviceId — target $toDeviceId not found" }
                return
            }
            log.i { "[HUB] RELAY $msgType from $fromDeviceId to $toDeviceId" }
            try {
                targetSession.send(json.encodeToString(message))
            } catch (e: Exception) {
                log.w { "[HUB] Relay send failed to $toDeviceId: ${e::class.simpleName}: ${e.message}" }
            }
        } else {
            // Broadcast to all peers (PeerList, Join)
            val targets = peers.filter { it.key != fromDeviceId }
            log.i { "[HUB] RELAY $msgType from $fromDeviceId to ${targets.keys}" }
            targets.values.forEach { session ->
                try {
                    session.send(json.encodeToString(message))
                } catch (e: Exception) {
                    log.w { "[HUB] Relay send failed: ${e::class.simpleName}: ${e.message}" }
                }
            }
        }
    }
}

fun Routing.signalingRoute(
    hub: SignalingHub,
    tokenVerifier: SupabaseTokenVerifier? = null,
    householdLookup: HouseholdMembershipLookup? = null
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    webSocket("/ws/signal") {
        var deviceId: String? = null
        var householdId: String? = null
        log.i { "[WS] New WebSocket connection from ${call.request.local.remoteHost}" }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val msg = try {
                        json.decodeFromString<SignalingMessage>(text)
                    } catch (e: Exception) {
                        log.w { "[WS] Failed to decode from device=$deviceId: ${e::class.simpleName}: ${e.message}" }
                        log.d { "[WS] Raw frame: ${text.take(200)}" }
                        continue
                    }
                    when (msg) {
                        is SignalingMessage.Join -> {
                            if (tokenVerifier != null) {
                                when (val result = authorizeHostedJoin(
                                    tokenVerifier = tokenVerifier,
                                    lookup = householdLookup,
                                    accessToken = msg.accessToken,
                                    requestedHouseholdId = msg.householdId
                                )) {
                                    is JoinAuthorizationResult.Authorized -> {
                                        log.i { "[WS] Hosted join authorized user=${result.userId} device=${msg.deviceId} household=${msg.householdId}" }
                                    }
                                    is JoinAuthorizationResult.Rejected -> {
                                        log.w { "[WS] Join rejected for ${msg.deviceId}: ${result.reason}" }
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, result.reason))
                                        return@webSocket
                                    }
                                }
                            }
                            deviceId = msg.deviceId
                            householdId = msg.householdId
                            log.i { "[WS] JOIN device=$deviceId household=$householdId" }
                            hub.register(msg.householdId, msg.deviceId, this)
                        }
                        else -> {
                            log.i { "[WS] RELAY ${msg::class.simpleName} from $deviceId" }
                            householdId?.let { hid ->
                                deviceId?.let { did -> hub.relay(hid, did, msg) }
                            }
                        }
                    }
                }
            }
        } finally {
            log.i { "[WS] DISCONNECT device=$deviceId household=$householdId" }
            if (householdId != null && deviceId != null) {
                hub.unregister(householdId, deviceId)
            }
        }
    }
}
