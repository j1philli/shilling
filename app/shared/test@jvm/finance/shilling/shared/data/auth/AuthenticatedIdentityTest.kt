package finance.shilling.shared.data.auth

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class AuthenticatedIdentityTest {
    @Test
    fun budgetCheckWaitsForTheSignedInSessionInsteadOfReadingTheGuest() = runBlocking {
        val state = MutableStateFlow(AuthState(true, "guest", null, UserTier.ANONYMOUS, "guest-token", true, "device"))
        val accountForBudgetCheck = async(start = CoroutineStart.UNDISPATCHED) {
            state.awaitSessionIdentity("original-account", "new-token")
            state.value.userId
        }
        assertFalse(accountForBudgetCheck.isCompleted)
        state.value = state.value.copy(userId = "original-account", accessToken = "old-token")
        yield()
        assertFalse(accountForBudgetCheck.isCompleted)
        state.value = state.value.copy(accessToken = "new-token", isAnonymous = false, tier = UserTier.FREE)
        assertEquals("original-account", accountForBudgetCheck.await())
    }
}
