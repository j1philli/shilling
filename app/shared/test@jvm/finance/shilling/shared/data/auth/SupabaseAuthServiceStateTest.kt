package finance.shilling.shared.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseAuthServiceStateTest {
    @Test
    fun pendingEmailConfirmationPromotesGuestToFreeImmediately() {
        val authState = resolveAuthenticatedState(
            userId = "user-1",
            sessionEmail = null,
            token = "token-1",
            deviceId = "device-1",
            storedTier = UserTier.ANONYMOUS,
            profileTier = UserTier.ANONYMOUS,
            pendingEmail = "user@example.com",
            pendingEmailConfirmation = true
        )

        assertEquals(UserTier.FREE, authState.tier)
        assertEquals("user@example.com", authState.email)
        assertFalse(authState.isAnonymous)
        assertTrue(authState.pendingEmailConfirmation)
    }

    @Test
    fun confirmedEmailClearsPendingConfirmationState() {
        val authState = resolveAuthenticatedState(
            userId = "user-1",
            sessionEmail = "user@example.com",
            token = "token-1",
            deviceId = "device-1",
            storedTier = UserTier.FREE,
            profileTier = UserTier.FREE,
            pendingEmail = "user@example.com",
            pendingEmailConfirmation = true
        )

        assertEquals(UserTier.FREE, authState.tier)
        assertEquals("user@example.com", authState.email)
        assertFalse(authState.pendingEmailConfirmation)
        assertNull(authState.pendingEmail)
    }
}
