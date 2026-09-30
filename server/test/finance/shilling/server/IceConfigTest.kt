package finance.shilling.server

import finance.shilling.core.sync.IceServerConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class IceConfigTest {
    private val cloudflareConfig = TurnConfig(
        stunUrls = listOf("stun:example.org:3478"),
        turnUrls = emptyList(),
        turnSecret = null,
        ttlSeconds = 3600,
        cloudflareKeyId = "key-id",
        cloudflareApiToken = "server-secret"
    )

    @Test
    fun cloudflareCredentialsReachTheIceResponse() = runBlocking {
        val provider = FakeCloudflareProvider()
        val response = iceServersForRequest(cloudflareConfig, includeTurn = true, provider)

        assertEquals(1, provider.calls)
        assertEquals(3600, provider.requestedTtl)
        assertEquals(3600, response.ttlSeconds)
        assertEquals("turn:turn.cloudflare.com:3478?transport=udp", response.iceServers[1].urls.single())
        assertEquals("short-lived-user", response.iceServers[1].username)
        assertEquals("short-lived-password", response.iceServers[1].credential)
    }

    @Test
    fun unauthenticatedRequestDoesNotIssueCloudflareCredentials() = runBlocking {
        val provider = FakeCloudflareProvider()
        val response = iceServersForRequest(cloudflareConfig, includeTurn = false, provider)

        assertEquals(0, provider.calls)
        assertEquals(listOf("stun:example.org:3478"), response.iceServers.single().urls)
    }

    @Test
    fun coturnCredentialPathRemainsAvailable() = runBlocking {
        val provider = FakeCloudflareProvider()
        val response = iceServersForRequest(
            TurnConfig(
                stunUrls = emptyList(),
                turnUrls = listOf("turn:coturn.example.org:3478"),
                turnSecret = "test-secret",
                ttlSeconds = 60
            ),
            includeTurn = true,
            cloudflareProvider = provider
        )

        assertEquals(0, provider.calls)
        assertTrue(response.iceServers.single().username!!.endsWith(":shilling"))
        assertNotNull(response.iceServers.single().credential)
        assertEquals("password", response.iceServers.single().credentialType)
    }

    @Test
    fun cloudflareResponseMustIncludeUsableTurnCredentials() {
        val servers = parseCloudflareIceServers(
            """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},{"urls":["turn:turn.cloudflare.com:3478?transport=udp"],"username":"user","credential":"password"}]}"""
        )
        assertEquals(2, servers.size)
        assertFalse(servers[1].credential.isNullOrBlank())

        assertFailsWith<IllegalArgumentException> {
            parseCloudflareIceServers("""{"iceServers":[{"urls":["turn:turn.cloudflare.com:3478"]}]}""")
        }
    }

    private class FakeCloudflareProvider : CloudflareTurnProvider {
        var calls = 0
        var requestedTtl = 0

        override suspend fun generateIceServers(
            keyId: String,
            apiToken: String,
            ttlSeconds: Int
        ): List<IceServerConfig> {
            assertEquals("key-id", keyId)
            assertEquals("server-secret", apiToken)
            calls++
            requestedTtl = ttlSeconds
            return listOf(IceServerConfig(
                urls = listOf("turn:turn.cloudflare.com:3478?transport=udp"),
                username = "short-lived-user",
                credential = "short-lived-password"
            ))
        }
    }
}
