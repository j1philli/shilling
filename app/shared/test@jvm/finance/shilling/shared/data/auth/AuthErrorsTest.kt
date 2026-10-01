package finance.shilling.shared.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthErrorsTest {
    @Test
    fun emailLimitHasActionableCopyWithoutProviderCode() {
        val message = AuthErrors.messageForCode("over_email_send_rate_limit", 429)
        assertTrue(message.contains("email"))
        assertTrue(message.contains("later"))
        assertFalse(message.contains("over_email_send_rate_limit"))
    }

    @Test
    fun unknownFailuresDoNotExposeResponseBodiesOrCredentialsInUiOrLogs() {
        val error = IllegalStateException("Authorization: Bearer secret-token password=hunter2 user@example.com")
        val message = AuthErrors.message(error, "sign_in")
        val diagnostic = AuthErrors.diagnostic("sign_in", error)
        listOf("secret-token", "hunter2", "user@example.com", "Authorization").forEach {
            assertFalse(message.contains(it))
            assertFalse(diagnostic.contains(it))
        }
        assertTrue(diagnostic.contains("operation=sign_in"))
        assertTrue(diagnostic.contains("type=IllegalStateException"))
    }

    @Test
    fun unknownRateLimitStillGetsRetryMessage() {
        assertEquals(AuthErrors.messageForCode("over_request_rate_limit"), AuthErrors.messageForCode(null, 429))
    }
}
