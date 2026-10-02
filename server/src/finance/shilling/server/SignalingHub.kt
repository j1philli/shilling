package finance.shilling.server

import co.touchlab.kermit.Logger
import finance.shilling.core.sync.SignalingMessage
import finance.shilling.core.auth.HostedPlan
import finance.shilling.core.auth.householdLimit
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val log = Logger.withTag("Signaling")

class SignalingHub(
    private val queueCapacity: Int = 64,
    private val sendTimeoutMillis: Long = 5_000
) {
    init { require(queueCapacity > 0 && sendTimeoutMillis > 0) }
    private val membershipPolicy = Mutex()
    suspend fun <T> withMembershipPolicy(action: suspend () -> T): T = membershipPolicy.withLock { action() }

    private class Peer(val session: WebSocketSession, capacity: Int) {
        val queue = Channel<String>(capacity)
        val queuedChars = AtomicInteger()
        @Volatile var sender: Job? = null
    }

    private val households = ConcurrentHashMap<String, ConcurrentHashMap<String, Peer>>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun register(householdId: String, deviceId: String, session: WebSocketSession) {
        val peer = Peer(session, queueCapacity)
        var replaced: Peer? = null
        val peers = households.compute(householdId) { _, existing ->
            (existing ?: ConcurrentHashMap()).also { replaced = it.put(deviceId, peer) }
        }!!
        replaced?.let { disconnect(householdId, deviceId, it, "Signaling session replaced") }
        peer.sender = session.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (text in peer.queue) {
                    peer.queuedChars.addAndGet(-text.length)
                    withTimeout(sendTimeoutMillis) { session.send(text) }
                }
            } catch (failure: Exception) {
                if (failure !is CancellationException || failure is TimeoutCancellationException) {
                    log.w { "[HUB] Closing stalled signaling peer: ${failure::class.simpleName}" }
                    session.cancel(CancellationException("Signaling send failed", failure))
                }
            } finally {
                unregister(householdId, deviceId, session)
            }
        }

        val existingPeers = peers.entries.filter { it.key != deviceId }.map { it.key to it.value }
        if (existingPeers.isNotEmpty()) {
            enqueue(householdId, deviceId, peer, json.encodeToString<SignalingMessage>(
                SignalingMessage.PeerList(existingPeers.map { it.first })
            ))
            val notification = json.encodeToString<SignalingMessage>(SignalingMessage.PeerList(listOf(deviceId)))
            existingPeers.forEach { (id, other) -> enqueue(householdId, id, other, notification) }
        }
        log.d { "[HUB] REGISTER (household peers=${peers.size})" }
    }

    fun unregister(householdId: String, deviceId: String, session: WebSocketSession) {
        var removed: Peer? = null
        // Registration/removal are atomic per household, including removal of
        // the empty map. A reconnect cannot be inserted into an orphaned map.
        households.computeIfPresent(householdId) { _, peers ->
            peers[deviceId]?.takeIf { it.session === session }?.let {
                peers.remove(deviceId)
                removed = it
            }
            peers.takeIf { it.isNotEmpty() }
        }
        removed?.let { it.queue.cancel(); it.sender?.cancel() }
    }

    private fun disconnect(householdId: String, deviceId: String, peer: Peer, reason: String) {
        peer.queue.cancel()
        peer.sender?.cancel()
        peer.session.cancel(CancellationException(reason))
        unregister(householdId, deviceId, peer.session)
    }

    private fun enqueue(householdId: String, deviceId: String, peer: Peer, text: String) {
        // Bound both message count and retained String content. The sender may
        // additionally hold one in-flight message while Ktor applies backpressure.
        if (text.length > MAX_QUEUED_CHARS) {
            disconnect(householdId, deviceId, peer, "Signaling message too large")
            return
        }
        val queued = peer.queuedChars.addAndGet(text.length)
        val result = if (queued <= MAX_QUEUED_CHARS) peer.queue.trySend(text) else null
        if (result == null || result.isFailure) {
            peer.queuedChars.addAndGet(-text.length)
            if (result?.isClosed != true) log.w { "[HUB] Closing peer whose signaling queue is full" }
            disconnect(householdId, deviceId, peer, "Signaling queue full")
        }
    }

    suspend fun evict(householdId: String, deviceId: String) {
        val peers = households[householdId] ?: return
        peers[deviceId]?.let { peer ->
            unregister(householdId, deviceId, peer.session)
            runCatching { peer.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device removed")) }
        }
        // Notify remaining clients even if the removed device lost signaling while its
        // data channel stayed open. This is control-plane metadata, never entity data.
        val notification = json.encodeToString<SignalingMessage>(
            SignalingMessage.PeerList(emptyList(), removedDeviceIds = listOf(deviceId))
        )
        households[householdId]?.forEach { (id, other) ->
            enqueue(householdId, id, other, notification)
        }
    }

    suspend fun relay(householdId: String, fromDeviceId: String, message: SignalingMessage) {
        val peers = households[householdId] ?: return
        val target = when (message) {
            is SignalingMessage.Offer -> message.toDeviceId
            is SignalingMessage.Answer -> message.toDeviceId
            is SignalingMessage.IceCandidate -> message.toDeviceId
            is SignalingMessage.Join, is SignalingMessage.PeerList -> return
        }
        // Only client-authenticated control messages reach this method via the route.
        if (!isValidClientSignal(message, fromDeviceId)) return
        val text = json.encodeToString(message)
        if (target != null) {
            peers[target]?.let { enqueue(householdId, target, it, text) }
        } else {
            peers.entries.filter { it.key != fromDeviceId }.forEach { (id, peer) ->
                enqueue(householdId, id, peer, text)
            }
        }
    }

    private companion object { const val MAX_QUEUED_CHARS = 256 * 1024 }
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
        log.d { "[WS] New WebSocket connection" }
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
                            log.d { "[WS] JOIN" }
                        }
                        else -> {
                            if (deviceId == null || !isValidClientSignal(msg, deviceId)) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid signaling message"))
                                return@webSocket
                            }
                            log.d { "[WS] RELAY ${msg::class.simpleName}" }
                            householdId?.let { hid ->
                                hub.relay(hid, deviceId, msg)
                            }
                        }
                    }
                }
            }
        } finally {
            log.d { "[WS] DISCONNECT" }
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
