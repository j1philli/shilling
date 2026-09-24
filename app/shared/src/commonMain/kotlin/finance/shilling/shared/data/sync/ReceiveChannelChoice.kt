package finance.shilling.shared.data.sync

data class ReceiveChannelChoice<T>(
    val channel: T,
    val usedExisting: Boolean
)

/**
 * Prefer an already-created local receive channel when available.
 *
 * Some WebRTC stacks emit an Open event with a channel wrapper that is not the
 * same instance returned by createDataChannel(). Keeping the locally created
 * channel avoids listening on a wrapper that may not receive incoming payloads.
 */
fun <T> preferExistingReceiveChannel(existing: T?, opened: T): ReceiveChannelChoice<T> =
    if (existing != null) {
        ReceiveChannelChoice(channel = existing, usedExisting = true)
    } else {
        ReceiveChannelChoice(channel = opened, usedExisting = false)
    }
