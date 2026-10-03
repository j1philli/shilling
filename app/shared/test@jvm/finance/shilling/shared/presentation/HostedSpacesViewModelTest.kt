package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModelStore
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.auth.*
import finance.shilling.shared.data.store.LinkedTransferStoreTest
import finance.shilling.shared.session.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HostedSpacesViewModelTest {
    @Test fun hiddenLoadsCancelAndReopeningRecoversWithoutDuplicatingEntitlements() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = LinkedTransferStoreTest.Fixture().prepare()
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
            requests += request.url.encodedPath
            delay(100)
            respond(if (request.url.encodedPath == "/api/spaces") """{"spaces":[],"activeSpaceId":"home"}"""
                else """{"accountPlan":"FREE","householdPlan":"FREE","deviceLimit":2,"registeredDevices":1,"turnEnabled":false,"bankReadingEnabled":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } }; install(ContentNegotiation) { json() } }
        val owner = ViewModelStore()
        try {
            f.settings.putString(SETTINGS_KEY_SERVER_URL, "https://synthetic.test")
            val auth = NoOpAuthService("synthetic-device")
            val session = object : SessionState { override val phase = MutableStateFlow<SessionPhase>(SessionPhase.Ready(auth, FeatureGate(auth, false), false)) }
            val model = HostedSpacesViewModel(f.settings, client, session, SpaceSelectionCallback {}, f.graphs, f.transfers, f.ids)
            owner.put("spaces", model)
            advanceTimeBy(1000); runCurrent()
            assertTrue(requests.isEmpty())
            val hidden = backgroundScope.launch { model.state.collect() }
            runCurrent()
            assertEquals(2, requests.size)
            hidden.cancelAndJoin(); runCurrent()
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.error)
            advanceTimeBy(1000); runCurrent()
            assertEquals(2, requests.size)
            backgroundScope.launch { model.state.collect() }
            runCurrent()
            advanceTimeBy(100); runCurrent()
            withContext(Dispatchers.Default) { delay(50) }
            runCurrent()
            assertEquals("home", model.state.value.data.activeSpaceId)
            assertFalse(model.state.value.busy)
            assertEquals(4, requests.size)
        } finally {
            owner.clear(); client.close(); f.close(); Dispatchers.resetMain()
        }
    }
}
