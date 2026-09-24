package finance.shilling.server

import finance.shilling.core.sync.SignalingMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class SignalingHubTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun registerSendsPeerListToNewPeerAndNotifiesExistingPeers() = runBlocking {
        val hub = SignalingHub()
        val first = FakeWebSocketSession()
        val second = FakeWebSocketSession()

        hub.register("house-1", "peer-a", first)
        assertTrue(first.sentMessages().isEmpty())

        hub.register("house-1", "peer-b", second)

        assertEquals(
            listOf(SignalingMessage.PeerList(listOf("peer-b"))),
            first.sentMessages().map(::decode)
        )
        assertEquals(
            listOf(SignalingMessage.PeerList(listOf("peer-a"))),
            second.sentMessages().map(::decode)
        )
    }

    @Test
    fun relayOfferRoutesOnlyToTargetPeer() = runBlocking {
        val hub = SignalingHub()
        val source = FakeWebSocketSession()
        val target = FakeWebSocketSession()
        val other = FakeWebSocketSession()

        hub.register("house-1", "peer-a", source)
        hub.register("house-1", "peer-b", target)
        hub.register("house-1", "peer-c", other)
        source.clear()
        target.clear()
        other.clear()

        val offer = SignalingMessage.Offer("peer-a", "peer-b", "offer-sdp")
        hub.relay("house-1", "peer-a", offer)

        assertEquals(listOf(offer), target.sentMessages().map(::decode))
        assertTrue(other.sentMessages().isEmpty())
        assertTrue(source.sentMessages().isEmpty())
    }

    @Test
    fun relayDoesNotCrossHouseholds() = runBlocking {
        val hub = SignalingHub()
        val houseOneTarget = FakeWebSocketSession()
        val houseTwoTarget = FakeWebSocketSession()

        hub.register("house-1", "peer-a", FakeWebSocketSession())
        hub.register("house-1", "peer-b", houseOneTarget)
        hub.register("house-2", "peer-b", houseTwoTarget)
        houseOneTarget.clear()
        houseTwoTarget.clear()

        val candidate = SignalingMessage.IceCandidate("peer-a", "peer-b", "candidate-1", null, null)
        hub.relay("house-1", "peer-a", candidate)

        assertEquals(listOf(candidate), houseOneTarget.sentMessages().map(::decode))
        assertTrue(houseTwoTarget.sentMessages().isEmpty())
    }

    @Test
    fun unregisterRemovesPeerFromFutureRelayTargets() = runBlocking {
        val hub = SignalingHub()
        val target = FakeWebSocketSession()

        hub.register("house-1", "peer-a", FakeWebSocketSession())
        hub.register("house-1", "peer-b", target)
        target.clear()
        hub.unregister("house-1", "peer-b")

        hub.relay("house-1", "peer-a", SignalingMessage.Answer("peer-a", "peer-b", "answer-sdp"))

        assertTrue(target.sentMessages().isEmpty())
    }

    private fun decode(text: String): SignalingMessage = json.decodeFromString(text)

    @Suppress("OVERRIDE_DEPRECATION")
    private class FakeWebSocketSession : WebSocketSession {
        private val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
        private val outgoingChannel = Channel<Frame>(Channel.UNLIMITED)

        override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val incoming: ReceiveChannel<Frame> = incomingChannel
        override val outgoing: SendChannel<Frame> = outgoingChannel
        override val extensions: List<WebSocketExtension<*>> = emptyList()

        override suspend fun flush() = Unit

        override fun terminate() {
            incomingChannel.close()
            outgoingChannel.close()
        }

        fun sentMessages(): List<String> {
            val messages = mutableListOf<String>()
            while (true) {
                val frame = outgoingChannel.tryReceive().getOrNull() ?: break
                if (frame is Frame.Text) {
                    messages += frame.readText()
                }
            }
            return messages
        }

        fun clear() {
            sentMessages()
        }
    }
}
