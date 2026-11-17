package finance.shilling.shared.presentation

import finance.shilling.core.auth.HostedPlan
import finance.shilling.shared.data.auth.AccountDeletionException
import finance.shilling.shared.data.auth.AccountDeletionResult
import finance.shilling.shared.data.auth.DeleteHostedAccountCallback
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountDeletionFlowTest {
    private class Recorder(val result: (Boolean) -> Result<AccountDeletionResult>) {
        val acknowledgements = mutableListOf<Boolean>()
        val callback = DeleteHostedAccountCallback { ack -> acknowledgements += ack; result(ack) }
    }

    @Test
    fun freePlanGoesStraightToConfirmationWithoutAcknowledgement() = runBlocking {
        val recorder = Recorder { Result.success(AccountDeletionResult.Deleted) }
        val flow = AccountDeletionFlow(recorder.callback)
        flow.start(HostedPlan.FREE)
        assertEquals(AccountDeletionStep.Confirm(acknowledgeActiveSubscription = false), flow.step.value)
        assertNull(flow.confirm(acknowledgeActiveSubscription = false))
        assertEquals(listOf(false), recorder.acknowledgements)
        assertEquals(AccountDeletionStep.Idle, flow.step.value)
    }

    @Test
    fun paidPlanShowsSubscriptionReminderBeforeAcknowledgedConfirmation() {
        val flow = AccountDeletionFlow(Recorder { Result.success(AccountDeletionResult.Deleted) }.callback)
        flow.start(HostedPlan.GOLD)
        assertEquals(AccountDeletionStep.SubscriptionWarning(HostedPlan.GOLD), flow.step.value)
        flow.continueAfterWarning()
        assertEquals(AccountDeletionStep.Confirm(acknowledgeActiveSubscription = true), flow.step.value)
    }

    @Test
    fun serverReportedSubscriptionReturnsToReminder() = runBlocking {
        val flow = AccountDeletionFlow(Recorder { Result.success(AccountDeletionResult.SubscriptionActive(HostedPlan.SILVER)) }.callback)
        flow.start(accountPlan = null)
        assertNull(flow.confirm(acknowledgeActiveSubscription = false))
        assertEquals(AccountDeletionStep.SubscriptionWarning(HostedPlan.SILVER), flow.step.value)
    }

    @Test
    fun onlyUserFacingFailuresAreShownVerbatim() = runBlocking {
        val userFacing = AccountDeletionFlow(Recorder { Result.failure(AccountDeletionException("Sign in to delete your account")) }.callback)
        assertEquals("Sign in to delete your account", userFacing.confirm(false))
        assertEquals(AccountDeletionStep.Idle, userFacing.step.value)

        val network = AccountDeletionFlow(Recorder { Result.failure(IllegalStateException("socket closed")) }.callback)
        assertEquals("Couldn't delete your account. Check your connection and try again.", network.confirm(false))
    }

    @Test
    fun cancelDoesNotInterruptAnInFlightDeletion() = runBlocking {
        lateinit var flow: AccountDeletionFlow
        flow = AccountDeletionFlow(DeleteHostedAccountCallback {
            flow.cancel()
            assertEquals(AccountDeletionStep.Deleting, flow.step.value)
            Result.success(AccountDeletionResult.Deleted)
        })
        flow.confirm(false)
        assertEquals(AccountDeletionStep.Idle, flow.step.value)
    }
}
