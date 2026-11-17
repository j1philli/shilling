package finance.shilling.server

import finance.shilling.core.sync.SignalingMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.readReason
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class SignalingHubTest {
    @Test
    fun admissionsOverlapButPolicyMutationWaitsAndCancelledMutationReleasesPermits() = runBlocking {
        withTimeout(5_000) {
            val hub = SignalingHub()
            val release = CompletableDeferred<Unit>()
            val entered = List(2) { CompletableDeferred<Unit>() }
            val joins = entered.map { ready -> async(start = CoroutineStart.UNDISPATCHED) {
                hub.withJoinPolicy { ready.complete(Unit); release.await() }
            } }
            entered.forEach { it.await() }
            var changed = false
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                hub.withMembershipPolicy { changed = true }
            }
            assertTrue(!changed)
            cancelled.cancelAndJoin()
            // All permits drained by the cancelled mutation must be returned.
            val more = List(30) { async(start = CoroutineStart.UNDISPATCHED) {
                hub.withJoinPolicy { release.await() }
            } }
            val mutation = async(start = CoroutineStart.UNDISPATCHED) {
                hub.withMembershipPolicy { changed = true }
            }
            assertTrue(!changed)
            release.complete(Unit)
            (joins + more).forEach { it.await() }
            mutation.await()
            assertTrue(changed)
            hub.withJoinPolicy { }
        }
    }

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
        hub.unregister("house-1", "peer-b", target)

        hub.relay("house-1", "peer-a", SignalingMessage.Answer("peer-a", "peer-b", "answer-sdp"))

        assertTrue(target.sentMessages().isEmpty())
    }

    @Test
    fun clientCannotSpoofSenderOrPeerList() {
        assertTrue(isValidClientSignal(SignalingMessage.Offer("peer-a", "peer-b", "sdp"), "peer-a"))
        assertTrue(!isValidClientSignal(SignalingMessage.Offer("peer-b", "peer-a", "sdp"), "peer-a"))
        assertTrue(!isValidClientSignal(SignalingMessage.PeerList(listOf("peer-b")), "peer-a"))
    }

    @Test
    fun evictionNotifiesOnlyRemainingHouseholdPeersAndBlocksRelay() = runBlocking {
        val hub = SignalingHub()
        val remaining = FakeWebSocketSession()
        val removed = FakeWebSocketSession()
        val otherHousehold = FakeWebSocketSession()
        hub.register("house-1", "peer-a", remaining)
        hub.register("house-1", "peer-b", removed)
        hub.register("house-2", "peer-c", otherHousehold)
        remaining.clear()
        removed.clear()

        hub.evict("house-1", "peer-b")

        assertEquals(
            listOf(SignalingMessage.PeerList(emptyList(), removedDeviceIds = listOf("peer-b"))),
            remaining.sentMessages().map(::decode)
        )
        assertTrue(removed.receivedPolicyClose())
        hub.relay("house-1", "peer-a", SignalingMessage.Offer("peer-a", "peer-b", "sdp"))
        assertTrue(removed.sentMessages().isEmpty())
        assertTrue(otherHousehold.sentMessages().isEmpty())
    }

    @Test
    fun evictionNotifiesPeersWhenRemovedDeviceAlreadyLostSignaling() = runBlocking {
        val hub = SignalingHub()
        val remaining = FakeWebSocketSession()
        val removed = FakeWebSocketSession()
        hub.register("house-1", "peer-a", remaining)
        hub.register("house-1", "peer-b", removed)
        hub.unregister("house-1", "peer-b", removed)
        remaining.clear()

        hub.evict("house-1", "peer-b")

        assertEquals(
            listOf(SignalingMessage.PeerList(emptyList(), removedDeviceIds = listOf("peer-b"))),
            remaining.sentMessages().map(::decode)
        )
    }

    @Test
    fun replacedSessionCannotUnregisterCurrentPeer() = runBlocking {
        val hub = SignalingHub()
        val old = FakeWebSocketSession()
        val current = FakeWebSocketSession()
        hub.register("house-1", "peer-a", old)
        hub.register("house-1", "peer-a", current)
        hub.unregister("house-1", "peer-a", old)
        hub.relay("house-1", "peer-b", SignalingMessage.Offer("peer-b", "peer-a", "sdp"))
        assertEquals(1, current.sentMessages().size)
    }

    private fun decode(text: String): SignalingMessage = json.decodeFromString(text)

    @Suppress("OVERRIDE_DEPRECATION")
    private class FakeWebSocketSession : WebSocketSession {
        private val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
        private val outgoingChannel = Channel<Frame>(Channel.UNLIMITED)

        override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined
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

        fun receivedPolicyClose(): Boolean {
            val frame = outgoingChannel.tryReceive().getOrNull() as? Frame.Close ?: return false
            return io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY.code ==
                frame.readReason()?.code
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
