package finance.shilling.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupabaseTokenVerifierTest {
    private val config = AuthConfig(
        authMode = "supabase",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "publishable",
        supabaseServiceKey = "server-key",
        supabaseJwtSecret = "test-secret"
    )

    @Test
    fun acceptsAuthenticatedAccessToken() = runBlocking {
        assertEquals("user-1", SupabaseTokenVerifier(config).verifyUserToken(token())?.userId)
    }

    @Test
    fun rejectsTokenWithWrongRoleOrIssuer() = runBlocking {
        val verifier = SupabaseTokenVerifier(config)
        assertNull(verifier.verifyUserToken(token(role = "service_role")))
        assertNull(verifier.verifyUserToken(token(issuer = "https://other.supabase.co/auth/v1")))
    }

    @Test
    fun hostedVerifierRequiresProjectUrl() {
        assertNull(createSupabaseTokenVerifier(config.copy(supabaseUrl = null)))
    }

    private fun token(
        role: String = "authenticated",
        issuer: String = "https://example.supabase.co/auth/v1"
    ): String = JWT.create()
        .withIssuer(issuer)
        .withAudience("authenticated")
        .withSubject("user-1")
        .withClaim("role", role)
        .withExpiresAt(Instant.now().plusSeconds(300))
        .sign(Algorithm.HMAC256("test-secret"))
}
