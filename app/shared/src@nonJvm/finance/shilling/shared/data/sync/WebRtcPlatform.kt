package finance.shilling.shared.data.sync

import io.ktor.client.webrtc.WebRtc
import io.ktor.client.webrtc.WebRtcClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Platform WebRTC wiring. Each app binds one in its platform Koin module alongside
 * `ShillingDatabase`, `Settings`, `IdGenerator`, `ReceiptFileStore`, and `HttpClient`.
 */
class WebRtcPlatform(
    /** Builds the platform [WebRtcClient]; [iceServers] is read per connection so ICE updates apply. */
    val createClient: (iceServers: () -> List<WebRtc.IceServer>) -> WebRtcClient,
    /** Delay used by the sync loops (iOS needs a cooperative replacement for `delay`). */
    val delayFn: suspend (Long) -> Unit = { delay(it) },
    /**
     * Emits when the app comes back to the foreground after the platform paused it (web: the
     * document became visible again). Sync uses it to cut a pending reconnect backoff short.
     */
    val resumeSignals: Flow<Unit> = emptyFlow()
)
