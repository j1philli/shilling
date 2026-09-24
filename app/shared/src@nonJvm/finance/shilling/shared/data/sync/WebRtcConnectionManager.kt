package finance.shilling.shared.data.sync

import co.touchlab.kermit.Logger
import finance.shilling.core.sync.SignalingMessage
import io.ktor.client.webrtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val log = Logger.withTag("WebRTC")
private const val RETRY_DELAY_MS = 8_000L
private val PEER_CONNECTED_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)

class WebRtcConnectionManager(
    private val webRtcClient: WebRtcClient,
    private val signalingClient: SignalingClient,
    private val deviceId: String,
    private val receiveTextFn: suspend (WebRtcDataChannel) -> String = { it.receiveText() },
    private val onChannelOpen: (WebRtcDataChannel) -> Unit = {},
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) : PeerSyncManager {
    private val _incomingChanges = MutableSharedFlow<ChangeMessage>(extraBufferCapacity = 256)
    override val incomingChanges: SharedFlow<ChangeMessage> = _incomingChanges

    private val _incomingFileMessages = MutableSharedFlow<Pair<String, FileTransferMessage>>(extraBufferCapacity = 2048)
    override val incomingFileMessages: SharedFlow<Pair<String, FileTransferMessage>> = _incomingFileMessages

    private val _peerConnected = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val peerConnected: SharedFlow<String> = _peerConnected

    private val peers = mutableMapOf<String, WebRtcPeerConnection>()
    private val dataChannels = mutableMapOf<String, WebRtcDataChannel>()

    private data class ListenerState(val channelId: String, val job: Job)

    private val listenersByPeerAndLabel = mutableMapOf<Pair<String, String>, ListenerState>()
    private val pendingChanges = mutableListOf<ChangeMessage>()
    private val pendingFileMessages = mutableListOf<FileTransferMessage>()
    private val pendingDirectFileMessages = mutableMapOf<String, MutableList<FileTransferMessage>>()
    private val readyPeers = mutableSetOf<String>()
    private val peersWithInboundTraffic = mutableSetOf<String>()
    /** ICE candidates that arrived before the peer's Offer was processed. */
    private val bufferedIceCandidates = mutableMapOf<String, MutableList<SignalingMessage.IceCandidate>>()
    /** Retry jobs for peers we're waiting on — cancelled once connected. */
    private val retryJobs = mutableMapOf<String, Job>()
    /** Extra peerConnected re-emits to recover from startup/listener races. */
    private val peerConnectedRetryJobs = mutableMapOf<String, Job>()
    private var scope: CoroutineScope? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        log.i { "[RTC] start() — device=$deviceId, collecting signaling messages..." }
        scope.launch {
            signalingClient.incomingMessages.collect { msg ->
                when (msg) {
                    is SignalingMessage.PeerList -> {
                        log.i { "[RTC] PeerList received: ${msg.deviceIds} (we are $deviceId, existing peers=${peers.keys})" }
                        msg.deviceIds.forEach { peerId ->
                            if (peerId == deviceId) {
                                log.d { "[RTC] Ignoring self in PeerList" }
                                return@forEach
                            }
                            // PeerList means the peer just (re-)registered with the signaling
                            // server. Any existing connection is potentially stale — the peer
                            // may have restarted its sync scope, closing its side of the channel
                            // without us detecting it yet. Always clean up and reconnect.
                            if (peerId in peers) {
                                val ch = dataChannels[peerId]
                                val chState = ch?.state
                                log.i { "[RTC] Peer $peerId re-registered — replacing existing connection (channel=$chState)" }
                                cleanupPeer(peerId)
                            }
                            val weOffer = shouldBeOfferer(deviceId, peerId)
                            if (weOffer) {
                                log.i { "[RTC] Initiating connection to $peerId (we OFFER)" }
                                initiateConnection(peerId)
                            } else {
                                log.i { "[RTC] Waiting for offer from $peerId (they offer) — scheduling retry" }
                                scheduleRetryOffer(peerId)
                            }
                        }
                    }
                    is SignalingMessage.Offer -> {
                        if (msg.toDeviceId != deviceId) {
                            log.d { "[RTC] Ignoring Offer from ${msg.fromDeviceId} addressed to ${msg.toDeviceId}" }
                            return@collect
                        }
                        log.i { "[RTC] <<< Offer from ${msg.fromDeviceId} (sdpLen=${msg.sdp.length})" }
                        handleOffer(msg)
                    }
                    is SignalingMessage.Answer -> {
                        if (msg.toDeviceId != deviceId) {
                            log.d { "[RTC] Ignoring Answer from ${msg.fromDeviceId} addressed to ${msg.toDeviceId}" }
                            return@collect
                        }
                        log.i { "[RTC] <<< Answer from ${msg.fromDeviceId} (sdpLen=${msg.sdp.length})" }
                        handleAnswer(msg)
                    }
                    is SignalingMessage.IceCandidate -> {
                        if (msg.toDeviceId != deviceId) return@collect
                        log.d { "[RTC] <<< ICE from ${msg.fromDeviceId}: ${msg.candidate.take(60)}" }
                        handleIceCandidate(msg)
                    }
                    is SignalingMessage.Join -> {}
                }
            }
        }
    }

    override suspend fun broadcast(change: ChangeMessage) {
        val text = json.encodeToString(change)
        val channelStates = peers.keys.map { pid ->
            val ch = dataChannels[pid]
            "$pid=${ch?.state ?: "NO_CHANNEL"}"
        }
        val outboundCount = peers.keys.count { outboundChannel(it) != null }
        log.i { "[RTC] broadcast ${change.entityType}/${change.op} id=${change.entityId} — peers=${peers.size} open=$outboundCount channels=[$channelStates]" }

        var p2pSuccess = false
        val deadPeers = mutableListOf<String>()
        peers.keys.toList().forEach { peerId ->
            val channel = outboundChannel(peerId) ?: return@forEach
            try {
                channel.send(text)
                p2pSuccess = true
                log.i { "[RTC] >>> Sent ${change.entityType}/${change.op} to $peerId (${text.length} chars)" }
            } catch (e: Throwable) {
                log.w { "[RTC] Send failed to $peerId: ${e::class.simpleName}: ${e.message}" }
                deadPeers.add(peerId)
            }
        }
        deadPeers.forEach { cleanupPeer(it) }

        if (!p2pSuccess) {
            val alreadyQueued = pendingChanges.any { it.id == change.id }
            if (!alreadyQueued) {
                pendingChanges.add(change)
            }
            log.w { "[RTC] No P2P channels available — queued ${change.entityType}/${change.op} (pending=${pendingChanges.size})" }
        }
    }

    override suspend fun sendToPeer(peerId: String, change: ChangeMessage) {
        val channel = outboundChannel(peerId)
        if (channel == null) {
            log.w { "[RTC] sendToPeer: no open channel for $peerId, queuing ${change.entityType}/${change.op}" }
            val alreadyQueued = pendingChanges.any { it.id == change.id }
            if (!alreadyQueued) pendingChanges.add(change)
            return
        }
        try {
            channel.send(json.encodeToString(change))
            log.d { "[RTC] >>> Sent ${change.entityType}/${change.op} to $peerId (targeted)" }
        } catch (e: Throwable) {
            log.w { "[RTC] sendToPeer failed for $peerId: ${e::class.simpleName}: ${e.message}" }
            cleanupPeer(peerId)
        }
    }

    override suspend fun sendFileMessage(peerId: String, message: FileTransferMessage) {
        val channel = outboundChannel(peerId)
        if (channel == null) {
            queueDirectFileMessage(peerId, message, "no outbound channel (ch=${dataChannels[peerId]?.state})")
            return
        }
        try {
            channel.send(json.encodeToString(message))
            log.d { "[RTC] >>> ${describeFileMessage(message)} to $peerId" }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            queueDirectFileMessage(peerId, message, "send failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    override suspend fun broadcastFileMessage(message: FileTransferMessage) {
        val desc = describeFileMessage(message)
        val hasAnyOutbound = peers.keys.any { outboundChannel(it) != null }
        if (!hasAnyOutbound) {
            if (message is FileTransferMessage.FileRequest) {
                val alreadyQueued = pendingFileMessages.any {
                    it is FileTransferMessage.FileRequest && it.receiptId == message.receiptId
                }
                if (alreadyQueued) {
                    log.d { "[RTC] Skipping duplicate pending $desc" }
                    return
                }
            }
            log.i { "[RTC] No open channels, queuing $desc (queue=${pendingFileMessages.size + 1})" }
            pendingFileMessages.add(message)
            return
        }
        val text = json.encodeToString(message)
        var sent = false
        val deadPeers = mutableListOf<String>()
        peers.keys.toList().forEach { peerId ->
            val channel = outboundChannel(peerId) ?: return@forEach
            try {
                channel.send(text)
                sent = true
                log.i { "[RTC] >>> $desc to $peerId" }
            } catch (e: Throwable) {
                log.w { "[RTC] File broadcast failed for $peerId: ${e::class.simpleName}" }
                deadPeers.add(peerId)
            }
        }
        deadPeers.forEach { cleanupPeer(it) }
        if (!sent) {
            log.i { "[RTC] All channel sends failed, queuing $desc" }
            pendingFileMessages.add(message)
        }
    }

    override fun stop() {
        log.i {
            "[RTC] stop() — closing ${peers.size} peer(s), ${dataChannels.size} channel(s), " +
                "preserving ${pendingChanges.size} queued change(s) and ${pendingFileMessages.size} queued file msg(s)"
        }
        peers.values.forEach { it.close() }
        peers.clear()
        dataChannels.clear()
        pendingDirectFileMessages.clear()
        bufferedIceCandidates.clear()
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        peerConnectedRetryJobs.values.forEach { it.cancel() }
        peerConnectedRetryJobs.clear()
        listenersByPeerAndLabel.values.forEach { it.job.cancel() }
        listenersByPeerAndLabel.clear()
        readyPeers.clear()
        peersWithInboundTraffic.clear()
    }

    private suspend fun drainPendingChanges() {
        val outboundPeers = peers.keys.filter { outboundChannel(it) != null }
        if (pendingChanges.isEmpty() || outboundPeers.isEmpty()) return
        val queued = pendingChanges.toList()
        pendingChanges.clear()
        log.i { "[RTC] Draining ${queued.size} queued change(s) to ${outboundPeers.size} peer(s)" }
        val failed = mutableListOf<ChangeMessage>()
        for (change in queued) {
            val text = json.encodeToString(change)
            var delivered = false
            outboundPeers.forEach { peerId ->
                val channel = outboundChannel(peerId) ?: return@forEach
                try {
                    channel.send(text)
                    delivered = true
                    log.i { "[RTC] Drained ${change.entityType}/${change.op} to $peerId" }
                } catch (e: Throwable) {
                    log.w { "[RTC] Drain send failed for $peerId: ${e::class.simpleName}" }
                }
            }
            if (!delivered) failed.add(change)
        }
        if (failed.isNotEmpty()) {
            pendingChanges.addAll(failed)
            log.w { "[RTC] Re-queued ${failed.size} change(s) after drain failures" }
        }
    }

    private suspend fun drainPendingFileMessages() {
        val outboundPeers = peers.keys.filter { outboundChannel(it) != null }
        if (pendingFileMessages.isEmpty() || outboundPeers.isEmpty()) return
        val seen = mutableSetOf<String>()
        val deduped = pendingFileMessages.filter { msg ->
            if (msg is FileTransferMessage.FileRequest) {
                seen.add(msg.receiptId)
            } else true
        }
        val dropped = pendingFileMessages.size - deduped.size
        pendingFileMessages.clear()
        log.i {
            "[RTC] Draining ${deduped.size} pending file msg(s) to ${outboundPeers.size} peer(s)" +
                if (dropped > 0) " ($dropped duplicates removed)" else ""
        }
        val failed = mutableListOf<FileTransferMessage>()
        for (msg in deduped) {
            val text = json.encodeToString(msg)
            var delivered = false
            outboundPeers.forEach { peerId ->
                val channel = outboundChannel(peerId) ?: return@forEach
                try {
                    channel.send(text)
                    delivered = true
                } catch (e: Throwable) {
                    log.w { "[RTC] Drain file send failed for $peerId: ${e::class.simpleName}" }
                }
            }
            if (!delivered) failed.add(msg)
        }
        if (failed.isNotEmpty()) {
            pendingFileMessages.addAll(failed)
            log.w { "[RTC] Re-queued ${failed.size} file msg(s)" }
        }
    }

    private fun queueDirectFileMessage(peerId: String, message: FileTransferMessage, reason: String) {
        val queue = pendingDirectFileMessages.getOrPut(peerId) { mutableListOf() }
        queue.add(message)
        log.w { "[RTC] Queued direct ${describeFileMessage(message)} for $peerId: $reason (queued=${queue.size})" }
    }

    private suspend fun drainPendingDirectFileMessages(peerId: String) {
        val channel = outboundChannel(peerId) ?: return
        val queue = pendingDirectFileMessages[peerId] ?: return
        if (queue.isEmpty()) {
            pendingDirectFileMessages.remove(peerId)
            return
        }
        log.i { "[RTC] Draining ${queue.size} direct file msg(s) to $peerId" }
        val snapshot = queue.toList()
        queue.clear()
        for ((index, message) in snapshot.withIndex()) {
            try {
                channel.send(json.encodeToString(message))
            } catch (e: Throwable) {
                val remaining = snapshot.subList(index, snapshot.size)
                queue.addAll(remaining)
                log.w { "[RTC] Direct drain failed for $peerId at $index: ${e::class.simpleName} (re-queued=${queue.size})" }
                cleanupPeer(peerId)
                return
            }
        }
        pendingDirectFileMessages.remove(peerId)
    }

    private fun cleanupPeer(peerId: String) {
        log.i { "[RTC] cleanupPeer($peerId) — ch=${dataChannels[peerId]?.state}" }
        dataChannels.remove(peerId)
        listenersByPeerAndLabel.keys
            .filter { it.first == peerId }
            .forEach { key -> listenersByPeerAndLabel.remove(key)?.job?.cancel() }
        readyPeers.remove(peerId)
        peersWithInboundTraffic.remove(peerId)
        peerConnectedRetryJobs.remove(peerId)?.cancel()
        pendingDirectFileMessages.remove(peerId)?.let { dropped ->
            if (dropped.isNotEmpty()) {
                log.w { "[RTC] Dropped ${dropped.size} direct file msg(s) for $peerId" }
            }
        }
        bufferedIceCandidates.remove(peerId)
        peers.remove(peerId)?.close()
    }

    /**
     * If the expected offerer doesn't connect within [RETRY_DELAY_MS], we proactively
     * offer ourselves. This handles cases where the other peer's offer was lost,
     * ICE failed, or the peer was too slow.
     */
    private fun scheduleRetryOffer(peerId: String) {
        retryJobs[peerId]?.cancel()
        retryJobs[peerId] = (scope?.launch {
            delayFn(RETRY_DELAY_MS)
            val ch = dataChannels[peerId]
            if (ch?.state == WebRtc.DataChannel.State.OPEN) {
                log.d { "[RTC] Retry cancelled — $peerId already connected" }
                return@launch
            }
            if (peerId in peers) {
                log.i { "[RTC] Retry: stale connection to $peerId (ch=${ch?.state}) — cleaning up" }
                cleanupPeer(peerId)
            }
            log.i { "[RTC] Retry: proactively offering to $peerId after ${RETRY_DELAY_MS}ms timeout" }
            initiateConnection(peerId)
        }) ?: return
    }

    private suspend fun initiateConnection(peerId: String) {
        try {
            log.i { "[RTC] Creating PeerConnection for $peerId (offerer path)" }
            val connection = webRtcClient.createPeerConnection()
            peers[peerId] = connection
            collectIceCandidates(connection, peerId)
            collectConnectionState(connection, peerId)
            collectDataChannelEvents(connection, peerId)

            val channel = connection.createDataChannel("data")
            dataChannels[peerId] = channel
            log.i { "[RTC] Created 'data' channel for $peerId (${channelId(channel)}, state=${channel.state})" }
            onChannelOpen(channel)
            attachListener(peerId, channel, replace = true, reason = "offerer createDataChannel")

            log.i { "[RTC] Creating offer for $peerId..." }
            val offer = connection.createOffer()
            connection.setLocalDescription(offer)
            log.i { "[RTC] Offer created (sdpLen=${offer.sdp.length}), sending via signaling to $peerId" }
            signalingClient.send(SignalingMessage.Offer(deviceId, peerId, offer.sdp))
            drainBufferedIceCandidates(peerId)
        } catch (e: Throwable) {
            log.e { "[RTC] initiateConnection FAILED for $peerId: ${e::class.simpleName}: ${e.message}" }
            cleanupPeer(peerId)
        }
    }

    private suspend fun handleOffer(msg: SignalingMessage.Offer) {
        try {
            if (msg.fromDeviceId in peers) {
                log.i { "[RTC] Replacing existing connection for ${msg.fromDeviceId}" }
                cleanupPeer(msg.fromDeviceId)
            }

            log.i { "[RTC] Creating PeerConnection for ${msg.fromDeviceId} (answerer path)" }
            val connection = webRtcClient.createPeerConnection()
            peers[msg.fromDeviceId] = connection
            collectIceCandidates(connection, msg.fromDeviceId)
            collectConnectionState(connection, msg.fromDeviceId)
            collectDataChannelEvents(connection, msg.fromDeviceId)

            log.i { "[RTC] Setting remote description (offer) from ${msg.fromDeviceId}" }
            connection.setRemoteDescription(
                WebRtc.SessionDescription(WebRtc.SessionDescriptionType.OFFER, msg.sdp)
            )
            log.i { "[RTC] Creating answer for ${msg.fromDeviceId}..." }
            val answer = connection.createAnswer()
            connection.setLocalDescription(answer)
            log.i { "[RTC] Answer created (sdpLen=${answer.sdp.length}), sending via signaling to ${msg.fromDeviceId}" }
            signalingClient.send(SignalingMessage.Answer(deviceId, msg.fromDeviceId, answer.sdp))
            drainBufferedIceCandidates(msg.fromDeviceId)
        } catch (e: Throwable) {
            log.e { "[RTC] handleOffer FAILED from ${msg.fromDeviceId}: ${e::class.simpleName}: ${e.message}" }
            cleanupPeer(msg.fromDeviceId)
        }
    }

    private suspend fun handleAnswer(msg: SignalingMessage.Answer) {
        try {
            val conn = peers[msg.fromDeviceId]
            if (conn == null) {
                log.w { "[RTC] handleAnswer — no peer connection for ${msg.fromDeviceId}, ignoring" }
                return
            }
            log.i { "[RTC] Setting remote description (answer) from ${msg.fromDeviceId}" }
            conn.setRemoteDescription(
                WebRtc.SessionDescription(WebRtc.SessionDescriptionType.ANSWER, msg.sdp)
            )
            log.i { "[RTC] Remote answer set for ${msg.fromDeviceId} — draining pending..." }
            drainPendingChanges()
            drainPendingDirectFileMessages(msg.fromDeviceId)
            drainPendingFileMessages()
        } catch (e: Throwable) {
            log.e { "[RTC] handleAnswer FAILED from ${msg.fromDeviceId}: ${e::class.simpleName}: ${e.message}" }
            cleanupPeer(msg.fromDeviceId)
        }
    }

    private suspend fun handleIceCandidate(msg: SignalingMessage.IceCandidate) {
        try {
            if (msg.candidate.isBlank()) {
                log.d { "[RTC] Ignoring empty end-of-candidates from ${msg.fromDeviceId}" }
                return
            }
            val conn = peers[msg.fromDeviceId]
            if (conn == null) {
                // Buffer the candidate — the Offer for this peer may not have been processed yet
                val buf = bufferedIceCandidates.getOrPut(msg.fromDeviceId) { mutableListOf() }
                buf.add(msg)
                log.i { "[RTC] Buffered ICE candidate from ${msg.fromDeviceId} (no PeerConnection yet, buffered=${buf.size})" }
                return
            }
            conn.addIceCandidate(
                WebRtc.IceCandidate(msg.candidate, msg.sdpMid ?: "", msg.sdpMLineIndex ?: 0)
            )
        } catch (e: Throwable) {
            log.w { "[RTC] addIceCandidate failed from ${msg.fromDeviceId}: ${e::class.simpleName}: ${e.message}" }
        }
    }

    /** Apply any ICE candidates that were buffered before the PeerConnection was created. */
    private suspend fun drainBufferedIceCandidates(peerId: String) {
        val buffered = bufferedIceCandidates.remove(peerId) ?: return
        val conn = peers[peerId] ?: return
        log.i { "[RTC] Applying ${buffered.size} buffered ICE candidate(s) for $peerId" }
        for (candidate in buffered) {
            try {
                conn.addIceCandidate(
                    WebRtc.IceCandidate(candidate.candidate, candidate.sdpMid ?: "", candidate.sdpMLineIndex ?: 0)
                )
            } catch (e: Throwable) {
                log.w { "[RTC] Buffered addIceCandidate failed for $peerId: ${e::class.simpleName}: ${e.message}" }
            }
        }
    }

    private fun collectIceCandidates(connection: WebRtcPeerConnection, peerId: String) {
        scope?.launch {
            connection.iceCandidates.collect { ice ->
                log.d { "[RTC] ICE gathered for $peerId: ${ice.candidate.take(60)}" }
                signalingClient.send(
                    SignalingMessage.IceCandidate(
                        deviceId, peerId, ice.candidate, ice.sdpMid, ice.sdpMLineIndex
                    )
                )
            }
        }
    }

    private fun collectConnectionState(connection: WebRtcPeerConnection, peerId: String) {
        scope?.launch {
            connection.iceConnectionState.collect { state ->
                val chState = dataChannels[peerId]?.state
                log.i { "[RTC] ICE state $peerId: $state (channel=$chState)" }
                when (state) {
                    WebRtc.IceConnectionState.CONNECTED, WebRtc.IceConnectionState.COMPLETED -> {
                        if (outboundChannel(peerId) != null) {
                            log.i { "[RTC] ICE $state for $peerId — channel ready, draining pending messages" }
                            markPeerReady(peerId)
                        } else {
                            log.i { "[RTC] ICE $state for $peerId — waiting for open data channel before marking ready" }
                        }
                    }
                    WebRtc.IceConnectionState.FAILED -> {
                        log.e { "[RTC] ICE FAILED for $peerId — P2P unavailable" }
                    }
                    WebRtc.IceConnectionState.DISCONNECTED -> {
                        log.w { "[RTC] ICE DISCONNECTED for $peerId — may recover" }
                    }
                    WebRtc.IceConnectionState.CLOSED -> {
                        log.i { "[RTC] ICE CLOSED for $peerId" }
                    }
                    else -> {}
                }
            }
        }
        scope?.launch {
            connection.state.collect { state ->
                log.i { "[RTC] PeerConnection state $peerId: $state" }
            }
        }
    }

    private fun collectDataChannelEvents(connection: WebRtcPeerConnection, peerId: String) {
        scope?.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.dataChannelEvents.collect { event ->
                when (event) {
                    is DataChannelEvent.Open -> {
                        val ch = event.channel
                        log.i { "[RTC] DataChannelEvent.Open: peer=$peerId label=${ch.label} state=${ch.state} existing=${dataChannels[peerId] != null}" }
                        if (ch.label == "data" && dataChannels[peerId] == null) {
                            dataChannels[peerId] = ch
                            onChannelOpen(ch)
                            log.i { "[RTC] Registered answerer 'data' channel for $peerId (${channelId(ch)})" }
                            attachListener(peerId, ch, replace = true, reason = "answerer ondatachannel")
                        } else if (ch.label == "data") {
                            log.d { "[RTC] DataChannelEvent.Open for existing channel $peerId (offerer path)" }
                        }
                        markPeerReady(peerId)
                    }
                    is DataChannelEvent.Closed -> {
                        log.i { "[RTC] DataChannelEvent.Closed: peer=$peerId" }
                        cleanupPeer(peerId)
                    }
                    is DataChannelEvent.Error -> {
                        log.e { "[RTC] DataChannelEvent.Error: peer=$peerId" }
                    }
                    else -> {
                        log.d { "[RTC] DataChannelEvent other: ${event::class.simpleName} peer=$peerId" }
                    }
                }
            }
        }
    }

    private suspend fun markPeerReady(peerId: String) {
        val channel = outboundChannel(peerId)
        if (channel == null) {
            log.d { "[RTC] markPeerReady($peerId) skipped — no open outbound channel" }
            return
        }
        val firstReady = readyPeers.add(peerId)
        retryJobs.remove(peerId)?.cancel()
        drainPendingChanges()
        drainPendingDirectFileMessages(peerId)
        drainPendingFileMessages()
        if (firstReady) {
            log.i { "[RTC] Peer ready for sync: $peerId (${channelId(channel)})" }
            _peerConnected.tryEmit(peerId)
            schedulePeerConnectedRetry(peerId)
        } else {
            log.d { "[RTC] Peer already marked ready: $peerId" }
        }
    }

    private fun schedulePeerConnectedRetry(peerId: String) {
        peerConnectedRetryJobs[peerId]?.cancel()
        peerConnectedRetryJobs[peerId] = (scope?.launch {
            for (delayMs in PEER_CONNECTED_RETRY_DELAYS_MS) {
                delayFn(delayMs)
                if (!isActive) return@launch
                if (peerId !in readyPeers) return@launch
                if (peerId in peersWithInboundTraffic) return@launch
                val channel = outboundChannel(peerId) ?: return@launch
                log.i {
                    "[RTC] No inbound traffic from $peerId yet — re-emitting peerConnected to retry backfill (${channelId(channel)})"
                }
                _peerConnected.tryEmit(peerId)
            }
        }) ?: return
    }

    private fun outboundChannel(peerId: String): WebRtcDataChannel? {
        val ch = dataChannels[peerId] ?: return null
        return if (ch.state == WebRtc.DataChannel.State.OPEN) ch else null
    }

    private fun attachListener(peerId: String, channel: WebRtcDataChannel, replace: Boolean, reason: String) {
        val label = channel.label
        val key = peerId to label
        val newChannelId = channelId(channel)
        val existing = listenersByPeerAndLabel[key]

        if (existing != null) {
            if (existing.channelId == newChannelId && existing.job.isActive) {
                log.d { "[RTC] Listener already active for $peerId/$label ($newChannelId)" }
                return
            }
            if (!replace && existing.job.isActive) {
                log.d { "[RTC] Not replacing active listener for $peerId/$label" }
                return
            }
            log.d { "[RTC] Replacing listener for $peerId/$label (old=${existing.channelId})" }
            existing.job.cancel()
        }

        listenForMessages(channel, peerId)?.let { job ->
            listenersByPeerAndLabel[key] = ListenerState(newChannelId, job)
            log.i { "[RTC] Attached listener for $peerId on $newChannelId ($reason)" }
        }
    }

    private fun listenForMessages(channel: WebRtcDataChannel, peerId: String): Job? {
        return scope?.launch {
            log.i { "[RTC] listenForMessages STARTED: peer=$peerId ch=${channelId(channel)} state=${channel.state}" }
            var openDrainDone = false
            var messageCount = 0
            try {
                while (isActive) {
                    val text = receiveTextFn(channel)
                    messageCount++
                    if (!openDrainDone) {
                        openDrainDone = true
                        log.i { "[RTC] First message from $peerId — triggering post-open drain" }
                        if (peersWithInboundTraffic.add(peerId)) {
                            peerConnectedRetryJobs.remove(peerId)?.cancel()
                            if (peerId in readyPeers) {
                                log.i { "[RTC] First inbound traffic confirmed for $peerId — re-emitting peerConnected for confirmed backfill" }
                                _peerConnected.tryEmit(peerId)
                            }
                        }
                        drainPendingDirectFileMessages(peerId)
                        drainPendingFileMessages()
                    }
                    log.d { "[RTC] <<< Message #$messageCount from $peerId (${text.length} chars): ${text.take(120)}" }
                    try {
                        val change = json.decodeFromString<ChangeMessage>(text)
                        log.i { "[RTC] <<< ChangeMessage from $peerId: ${change.entityType}/${change.op} entity=${change.entityId}" }
                        _incomingChanges.emit(change)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        try {
                            val msg = json.decodeFromString<FileTransferMessage>(text)
                            log.d { "[RTC] <<< ${describeFileMessage(msg)} from $peerId" }
                            _incomingFileMessages.emit(peerId to msg)
                        } catch (e2: Throwable) {
                            if (e2 is CancellationException) throw e2
                            log.w { "[RTC] Failed to decode from $peerId: ${e2::class.simpleName}: ${text.take(200)}" }
                        }
                    }
                }
            } catch (e: CancellationException) {
                log.d { "[RTC] listenForMessages cancelled: peer=$peerId (received $messageCount msgs)" }
            } catch (e: Throwable) {
                log.w { "[RTC] listenForMessages ended: peer=$peerId ${e::class.simpleName}: ${e.message} (received $messageCount msgs)" }
                cleanupPeer(peerId)
            }
        }
    }

    private fun describeFileMessage(message: FileTransferMessage): String =
        when (message) {
            is FileTransferMessage.FileRequest -> "FileRequest(${message.receiptId})"
            is FileTransferMessage.FileHeader -> "FileHeader(${message.receiptId} size=${message.totalSize} chunks=${message.chunkCount})"
            is FileTransferMessage.FileChunk -> "FileChunk(${message.receiptId}#${message.index})"
            is FileTransferMessage.FileComplete -> "FileComplete(${message.receiptId})"
            is FileTransferMessage.FileNotAvailable -> "FileNotAvailable(${message.receiptId})"
        }

    private fun channelId(channel: WebRtcDataChannel): String =
        "${channel.label}@${channel.hashCode().toString(16)}"
}

/**
 * Determines whether [ownId] should be the WebRTC offerer when connecting to [peerId].
 *
 * Platform detection: NSUUID (iOS) always uses uppercase hex (A-F); Kotlin UUID (WASM/Android)
 * always uses lowercase (a-f). Absence of lowercase hex letters identifies an iOS peer.
 *
 *   - WASM/Android → iOS:  WASM/Android is always offerer
 *   - iOS → WASM/Android:  iOS is always answerer
 *   - Same platform: fall back to lexicographic tiebreaker
 */
internal fun shouldBeOfferer(ownId: String, peerId: String): Boolean {
    val ownIsNative = ownId.none { it in 'a'..'f' }
    val peerIsNative = peerId.none { it in 'a'..'f' }
    return when {
        !ownIsNative && peerIsNative -> true   // WASM/Android vs iOS: we offer
        ownIsNative && !peerIsNative -> false   // iOS vs WASM/Android: they offer
        else -> ownId < peerId                  // same platform: lexicographic tiebreaker
    }
}
