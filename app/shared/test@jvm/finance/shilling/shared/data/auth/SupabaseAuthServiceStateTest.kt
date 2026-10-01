package finance.shilling.shared.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseAuthServiceStateTest {
    @Test
    fun pendingEmailConfirmationKeepsGuestTier() {
        val authState = resolveAuthenticatedState(
            userId = "user-1",
            sessionEmail = null,
            emailConfirmed = false,
            token = "token-1",
            deviceId = "device-1",
            profileTier = UserTier.ANONYMOUS,
            pendingEmail = "user@example.com",
            pendingEmailConfirmation = true,
            needsPasswordSetup = true
        )

        assertEquals(UserTier.ANONYMOUS, authState.tier)
        assertEquals("user@example.com", authState.email)
        assertTrue(authState.isAnonymous)
        assertTrue(authState.pendingEmailConfirmation)
        assertFalse(authState.needsPasswordSetup)
    }

    @Test
    fun confirmedEmailClearsPendingConfirmationState() {
        val authState = resolveAuthenticatedState(
            userId = "user-1",
            sessionEmail = "user@example.com",
            emailConfirmed = true,
            token = "token-1",
            deviceId = "device-1",
            profileTier = UserTier.ANONYMOUS,
            pendingEmail = "user@example.com",
            pendingEmailConfirmation = true,
            needsPasswordSetup = true
        )

        assertEquals(UserTier.FREE, authState.tier)
        assertEquals("user@example.com", authState.email)
        assertFalse(authState.pendingEmailConfirmation)
        assertNull(authState.pendingEmail)
        assertTrue(authState.needsPasswordSetup)
    }

    @Test
    fun unverifiedSessionEmailCannotUnlockFreeTier() {
        val authState = resolveAuthenticatedState(
            userId = "user-1",
            sessionEmail = "user@example.com",
            emailConfirmed = false,
            token = "token-1",
            deviceId = "device-1",
            profileTier = UserTier.FREE,
            pendingEmail = "user@example.com",
            pendingEmailConfirmation = true,
            needsPasswordSetup = true
        )

        assertEquals(UserTier.ANONYMOUS, authState.tier)
        assertTrue(authState.isAnonymous)
        assertTrue(authState.pendingEmailConfirmation)
        assertFalse(authState.needsPasswordSetup)
    }

    @Test
    fun authCallbackCannotSwitchToAnUnrequestedAccount() {
        assertFalse(mayImportAuthCallback("other", "other@example.com", "current", null, null))
        assertFalse(mayImportAuthCallback("other", "other@example.com", "current", "user@example.com", null))
        assertFalse(mayImportAuthCallback("other", "user@example.com", "current", "user@example.com", "held"))
        assertTrue(mayImportAuthCallback("current", null, "current", null, null))
        assertTrue(mayImportAuthCallback("existing", "User@Example.com", "guest", "user@example.com", null))
    }
}
