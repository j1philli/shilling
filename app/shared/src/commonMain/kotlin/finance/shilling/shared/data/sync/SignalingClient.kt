package finance.shilling.shared.data.sync

import co.touchlab.kermit.Logger
import finance.shilling.core.sync.SignalingMessage
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val log = Logger.withTag("Signaling")

class SignalingClient(
    private val httpClient: HttpClient,
    serverUrl: String,
    private val deviceId: String,
    private val householdId: String,
    private val authService: finance.shilling.shared.data.auth.AuthService? = null
) {
    private val wsUrl = serverUrl
        .replace("http://", "ws://")
        .replace("https://", "wss://")

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages

    private var session: WebSocketSession? = null
    private var connectionJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun connect(scope: CoroutineScope) {
        log.i { "[SIG] connect() called — wsUrl=$wsUrl device=$deviceId household=$householdId" }
        connectionJob?.let {
            log.w { "[SIG] Cancelling previous connection job before reconnecting" }
            it.cancel()
        }
        session = null
        connectionJob = scope.launch {
            var backoffMs = 1000L
            while (isActive) {
                try {
                    log.i { "[SIG] Opening WebSocket to $wsUrl/ws/signal ..." }
                    httpClient.webSocket("$wsUrl/ws/signal") {
                        session = this
                        backoffMs = 1000L
                        log.i { "[SIG] WebSocket CONNECTED to $wsUrl/ws/signal" }
                        val token = authService?.refreshTokenIfNeeded()
                        log.i { "[SIG] Sending Join(device=$deviceId, household=$householdId, hasToken=${token != null})" }
                        send(json.encodeToString<SignalingMessage>(
                            SignalingMessage.Join(deviceId, householdId, token)
                        ))
                        log.i { "[SIG] Join sent — listening for frames..." }
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    val msg = json.decodeFromString<SignalingMessage>(text)
                                    log.i { "[SIG] <<< ${messageSummary(msg)}" }
                                    _incomingMessages.emit(msg)
                                } else {
                                    log.d { "[SIG] Non-text frame: ${frame.frameType}" }
                                }
                            }
                            log.w { "[SIG] Incoming frame loop ended (server closed connection)" }
                        } finally {
                            log.i { "[SIG] Frame loop exited — sending Close frame" }
                            withContext(NonCancellable) {
                                try {
                                    close(CloseReason(CloseReason.Codes.NORMAL, ""))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    log.i { "[SIG] Cancelled" }
                    throw e
                } catch (e: Exception) {
                    log.e { "[SIG] Connection FAILED: ${e::class.simpleName}: ${e.message}" }
                    var cause = e.cause
                    var depth = 1
                    while (cause != null && depth <= 3) {
                        log.e { "[SIG]   cause[$depth]: ${cause!!::class.simpleName}: ${cause!!.message}" }
                        cause = cause!!.cause
                        depth++
                    }
                }
                session = null
                if (!isActive) break
                log.i { "[SIG] Reconnecting in ${backoffMs}ms..." }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    val isConnected: Boolean get() = session != null

    suspend fun send(message: SignalingMessage): Boolean {
        val s = session
        if (s == null) {
            log.w { "[SIG] Cannot send ${message::class.simpleName}: no active session" }
            return false
        }
        try {
            s.send(json.encodeToString(message))
            log.i { "[SIG] >>> ${messageSummary(message)}" }
            return true
        } catch (e: Exception) {
            log.w { "[SIG] Send FAILED for ${message::class.simpleName}: ${e::class.simpleName}: ${e.message}" }
            return false
        }
    }

    fun disconnect() {
        log.i { "[SIG] disconnect() called" }
        connectionJob?.cancel()
        connectionJob = null
        session = null
    }

    private fun messageSummary(message: SignalingMessage): String =
        when (message) {
            is SignalingMessage.Join -> "Join(device=${message.deviceId}, household=${message.householdId})"
            is SignalingMessage.Offer -> "Offer(from=${message.fromDeviceId}, to=${message.toDeviceId}, sdpLen=${message.sdp.length})"
            is SignalingMessage.Answer -> "Answer(from=${message.fromDeviceId}, to=${message.toDeviceId}, sdpLen=${message.sdp.length})"
            is SignalingMessage.IceCandidate -> "IceCandidate(from=${message.fromDeviceId}, to=${message.toDeviceId}, candidate=${message.candidate.take(80)})"
            is SignalingMessage.PeerList -> "PeerList(devices=${message.deviceIds})"
        }
}
