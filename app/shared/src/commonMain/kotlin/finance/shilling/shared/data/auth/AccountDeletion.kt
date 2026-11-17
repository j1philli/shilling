package finance.shilling.shared.data.auth

import finance.shilling.core.auth.HostedPlan

/** Outcome of the hosted `POST /api/account/delete`. */
sealed interface AccountDeletionResult {
    data object Deleted : AccountDeletionResult

    /** A paid plan is active; ask the user to cancel or acknowledge it, then retry. */
    data class SubscriptionActive(val plan: HostedPlan) : AccountDeletionResult
}

/** A failure whose message is written for the user. */
class AccountDeletionException(message: String) : Exception(message)

/** Settings "Delete account": deletes the hosted account, then wipes this device. */
class DeleteHostedAccountCallback(
    val onDelete: suspend (acknowledgeActiveSubscription: Boolean) -> Result<AccountDeletionResult>
)
