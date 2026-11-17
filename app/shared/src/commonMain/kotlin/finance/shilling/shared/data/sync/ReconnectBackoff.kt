package finance.shilling.shared.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Exponential reconnect delay that can be cut short.
 *
 * The desktop webview is suspended while its window is hidden (or the screen is locked), so a
 * signaling reconnect can be stuck mid-backoff when the app comes back. [wake] ends the current
 * wait immediately and resets the delay so the next attempt happens right away.
 */
class ReconnectBackoff(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 30_000L
) {
    private val wakeSignal = MutableStateFlow(0)

    var currentMs: Long = initialMs
        private set

    /** Waits out the current delay, or returns early (true) if [wake] was called meanwhile. */
    suspend fun await(): Boolean {
        val seen = wakeSignal.value
        val woken = withTimeoutOrNull(currentMs) { wakeSignal.first { it != seen } } != null
        currentMs = if (woken) initialMs else (currentMs * 2).coerceAtMost(maxMs)
        return woken
    }

    /** A connection succeeded: the next failure starts the schedule over. */
    fun reset() {
        currentMs = initialMs
    }

    /** Ends any in-progress [await] now (e.g. the app became visible again). */
    fun wake() {
        wakeSignal.value += 1
    }
}
