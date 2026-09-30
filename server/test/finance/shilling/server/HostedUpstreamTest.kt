package finance.shilling.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HostedUpstreamTest {
    @Test
    fun coldAndExpiredConcurrentRequestsShareARefresh() = runBlocking<Unit> {
        Stub().use { stub ->
            val clock = TestClock()
            val verifier = SupabaseTokenVerifier(stub.config, clock = clock)
            val token = stub.token()
            suspend fun burst() = coroutineScope {
                List(40) { async(Dispatchers.Default) { verifier.verifyUserToken(token)?.userId } }.awaitAll()
            }
            assertTrue(burst().all { it == "user-1" })
            assertEquals(1, stub.jwksCalls.get())
            clock.advance(601)
            assertTrue(burst().all { it == "user-1" })
            assertEquals(2, stub.jwksCalls.get())
        }
    }

    @Test
    fun rotationRefreshesBeforeTtlAndUnknownIdsAreRateLimited() = runBlocking<Unit> {
        Stub().use { stub ->
            val clock = TestClock()
            val verifier = SupabaseTokenVerifier(stub.config, clock = clock)
            assertNotNull(verifier.verifyUserToken(stub.token()))
            stub.jwks = jwks("second", secondKey)
            val rotated = stub.token("second", secondKey)
            val users = List(40) { async(Dispatchers.Default) { verifier.verifyUserToken(rotated) } }.awaitAll()
            assertTrue(users.all { it?.userId == "user-1" })
            assertEquals(2, stub.jwksCalls.get())
            List(40) { index -> async(Dispatchers.Default) {
                assertNull(verifier.verifyUserToken(stub.token("unknown-$index")))
            } }.awaitAll()
            assertEquals(2, stub.jwksCalls.get())
            clock.advance(31)
            assertNull(verifier.verifyUserToken(stub.token("still-unknown")))
            assertEquals(3, stub.jwksCalls.get())
        }
    }

    @Test
    fun failedRefreshesAreCoalescedAndExpiredKeysFailClosed() = runBlocking<Unit> {
        Stub().use { stub ->
            val clock = TestClock()
            val verifier = SupabaseTokenVerifier(stub.config, clock = clock)
            val token = stub.token()
            assertNotNull(verifier.verifyUserToken(token))
            clock.advance(601)
            stub.jwksStatus = 503
            val failures = List(40) { async(Dispatchers.Default) { verifier.verifyUserToken(token) } }.awaitAll()
            assertTrue(failures.all { it == null })
            assertEquals(2, stub.jwksCalls.get())
            stub.jwksStatus = 200
            assertNull(verifier.verifyUserToken(token))
            assertEquals(2, stub.jwksCalls.get())
            clock.advance(2)
            assertNotNull(verifier.verifyUserToken(token))
            assertEquals(3, stub.jwksCalls.get())
        }
    }

    @Test
    fun signatureIssuerRoleAudienceAndExpiryStillRejectInvalidTokens() = runBlocking<Unit> {
        Stub().use { stub ->
            val verifier = SupabaseTokenVerifier(stub.config)
            assertNotNull(verifier.verifyUserToken(stub.token()))
            assertNull(verifier.verifyUserToken(stub.token(key = secondKey)))
            assertNull(verifier.verifyUserToken(stub.token(issuer = "https://wrong.invalid/auth/v1")))
            assertNull(verifier.verifyUserToken(stub.token(role = "service_role")))
            assertNull(verifier.verifyUserToken(stub.token(audience = "wrong")))
            assertNull(verifier.verifyUserToken(stub.token(expiry = Instant.now().minusSeconds(1))))
            assertEquals(1, stub.jwksCalls.get())
        }
    }

    @Test
    fun cancelledCallerIsNotConvertedIntoInvalidTokenOrMissingHousehold() = runBlocking<Unit> {
        Stub().use { stub ->
            stub.delayMillis = 1_000
            val verifier = SupabaseTokenVerifier(stub.config)
            val lookup = SupabaseHouseholdMembershipLookup(stub.url, "synthetic")
            assertFailsWith<CancellationException> { withTimeout(50) { verifier.verifyUserToken(stub.token()) } }
            assertFailsWith<CancellationException> { withTimeout(50) { lookup.householdIdForUser("user-1") } }
        }
    }

    @Test
    fun deadlineBoundsResponseBodyAndFailedJwksRequestsShareIt() = runBlocking<Unit> {
        Stub().use { stub ->
            stub.delayMillis = 0
            stub.stallBody = true
            val timeout = Duration.ofMillis(150)
            val verifier = SupabaseTokenVerifier(stub.config, requestTimeout = timeout)
            val token = stub.token()
            withTimeout(2_000) {
                val failures = List(20) { async(Dispatchers.Default) { verifier.verifyUserToken(token) } }.awaitAll()
                assertTrue(failures.all { it == null })
                assertEquals(1, stub.jwksCalls.get())
                val lookup = SupabaseHouseholdMembershipLookup(stub.url, "synthetic", requestTimeout = timeout)
                assertFailsWith<HostedUpstreamUnavailableException> { lookup.householdIdForUser("user-1") }
            }
        }
    }

    @Test
    fun lookupDistinguishesAbsenceFailureAndMalformedResponse() = runBlocking<Unit> {
        Stub().use { stub ->
            val lookup = SupabaseHouseholdMembershipLookup(stub.url, "synthetic")
            assertEquals("house-1", lookup.householdIdForUser("user-1"))
            stub.membership = "[]"
            assertNull(lookup.householdIdForUser("user-1"))
            stub.membershipStatus = 503
            assertFailsWith<HostedUpstreamUnavailableException> { lookup.householdIdForUser("user-1") }
            val rejected = authorizeHostedJoin(SupabaseTokenVerifier(stub.config), lookup, stub.token(), "house-1")
            assertEquals("Hosted household lookup unavailable", assertIs<JoinAuthorizationResult.Rejected>(rejected).reason)
            stub.membershipStatus = 200
            stub.membership = "malformed"
            assertFailsWith<HostedUpstreamUnavailableException> { lookup.householdIdForUser("user-1") }
        }
    }

    private class TestClock : Clock() {
        private var now = Instant.now()
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    }

    private class Stub : AutoCloseable {
        val jwksCalls = AtomicInteger()
        @Volatile var delayMillis = 100L
        @Volatile var jwks = jwks("first", firstKey)
        @Volatile var jwksStatus = 200
        @Volatile var membershipStatus = 200
        @Volatile var membership = """[{"household_id":"house-1"}]"""
        @Volatile var stallBody = false
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "synthetic-upstream").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Stub.executor
            createContext("/") { exchange ->
                try {
                    val isJwks = exchange.requestURI.path.endsWith("jwks.json")
                    if (isJwks) jwksCalls.incrementAndGet()
                    Thread.sleep(delayMillis)
                    val body = (if (isJwks) jwks else membership).toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(if (isJwks) jwksStatus else membershipStatus, if (stallBody) 0 else body.size.toLong())
                    if (stallBody) {
                        exchange.responseBody.write('{'.code)
                        exchange.responseBody.flush()
                        Thread.sleep(5_000)
                    } else exchange.responseBody.write(body)
                } catch (_: Exception) {
                    // Cancellation/timeout closes the test connection or interrupts shutdown.
                } finally { exchange.close() }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        val config = AuthConfig("supabase", url, "synthetic-public", "synthetic-service", null)
        fun token(
            kid: String = "first", key: KeyPair = firstKey,
            issuer: String = "$url/auth/v1", role: String = "authenticated",
            audience: String = "authenticated", expiry: Instant = Instant.now().plusSeconds(3_600)
        ): String = JWT.create().withKeyId(kid).withIssuer(issuer).withAudience(audience)
            .withSubject("user-1").withClaim("role", role).withExpiresAt(expiry)
            .sign(Algorithm.RSA256(key.public as RSAPublicKey, key.private as RSAPrivateKey))
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    private companion object {
        val firstKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val secondKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun jwks(kid: String, key: KeyPair): String {
            val rsa = key.public as RSAPublicKey
            fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(
                if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
            )
            return """{"keys":[{"kid":"$kid","alg":"RS256","kty":"RSA","n":"${encode(rsa.modulus.toByteArray())}","e":"${encode(rsa.publicExponent.toByteArray())}"}]}"""
        }
    }
}
