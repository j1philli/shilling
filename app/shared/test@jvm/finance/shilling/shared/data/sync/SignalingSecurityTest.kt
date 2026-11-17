package finance.shilling.shared.data.sync

import finance.shilling.core.sync.SignalingMessage
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.NoOpAuthService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SignalingSecurityTest {
    @Test
    fun httpsBuildsOnlySecureWebSocketEndpoints() {
        assertEquals("wss://example.test/ws/signal", secureSignalingUrl("https://example.test/"))
        assertEquals("wss://example.test/ws/signal", secureSignalingUrl("HTTPS://example.test"))
        assertEquals("wss://example.test:8443/shilling/ws/signal", secureSignalingUrl("https://example.test:8443/shilling/"))
        assertEquals("wss://localhost:8443/ws/signal", secureSignalingUrl("https://localhost:8443"))
        assertEquals("wss://[::1]:8443/ws/signal", secureSignalingUrl("https://[::1]:8443"))
    }

    @Test
    fun rejectsInsecureAndAmbiguousServerUrls() {
        for (url in listOf(
            "http://example.test", "http://localhost:8081", "http://127.0.0.1:8081",
            "http://10.0.2.2:8081", "ws://example.test", "//example.test", "example.test",
            "https://", "https:///path", "https://user:password@example.test", "https://example.test?url=http://other.test",
            "https://example.test#fragment", "https://exam ple.test"
        )) {
            assertFailsWith<IllegalArgumentException>(url) { secureSignalingUrl(url) }
        }
    }

    @Test
    fun insecureSavedSettingsCannotStartARequestRefreshAuthOrSendJoin() = runTest {
        var requests = 0
        var refreshes = 0
        val client = HttpClient(MockEngine) {
            engine { addHandler { requests++; respond("") } }
        }
        val auth = object : AuthService by NoOpAuthService("device") {
            override suspend fun refreshTokenIfNeeded(): String? {
                refreshes++
                return "secret-token"
            }
        }
        try {
            for (url in listOf("http://localhost:8081", "http://10.0.2.2:8081", "ws://example.test")) {
                val api = ServerApi(client, url, auth)
                assertFailsWith<IllegalArgumentException> { api.fetchConfig() }
                assertFailsWith<IllegalArgumentException> { api.fetchHousehold() }
                val signaling = SignalingClient(client, url, "device", "household", auth)
                signaling.connect(backgroundScope)
                signaling.reconnectNow()
                assertFalse(signaling.isConnected)
                assertFalse(signaling.send(SignalingMessage.Join("device", "household", "secret-token")))
                signaling.disconnect()
            }
            assertEquals(0, requests)
            assertEquals(0, refreshes)
        } finally {
            client.close()
        }
    }

    @Test
    fun signalingTransportNeverFollowsRedirectsAndLeavesParentClientUsable() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requests += it.url.toString()
                    if (it.url.encodedPath == "/health") respond("", HttpStatusCode.OK)
                    else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "ws://insecure.test/ws/signal"))
                }
            }
        }
        val transport = client.signalingTransport()
        try {
            assertEquals(HttpStatusCode.Found, transport.get("wss://example.test/ws/signal").status)
            assertEquals(listOf("wss://example.test/ws/signal"), requests)
        } finally {
            transport.close()
        }
        try {
            // Closing a connection's child client must not close the shared app client.
            assertEquals(HttpStatusCode.OK, client.get("https://example.test/health").status)
        } finally {
            client.close()
        }
    }
}
