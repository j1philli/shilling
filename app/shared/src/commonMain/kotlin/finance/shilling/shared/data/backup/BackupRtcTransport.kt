@file:OptIn(io.ktor.utils.io.ExperimentalKtorApi::class)
package finance.shilling.shared.data.backup

import finance.shilling.core.backup.BACKUP_MAX_FRAME_CHARS
import finance.shilling.core.sync.IceServersResponse
import finance.shilling.core.sync.SignalingMessage
import finance.shilling.shared.data.sync.SignalingClient
import io.ktor.client.webrtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Preserve TURN authentication. Dropping these fields silently makes relayed connections fail. */
fun IceServersResponse.webRtcServers(): List<WebRtc.IceServer> = iceServers.flatMap { server ->
    server.urls.map { WebRtc.IceServer(it, server.username, server.credential) }
}

/** Dedicated protocol channel: never exposes the ordinary entity-sync or receipt-file API. */
class BackupRtcTransport(
    private val rtc: WebRtcClient,
    private val signaling: SignalingClient,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val offerTo: (String) -> Boolean,
    private val maxConnections: Int = 8,
    private val label: String = "shilling-backup-v1",
    private val connectionSlots: kotlinx.coroutines.sync.Semaphore = kotlinx.coroutines.sync.Semaphore(maxConnections),
    private val onMessage: suspend (String, String) -> String?
) {
    private data class Peer(val connection: WebRtcPeerConnection, val scope: CoroutineScope, var channel: WebRtcDataChannel? = null, var listener: Job? = null)
    private val peers = mutableMapOf<String, Peer>()
    private val candidates = mutableMapOf<String, MutableList<WebRtc.IceCandidate>>()
    private val mutex = Mutex()
    private val ready = MutableStateFlow<Set<String>>(emptySet())
    private var collector: Job? = null
    private var lastReconnect = 0L

    fun start() {
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaling.incomingMessages.collect { message ->
                try { mutex.withLock { handle(message) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A failed negotiation is retried by reconnecting the session. */ }
            }
        }
        signaling.connect(scope)
    }

    suspend fun awaitPeer(peerId: String) = withTimeout(30_000) { ready.first { peerId in it }; Unit }

    suspend fun send(peerId: String, text: String) {
        require(text.length <= BACKUP_MAX_FRAME_CHARS)
        val channel = mutex.withLock { peers[peerId]?.channel } ?: error("Peer disconnected")
        withTimeout(15_000) { channel.send(text) }
    }

    private suspend fun handle(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.PeerList -> message.deviceIds.filter { it != deviceId && offerTo(it) }.forEach { id ->
                remove(id)
                val peer = create(id)
                val channel = peer.connection.createDataChannel(label)
                peer.channel = channel
                val offer = peer.connection.createOffer()
                peer.connection.setLocalDescription(offer)
                signaling.send(SignalingMessage.Offer(deviceId, id, offer.sdp))
            }
            is SignalingMessage.Offer -> if (message.toDeviceId == deviceId && !offerTo(message.fromDeviceId)) {
                remove(message.fromDeviceId)
                val peer = create(message.fromDeviceId)
                peer.connection.setRemoteDescription(WebRtc.SessionDescription(WebRtc.SessionDescriptionType.OFFER, message.sdp))
                val answer = peer.connection.createAnswer()
                peer.connection.setLocalDescription(answer)
                signaling.send(SignalingMessage.Answer(deviceId, message.fromDeviceId, answer.sdp))
                drainCandidates(message.fromDeviceId, peer)
            }
            is SignalingMessage.Answer -> if (message.toDeviceId == deviceId) {
                peers[message.fromDeviceId]?.let { peer ->
                    peer.connection.setRemoteDescription(WebRtc.SessionDescription(WebRtc.SessionDescriptionType.ANSWER, message.sdp))
                    drainCandidates(message.fromDeviceId, peer)
                }
            }
            is SignalingMessage.IceCandidate -> if (message.toDeviceId == deviceId && message.candidate.isNotBlank()) {
                val candidate = WebRtc.IceCandidate(message.candidate, message.sdpMid ?: "", message.sdpMLineIndex ?: 0)
                val peer = peers[message.fromDeviceId]
                if (peer != null) {
                    try { peer.connection.addIceCandidate(candidate) }
                    catch (_: Exception) { buffer(message.fromDeviceId, candidate) }
                } else buffer(message.fromDeviceId, candidate)
            }
            else -> Unit
        }
    }
    private fun buffer(id: String, candidate: WebRtc.IceCandidate) {
        if (candidates.size >= maxConnections && id !in candidates) return
        val pending = candidates.getOrPut(id) { mutableListOf() }
        if (pending.size < 64) pending.add(candidate)
    }
    private suspend fun drainCandidates(id: String, peer: Peer) {
        candidates.remove(id)?.forEach { peer.connection.addIceCandidate(it) }
    }
    suspend fun reconnectIfIdle() {
        if (ready.value.isNotEmpty()) return
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (now - lastReconnect < 30_000) return
        lastReconnect = now
        signaling.connect(scope)
    }
    suspend fun reconnect() { close(); start() }
    private suspend fun create(id: String): Peer {
        check(peers.size < maxConnections) { "Worker busy" }
        check(connectionSlots.tryAcquire()) { "Worker busy" }
        val peer = try { Peer(rtc.createPeerConnection(), CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))) } catch (e: Throwable) { connectionSlots.release(); throw e }
        peers[id] = peer
        peer.scope.launch {
            delay(30_000)
            mutex.withLock { if (peers[id] === peer && id !in ready.value) remove(id) }
        }
        peer.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.connection.iceCandidates.collect {
                signaling.send(SignalingMessage.IceCandidate(deviceId, id, it.candidate, it.sdpMid, it.sdpMLineIndex))
            }
        }
        peer.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.connection.dataChannelEvents.collect { event ->
                if (event is DataChannelEvent.Open && event.channel.label == label) {
                    mutex.withLock {
                        if (peers[id] !== peer || peer.listener != null) return@withLock
                        peer.channel = event.channel
                        ready.value = ready.value + id
                        peer.listener = peer.scope.launch {
                            try {
                                while (isActive) {
                                    val text = withTimeout(90_000) { event.channel.receiveText() }
                                    require(text.length <= BACKUP_MAX_FRAME_CHARS)
                                    when (text) {
                                        "__ping__" -> send(id, "__pong__")
                                        "__pong__" -> Unit
                                        else -> onMessage(id, text)?.let { send(id, it) }
                                    }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Never log protocol contents or keys. */ }
                            finally { withContext(NonCancellable) { mutex.withLock { if (peers[id] === peer) remove(id) } } }
                        }
                    }
                }
            }
        }
        return peer
    }
    private fun remove(id: String) {
        ready.value = ready.value - id
        peers.remove(id)?.let { it.scope.cancel(); it.connection.close(); connectionSlots.release() }
    }
    suspend fun ping() {
        for (id in ready.value) {
            try { send(id, "__ping__") } catch (e: CancellationException) { throw e } catch (_: Exception) { mutex.withLock { remove(id) } }
        }
    }
    suspend fun close() {
        collector?.cancel()
        signaling.disconnect()
        mutex.withLock { peers.keys.toList().forEach(::remove); candidates.clear() }
    }
}
