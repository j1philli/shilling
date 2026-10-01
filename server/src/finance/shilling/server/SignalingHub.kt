package finance.shilling.server

import co.touchlab.kermit.Logger
import finance.shilling.core.sync.SignalingMessage
import finance.shilling.core.auth.HostedPlan
import finance.shilling.core.auth.householdLimit
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val log = Logger.withTag("Signaling")

class SignalingHub {
    private val membershipPolicy = Mutex()
    suspend fun <T> withMembershipPolicy(action: suspend () -> T): T = membershipPolicy.withLock { action() }
    private val households = ConcurrentHashMap<String, MutableMap<String, WebSocketSession>>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun register(householdId: String, deviceId: String, session: WebSocketSession) {
        val peers = households.getOrPut(householdId) { ConcurrentHashMap() }
        val previous = peers.put(deviceId, session)
        if (previous != null && previous !== session) {
            runCatching { previous.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device reconnected")) }
        }
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

    fun unregister(householdId: String, deviceId: String, session: WebSocketSession) {
        households[householdId]?.remove(deviceId, session)
        val remaining = households[householdId]?.keys ?: emptySet()
        log.i { "[HUB] UNREGISTER (household peers=${remaining.size})" }
    }

    suspend fun evict(householdId: String, deviceId: String) {
        val peers = households[householdId] ?: return
        val session = peers.remove(deviceId)
        if (session != null) {
            runCatching { session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device removed")) }
        }
        // Notify remaining clients even if the removed device lost signaling while its
        // data channel stayed open. This is control-plane metadata, never entity data.
        peers.values.forEach { peer ->
            runCatching {
                peer.send(json.encodeToString<SignalingMessage>(
                    SignalingMessage.PeerList(emptyList(), removedDeviceIds = listOf(deviceId))
                ))
            }.onFailure { log.w { "[HUB] Failed to notify device removal: ${it::class.simpleName}" } }
        }
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
    householdLookup: HouseholdMembershipLookup? = null,
    entitlements: HostedEntitlementLookup? = null,
    devices: HostedDeviceRegistry? = null,
    spaces: SupabaseSpaceManagement? = null
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
                            if (deviceId != null) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Already joined"))
                                return@webSocket
                            }
                            val joined = hub.withMembershipPolicy {
                                if (tokenVerifier != null) {
                                    when (val result = authorizeHostedJoin(tokenVerifier, householdLookup, msg.accessToken, msg.householdId)) {
                                        is JoinAuthorizationResult.Authorized -> {
                                            val admitted = runCatching {
                                                val ownPlan = entitlements?.accountPlan(result.userId) ?: HostedPlan.FREE
                                                val access = spaces?.execute(result.userId, finance.shilling.core.auth.HostedSpaceCommand("list"), ownPlan.householdLimit())
                                                if (access?.activeSpaceId != msg.householdId || access?.requiresSpaceSelection != false) return@runCatching false
                                                val plan = entitlements?.householdPlan(msg.householdId) ?: HostedPlan.FREE
                                                devices?.register(msg.householdId, msg.deviceId, result.userId, plan) ?: false
                                            }.getOrDefault(false)
                                            if (!admitted) {
                                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device allowance or active space selection unavailable"))
                                                return@withMembershipPolicy false
                                            }
                                        }
                                        is JoinAuthorizationResult.Rejected -> {
                                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, result.reason))
                                            return@withMembershipPolicy false
                                        }
                                    }
                                }
                                deviceId = msg.deviceId
                                householdId = msg.householdId
                                hub.register(msg.householdId, msg.deviceId, this)
                                true
                            }
                            if (!joined) return@webSocket
                            deviceId = msg.deviceId
                            householdId = msg.householdId
                            log.i { "[WS] JOIN" }
                        }
                        else -> {
                            if (deviceId == null || !isValidClientSignal(msg, deviceId)) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid signaling message"))
                                return@webSocket
                            }
                            log.i { "[WS] RELAY ${msg::class.simpleName}" }
                            householdId?.let { hid ->
                                hub.relay(hid, deviceId, msg)
                            }
                        }
                    }
                }
            }
        } finally {
            log.i { "[WS] DISCONNECT" }
            if (householdId != null && deviceId != null) {
                hub.unregister(householdId, deviceId, this)
            }
        }
    }
}

internal fun isValidClientSignal(message: SignalingMessage, deviceId: String): Boolean = when (message) {
    is SignalingMessage.Offer -> message.fromDeviceId == deviceId
    is SignalingMessage.Answer -> message.fromDeviceId == deviceId
    is SignalingMessage.IceCandidate -> message.fromDeviceId == deviceId
    is SignalingMessage.Join, is SignalingMessage.PeerList -> false
}
