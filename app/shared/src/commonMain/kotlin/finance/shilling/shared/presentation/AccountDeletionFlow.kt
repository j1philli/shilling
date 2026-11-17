package finance.shilling.shared.presentation

import finance.shilling.core.auth.HostedPlan
import finance.shilling.shared.data.auth.AccountDeletionException
import finance.shilling.shared.data.auth.AccountDeletionResult
import finance.shilling.shared.data.auth.DeleteHostedAccountCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings "Delete account" (signed-in hosted accounts only). A paid plan first shows a reminder
 * that store subscriptions are not cancelled by deleting the account, then the final confirmation.
 */
sealed interface AccountDeletionStep {
    data object Idle : AccountDeletionStep

    data class SubscriptionWarning(val plan: HostedPlan) : AccountDeletionStep {
        val title get() = "Cancel your ${plan.label} subscription"
        val message get() = "Deleting your account doesn't cancel your subscription. To stop future charges, " +
            "cancel it where you subscribed: the App Store, Google Play, or the link in your billing email."
        val manageLabel get() = "Manage subscription"
        val continueLabel get() = "Continue"
    }

    data class Confirm(val acknowledgeActiveSubscription: Boolean) : AccountDeletionStep {
        val confirmCopy get() = ConfirmCopy(
            title = "Delete your account?",
            message = "This permanently deletes your Shilling account and erases the budget on this device. " +
                "Finance spaces you share keep working for their other members. Copies already synced to " +
                "your other devices stay there until you reset them. This can't be undone.",
            confirmLabel = "Delete account",
            destructive = true
        )
    }

    data object Deleting : AccountDeletionStep

    companion object {
        const val ACTION_LABEL = "Delete account"
        const val DELETING_LABEL = "Deleting account…"
        const val FOOTNOTE = "Permanently deletes your account and the budget on this device."
    }
}

private val HostedPlan.label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

/** The Settings "Delete account" steps, shared by Compose and SwiftUI through [SettingsViewModel]. */
class AccountDeletionFlow(private val callback: DeleteHostedAccountCallback) {
    private val mutableStep = MutableStateFlow<AccountDeletionStep>(AccountDeletionStep.Idle)
    val step: StateFlow<AccountDeletionStep> = mutableStep

    /** [accountPlan]: the plan Settings already shows, if loaded. The server re-checks it. */
    fun start(accountPlan: HostedPlan?) {
        if (mutableStep.value == AccountDeletionStep.Deleting) return
        mutableStep.value = if (accountPlan != null && accountPlan > HostedPlan.FREE) {
            AccountDeletionStep.SubscriptionWarning(accountPlan)
        } else {
            AccountDeletionStep.Confirm(acknowledgeActiveSubscription = false)
        }
    }

    fun continueAfterWarning() {
        if (mutableStep.value is AccountDeletionStep.SubscriptionWarning) {
            mutableStep.value = AccountDeletionStep.Confirm(acknowledgeActiveSubscription = true)
        }
    }

    fun cancel() {
        if (mutableStep.value != AccountDeletionStep.Deleting) mutableStep.value = AccountDeletionStep.Idle
    }

    /**
     * Deletes the account after the final [AccountDeletionStep.Confirm] (pass its flag; dialogs may
     * dismiss before confirming). On success the session returns to Welcome.
     * Returns an error message to show, or null.
     */
    suspend fun confirm(acknowledgeActiveSubscription: Boolean): String? {
        if (mutableStep.value == AccountDeletionStep.Deleting) return null
        mutableStep.value = AccountDeletionStep.Deleting
        val result = callback.onDelete(acknowledgeActiveSubscription)
        return when (val outcome = result.getOrNull()) {
            AccountDeletionResult.Deleted -> {
                mutableStep.value = AccountDeletionStep.Idle
                null
            }
            // The server found a subscription Settings hadn't loaded yet.
            is AccountDeletionResult.SubscriptionActive -> {
                mutableStep.value = AccountDeletionStep.SubscriptionWarning(outcome.plan)
                null
            }
            null -> {
                mutableStep.value = AccountDeletionStep.Idle
                // Network and parsing errors aren't written for users.
                (result.exceptionOrNull() as? AccountDeletionException)?.message
                    ?: "Couldn't delete your account. Check your connection and try again."
            }
        }
    }
}
