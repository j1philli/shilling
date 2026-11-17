package finance.shilling.server

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import finance.shilling.core.sync.SignalingMessage
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlin.test.*

class SignalingLifecycleTest {
    private val sessions = mutableListOf<Socket>()

    @AfterTest
    fun closeSessions() { sessions.forEach { it.cancel() } }

    private fun socket(capacity: Int = Channel.UNLIMITED) = Socket(capacity).also { sessions += it }

    @Test
    fun disconnectedHouseholdsDoNotAccumulate() = runBlocking {
        Logger.setMinSeverity(Severity.Warn)
        val hub = SignalingHub()
        repeat(1_000) { index ->
            val socket = socket()
            hub.register("temporary-$index", "peer", socket)
            hub.unregister("temporary-$index", "peer", socket)
        }
        val field = SignalingHub::class.java.getDeclaredField("households").apply { isAccessible = true }
        val retained = (field.get(hub) as Map<*, *>).size
        println("SERVER_PROBE householdsRetained=$retained afterDisconnects=1000")
        assertEquals(0, retained, "Disconnected household registries must be released")
    }

    @Test
    fun slowPeerDoesNotBlockAnotherPeersJoinOrOffer() = runBlocking {
        val hub = SignalingHub()
        val slow = socket(0)
        val healthy = socket()
        hub.register("house", "slow", slow)
        val completed = withTimeoutOrNull(250) {
            hub.register("house", "healthy", healthy)
            hub.relay("house", "slow", SignalingMessage.Offer("slow", "healthy", "sdp"))
            true
        }
        println("SERVER_PROBE healthyJoinCompletedWithin250ms=${completed == true}")
        assertEquals(true, completed)
        val messages = withTimeout(1_000) {
            List(2) { Json.decodeFromString<SignalingMessage>((healthy.outgoing.receive() as Frame.Text).readText()) }
        }
        assertEquals(listOf(SignalingMessage.PeerList(listOf("slow")), SignalingMessage.Offer("slow", "healthy", "sdp")), messages)
    }

    @Test
    fun queueOverflowClosesOnlyTheSlowPeer() = runBlocking {
        val hub = SignalingHub(queueCapacity = 2)
        val slow = socket(0)
        val healthy = socket()
        hub.register("house", "slow", slow)
        hub.register("house", "healthy", healthy)
        repeat(4) { hub.relay("house", "healthy", SignalingMessage.Offer("healthy", "slow", "$it")) }
        assertFalse(slow.isActive)
        assertTrue(healthy.isActive)
        val replacement = socket()
        hub.register("house", "slow", replacement)
        hub.unregister("house", "slow", slow)
        hub.relay("house", "healthy", SignalingMessage.Answer("healthy", "slow", "still-live"))
        withTimeout(1_000) {
            assertTrue((replacement.outgoing.receive() as Frame.Text).readText().contains("PeerList"))
            assertTrue((replacement.outgoing.receive() as Frame.Text).readText().contains("still-live"))
        }
    }

    @Test
    fun stalledTransportHasDeadlineEvenWithoutQueueOverflow() = runBlocking {
        val hub = SignalingHub(sendTimeoutMillis = 30)
        val slow = socket(0)
        hub.register("house", "slow", slow)
        hub.register("house", "healthy", socket())
        withTimeout(1_000) { slow.coroutineContext[Job]!!.join() }
        assertFalse(slow.isActive)
    }

    @Test
    fun queuedContentIsBoundedBeforeTheMessageCountLimit() = runBlocking {
        val hub = SignalingHub()
        val slow = socket(0)
        val healthy = socket()
        hub.register("house", "slow", slow)
        hub.register("house", "healthy", healthy)
        // The PeerList is stalled in the transport. Three individually valid
        // messages exceed the content budget long before 64 messages accumulate.
        repeat(3) {
            hub.relay("house", "healthy", SignalingMessage.Offer("healthy", "slow", "x".repeat(100_000)))
        }
        assertFalse(slow.isActive)
        assertTrue(healthy.isActive)
        val replacement = socket()
        hub.register("house", "slow", replacement)
        hub.relay("house", "healthy", SignalingMessage.Offer("healthy", "slow", "small"))
        withTimeout(1_000) {
            replacement.outgoing.receive() // PeerList
            assertTrue((replacement.outgoing.receive() as Frame.Text).readText().contains("small"))
        }
    }

    @Test
    fun concurrentLastDisconnectAndRegistrationDoesNotLoseTheNewPeer() = runBlocking {
        val hub = SignalingHub()
        repeat(100) {
            val old = socket()
            val current = socket()
            hub.register("house", "old", old)
            coroutineScope {
                launch(Dispatchers.Default) { hub.unregister("house", "old", old) }
                launch(Dispatchers.Default) { hub.register("house", "current", current) }
            }
            hub.relay("house", "source", SignalingMessage.Offer("source", "current", "probe"))
            withTimeout(1_000) {
                while (!(current.outgoing.receive() as Frame.Text).readText().contains("probe")) Unit
            }
            hub.unregister("house", "current", current)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class Socket(capacity: Int) : WebSocketSession {
        override val coroutineContext = SupervisorJob() + Dispatchers.Unconfined
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(capacity)
        override val extensions = emptyList<WebSocketExtension<*>>()
        override suspend fun flush() = Unit
        override fun terminate() { cancel() }
    }
}
