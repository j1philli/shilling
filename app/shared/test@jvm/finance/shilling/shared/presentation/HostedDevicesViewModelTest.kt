package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModelStore
import com.russhwolf.settings.PreferencesSettings
import finance.shilling.shared.data.CloudRelayCallback
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.auth.*
import finance.shilling.shared.data.sync.PeerConnectionStatus
import finance.shilling.shared.session.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HostedDevicesViewModelTest {
    @Test
    fun syntheticPollingProfile() = runTest {
        Fixture(this).use { fixture ->
            var readyAt: Long? = null
            val observer = backgroundScope.launch {
                fixture.model.state.collect {
                    if (it.devices.isNotEmpty() && readyAt == null) readyAt = testScheduler.currentTime
                }
            }
            advanceTimeBy(45_000)
            runCurrent()
            val visibleRequests = fixture.requests.size
            observer.cancelAndJoin()
            advanceTimeBy(45_000)
            runCurrent()
            println("HOSTED_SETTINGS_PROFILE readyMs=$readyAt visible45sRequests=$visibleRequests hidden45sRequests=${fixture.requests.size - visibleRequests}")
            assertEquals(100, readyAt)
            assertEquals(7, visibleRequests)
            assertEquals(visibleRequests, fixture.requests.size)
            assertEquals(1, fixture.requests.count { it == "/api/billing/config" })
        }
    }

    @Test
    fun noObserversMeansNoRequestsAndHidingCancelsPendingLoad() = runTest {
        Fixture(this).use { fixture ->
            advanceTimeBy(45_000)
            runCurrent()
            assertTrue(fixture.requests.isEmpty())
            val first = fixture.observe()
            runCurrent()
            assertEquals(3, fixture.requests.size)
            first.cancelAndJoin()
            advanceTimeBy(45_000)
            runCurrent()
            assertEquals(3, fixture.requests.size)
            assertTrue(fixture.model.state.value.devices.isEmpty())
            assertNull(fixture.model.state.value.error)

            fixture.observe()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(6, fixture.requests.size)
            assertEquals(2, fixture.model.state.value.devices.size)
        }
    }

    @Test
    fun refreshesAreConflatedAndTokenChangesDoNotReloadIdentity() = runTest {
        Fixture(this).use { fixture ->
            fixture.observe()
            advanceTimeBy(100)
            runCurrent()
            fixture.authState.value = fixture.authState.value.copy(accessToken = "synthetic-refreshed")
            runCurrent()
            assertEquals(3, fixture.requests.size)
            assertEquals(2, fixture.model.state.value.devices.size)
            repeat(100) { fixture.model.refresh() }
            runCurrent()
            assertEquals(5, fixture.requests.size)
            repeat(100) { fixture.model.refresh() }
            advanceTimeBy(200)
            runCurrent()
            assertEquals(7, fixture.requests.size)
            assertEquals(1, fixture.requests.count { it == "/api/billing/config" })
            assertEquals(3, fixture.peakRequests)
        }
    }

    @Test
    fun accountChangesCancelOldLoadAndSelfHostedDoesNotPoll() = runTest {
        Fixture(this).use { fixture ->
            fixture.observe()
            runCurrent()
            advanceTimeBy(50)
            fixture.authState.value = fixture.authState.value.copy(userId = "user-2")
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals("user-2", fixture.model.state.value.userId)
            assertEquals("Your account", fixture.model.state.value.devices.single { it.deviceId == "device-2" }.ownerLabel)
            assertEquals(6, fixture.requests.size)
            fixture.phase.value = SessionPhase.Ready(fixture.auth, FeatureGate(fixture.auth, true), true)
            runCurrent()
            fixture.model.refresh()
            advanceTimeBy(45_000)
            runCurrent()
            assertEquals(6, fixture.requests.size)
            assertTrue(fixture.model.state.value.selfHosted)
            assertTrue(fixture.model.state.value.devices.isEmpty())
        }
    }

    @Test
    fun removalCancellationPropagatesAndSuccessfulRemovalRefreshes() = runTest {
        Fixture(this).use { fixture ->
            fixture.observe()
            advanceTimeBy(100)
            runCurrent()
            var returned = false
            val cancelled = launch { fixture.model.removeDevice("device-2"); returned = true }
            runCurrent()
            cancelled.cancelAndJoin()
            assertFalse(returned)
            val removed = async { fixture.model.removeDevice("device-2") }
            advanceTimeBy(200)
            runCurrent()
            assertEquals("Device removed", removed.await())
            assertEquals(7, fixture.requests.size) // 3 initial + 2 deletes + 2 refresh requests.
        }
    }

    private class Fixture(val scope: TestScope) : AutoCloseable {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val preferences = Preferences.userRoot().node("shilling-hosted-test-${UUID.randomUUID()}")
        val settings = PreferencesSettings(preferences).also { it.putString(SETTINGS_KEY_SERVER_URL, "https://synthetic.test") }
        val authState = MutableStateFlow(AuthState(true, "user-1", "synthetic@example.test", UserTier.FREE,
            "synthetic-token", false, "device-1"))
        val auth = object : AuthService by NoOpAuthService("device-1") {
            override val authState = this@Fixture.authState
            override suspend fun refreshTokenIfNeeded(): String? = authState.value.accessToken
        }
        val phase = MutableStateFlow<SessionPhase>(SessionPhase.Ready(auth, FeatureGate(auth, false), false))
        val requests = mutableListOf<String>()
        var responseDelay = 100L
        var fail = false
        var activeRequests = 0
        var peakRequests = 0
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = this@Fixture.dispatcher
                addHandler { request ->
                    val path = request.url.encodedPath
                    requests += path
                    activeRequests++
                    peakRequests = maxOf(peakRequests, activeRequests)
                    try { delay(responseDelay) } finally { activeRequests-- }
                    if (fail) error("Synthetic failure")
                    val body = when (path) {
                        "/api/tier" -> """{"accountPlan":"FREE","householdPlan":"FREE","deviceLimit":2,"registeredDevices":2,"turnEnabled":false,"bankReadingEnabled":false,"householdLimit":1,"canManageSpace":true}"""
                        "/api/devices/details" -> """[{"deviceId":"device-1","ownerUserId":"user-1","registeredAt":"2026-10-01"},{"deviceId":"device-2","ownerUserId":"user-2","registeredAt":"2026-10-01"}]"""
                        "/api/billing/config" -> "{}"
                        "/api/devices/device-2" -> "{}"
                        else -> error("Unexpected synthetic request $path")
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
            }
            install(ContentNegotiation) { json() }
        }
        val owner = ViewModelStore()
        val model: HostedDevicesViewModel
        fun observe() = scope.backgroundScope.launch { model.state.collect() }
        init {
            Dispatchers.setMain(dispatcher)
            model = HostedDevicesViewModel(object : SessionState { override val phase = this@Fixture.phase },
                settings, client, PeerConnectionStatus(), CloudRelayCallback { })
            owner.put("devices", model)
        }
        override fun close() {
            owner.clear()
            client.close()
            Dispatchers.resetMain()
            preferences.removeNode()
        }
    }
}
