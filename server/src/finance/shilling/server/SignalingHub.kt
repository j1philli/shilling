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
        log.i { "[HUB] REGISTER (household peers=${peers.size})" }

        val existingPeerIds = peers.keys.filter { it != deviceId }
        if (existingPeerIds.isNotEmpty()) {
            log.i { "[HUB] Sending PeerList (${existingPeerIds.size} existing peers)" }
            session.send(json.encodeToString<SignalingMessage>(
                SignalingMessage.PeerList(existingPeerIds)
            ))
            existingPeerIds.forEach { peerId ->
                try {
                    peers[peerId]?.send(json.encodeToString<SignalingMessage>(
                        SignalingMessage.PeerList(listOf(deviceId))
                    ))
                } catch (e: Exception) {
                    log.w { "[HUB] Failed to notify peer: ${e::class.simpleName}" }
                }
            }
        } else {
            log.i { "[HUB] First peer in household" }
        }
    }

    fun unregister(householdId: String, deviceId: String) {
        households[householdId]?.remove(deviceId)
        val remaining = households[householdId]?.keys ?: emptySet()
        log.i { "[HUB] UNREGISTER (household peers=${remaining.size})" }
    }

    suspend fun relay(householdId: String, fromDeviceId: String, message: SignalingMessage) {
        val peers = households[householdId] ?: run {
            log.w { "[HUB] Relay: household unavailable" }
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
            is SignalingMessage.Offer -> "Offer"
            is SignalingMessage.Answer -> "Answer"
            is SignalingMessage.IceCandidate -> "IceCandidate"
            is SignalingMessage.PeerList -> "PeerList"
            is SignalingMessage.Join -> "Join"
        }

        if (toDeviceId != null) {
            // Targeted relay to specific peer
            val targetSession = peers[toDeviceId]
            if (targetSession == null) {
                log.w { "[HUB] RELAY $msgType: target unavailable" }
                return
            }
            log.i { "[HUB] RELAY $msgType" }
            try {
                targetSession.send(json.encodeToString(message))
            } catch (e: Exception) {
                log.w { "[HUB] Relay send failed: ${e::class.simpleName}" }
            }
        } else {
            // Broadcast to all peers (PeerList, Join)
            val targets = peers.filter { it.key != fromDeviceId }
            log.i { "[HUB] RELAY $msgType (${targets.size} targets)" }
            targets.values.forEach { session ->
                try {
                    session.send(json.encodeToString(message))
                } catch (e: Exception) {
                    log.w { "[HUB] Relay send failed: ${e::class.simpleName}" }
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
        log.i { "[WS] New WebSocket connection" }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val msg = try {
                        json.decodeFromString<SignalingMessage>(text)
                    } catch (e: Exception) {
                        log.w { "[WS] Failed to decode message: ${e::class.simpleName}" }
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
                                        log.i { "[WS] Hosted join authorized" }
                                    }
                                    is JoinAuthorizationResult.Rejected -> {
                                        log.w { "[WS] Join rejected: ${result.reason}" }
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, result.reason))
                                        return@webSocket
                                    }
                                }
                            }
                            deviceId = msg.deviceId
                            householdId = msg.householdId
                            log.i { "[WS] JOIN" }
                            hub.register(msg.householdId, msg.deviceId, this)
                        }
                        else -> {
                            log.i { "[WS] RELAY ${msg::class.simpleName}" }
                            householdId?.let { hid ->
                                deviceId?.let { did -> hub.relay(hid, did, msg) }
                            }
                        }
                    }
                }
            }
        } finally {
            log.i { "[WS] DISCONNECT" }
            if (householdId != null && deviceId != null) {
                hub.unregister(householdId, deviceId)
            }
        }
    }
}
