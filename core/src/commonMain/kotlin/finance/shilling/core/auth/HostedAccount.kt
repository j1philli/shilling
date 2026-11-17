package finance.shilling.core.auth

import kotlinx.serialization.Serializable

/**
 * `POST /api/account/delete`. Store subscriptions are billed by Apple, Google, or the web billing
 * provider and are not cancelled by deleting the account, so a paid account must acknowledge that.
 */
@Serializable
data class HostedAccountDeletionRequest(val acknowledgeActiveSubscription: Boolean = false)

/** 409 body when [HostedAccountDeletionRequest.acknowledgeActiveSubscription] is required. */
@Serializable
data class HostedAccountDeletionConflict(
    val error: String,
    val activeSubscriptionPlan: HostedPlan
)
