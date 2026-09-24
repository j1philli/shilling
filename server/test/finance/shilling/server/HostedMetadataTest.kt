package finance.shilling.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HostedMetadataTest {
    @Test
    fun authorizeHostedJoinRejectsMissingToken() = runBlocking {
        val result = authorizeHostedJoin(
            tokenVerifier = FakeVerifier(mapOf()),
            lookup = FakeLookup(mapOf("user-1" to "house-1")),
            accessToken = null,
            requestedHouseholdId = "house-1"
        )

        assertEquals("Missing token", assertIs<JoinAuthorizationResult.Rejected>(result).reason)
    }

    @Test
    fun authorizeHostedJoinRejectsMismatchedHousehold() = runBlocking {
        val token = tokenFor("user-1")
        val result = authorizeHostedJoin(
            tokenVerifier = FakeVerifier(mapOf(token to "user-1")),
            lookup = FakeLookup(mapOf("user-1" to "house-1")),
            accessToken = token,
            requestedHouseholdId = "house-2"
        )

        assertEquals("Household mismatch", assertIs<JoinAuthorizationResult.Rejected>(result).reason)
    }

    @Test
    fun authorizeHostedJoinRejectsWhenLookupUnavailable() = runBlocking {
        val token = tokenFor("user-1")
        val result = authorizeHostedJoin(
            tokenVerifier = FakeVerifier(mapOf(token to "user-1")),
            lookup = null,
            accessToken = token,
            requestedHouseholdId = "house-1"
        )

        assertEquals("Hosted household lookup unavailable", assertIs<JoinAuthorizationResult.Rejected>(result).reason)
    }

    @Test
    fun authorizeHostedJoinAcceptsMatchingHousehold() = runBlocking {
        val token = tokenFor("user-1")
        val result = authorizeHostedJoin(
            tokenVerifier = FakeVerifier(mapOf(token to "user-1")),
            lookup = FakeLookup(mapOf("user-1" to "house-1")),
            accessToken = token,
            requestedHouseholdId = "house-1"
        )

        assertEquals("user-1", assertIs<JoinAuthorizationResult.Authorized>(result).userId)
    }

    private fun tokenFor(subject: String): String =
        JWT.create()
            .withAudience("authenticated")
            .withSubject(subject)
            .sign(Algorithm.HMAC256("test-secret"))

    private class FakeLookup(
        private val households: Map<String, String>
    ) : HouseholdMembershipLookup {
        override suspend fun householdIdForUser(userId: String): String? = households[userId]
    }

    private class FakeVerifier(
        private val subjectsByToken: Map<String, String>
    ) : AccessTokenVerifier {
        override suspend fun verifyUserToken(token: String): VerifiedSupabaseUser? {
            val subject = subjectsByToken[token] ?: return null
            return VerifiedSupabaseUser(
                userId = subject,
                issuer = "test",
                role = "authenticated",
                expiresAtEpochSeconds = Long.MAX_VALUE
            )
        }
    }
}
