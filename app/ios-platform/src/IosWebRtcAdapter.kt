@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package finance.shilling.app

import co.touchlab.kermit.Logger
import WebRTC.RTCDataBuffer
import WebRTC.RTCDataChannel
import WebRTC.RTCDataChannelDelegateProtocol
import WebRTC.RTCDataChannelState
import io.ktor.client.webrtc.*
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

/** Native timer suspends without polling the UI or Default dispatcher. */
val iosDelay: suspend (Long) -> Unit = { ms ->
    if (ms > 0) {
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            platform.darwin.dispatch_after(
                platform.darwin.dispatch_time(
                    platform.darwin.DISPATCH_TIME_NOW,
                    ms.coerceAtMost(Long.MAX_VALUE / 1_000_000) * 1_000_000
                ),
                platform.darwin.dispatch_get_main_queue()
            ) { continuation.resume(Unit) }
        }
    }
}

/** Bounded receive queue. Overflow closes the transport so receipts retry intact. */
private class NativeChannelReceiver : NSObject(), RTCDataChannelDelegateProtocol {
    val messages = Channel<WebRtc.DataChannel.Message>(capacity = 256)
    private val deliveryLock = NSRecursiveLock()
    private var eventDelegate: RTCDataChannelDelegateProtocol? = null

    fun install(channel: WebRtcDataChannel) {
        deliveryLock.lock()
        try {
            val native = channel.getNative()
            eventDelegate = native.delegate
            native.delegate = this
            // The peer can send before the Open event reaches our collector.
            // Ktor 3.6 retains its delegate and queues those early messages.
            // Adopt them before admitting callbacks from the replacement delegate.
            var adopted = 0
            while (true) {
                val message = channel.tryReceive() ?: break
                enqueue(native, message)
                adopted++
            }
            if (adopted > 0) Logger.withTag("iOS-WebRTC").i { "Adopted $adopted message(s) queued before receiver installation" }
            if (native.readyState == RTCDataChannelState.RTCDataChannelStateClosed) messages.close()
        } finally {
            deliveryLock.unlock()
        }
    }

    override fun dataChannelDidChangeState(dataChannel: RTCDataChannel) {
        // Ktor emits Open/Closed from this callback. Replacing only the receive
        // queue must preserve those events, including an offerer's later Open.
        eventDelegate?.dataChannelDidChangeState(dataChannel)
        if (dataChannel.readyState == RTCDataChannelState.RTCDataChannelStateClosed) messages.close()
    }

    override fun dataChannel(dataChannel: RTCDataChannel, didChangeBufferedAmount: ULong) {
        eventDelegate?.dataChannel(dataChannel, didChangeBufferedAmount)
    }

    override fun dataChannel(dataChannel: RTCDataChannel, didReceiveMessageWithBuffer: RTCDataBuffer) {
        val data = didReceiveMessageWithBuffer.data
        val length = data.length.toInt()
        if (length == 0) return
        val bytes = ByteArray(length)
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        val message = if (didReceiveMessageWithBuffer.isBinary) {
            WebRtc.DataChannel.Message.Binary(bytes)
        } else {
            WebRtc.DataChannel.Message.Text(bytes.decodeToString())
        }
        deliveryLock.lock()
        try { enqueue(dataChannel, message) } finally { deliveryLock.unlock() }
    }

    private fun enqueue(dataChannel: RTCDataChannel, message: WebRtc.DataChannel.Message) {
        if (messages.trySend(message).isFailure) {
            messages.close(IllegalStateException("Native receipt receive buffer full"))
            dataChannel.close()
        }
    }
}

// RTCDataChannel.delegate is weak. Keep the bounded receiver alive until its
// listener closes/cancels; synchronize access from native callbacks and sync jobs.
private val nativeReceivers = mutableMapOf<WebRtcDataChannel, NativeChannelReceiver>()
private val receiversLock = NSLock()

private inline fun <T> withReceiversLock(block: () -> T): T {
    receiversLock.lock()
    try { return block() } finally { receiversLock.unlock() }
}

private fun receiverFor(channel: WebRtcDataChannel): NativeChannelReceiver = withReceiversLock {
    nativeReceivers.getOrPut(channel) {
        NativeChannelReceiver().also { it.install(channel) }
    }
}

private fun releaseReceiver(channel: WebRtcDataChannel, receiver: NativeChannelReceiver) = withReceiversLock {
    if (nativeReceivers[channel] === receiver) nativeReceivers.remove(channel)
}

val nativeChannelOpen: (WebRtcDataChannel) -> Unit = { receiverFor(it) }

/** Await a message without dispatching repeated empty polls through the UI thread. */
val iosReceiveMessage: suspend (WebRtcDataChannel) -> WebRtc.DataChannel.Message = { channel ->
    val receiver = receiverFor(channel)
    try {
        val result = receiver.messages.receiveCatching()
        if (result.isClosed) {
            releaseReceiver(channel, receiver)
            throw WebRtc.DataChannelClosedException("Native receive channel closed", result.exceptionOrNull())
        }
        result.getOrThrow()
    } catch (cancelled: CancellationException) {
        releaseReceiver(channel, receiver)
        receiver.messages.cancel(cancelled)
        throw cancelled
    }
}
