package finance.shilling.core.auth

import kotlinx.serialization.Serializable

@Serializable
enum class HostedPlan { FREE, SILVER, GOLD }

@Serializable
data class HostedEntitlementsResponse(
    val accountPlan: HostedPlan,
    val householdPlan: HostedPlan,
    val deviceLimit: Int?,
    val registeredDevices: Int,
    val turnEnabled: Boolean,
    val bankReadingEnabled: Boolean,
    val householdLimit: Int?,
    val canManageSpace: Boolean = false
)

@Serializable
data class HostedBillingConfig(
    val androidPublicKey: String? = null,
    val iosPublicKey: String? = null,
    val silverOfferingId: String? = null,
    val goldOfferingId: String? = null,
    val silverWebPurchaseUrl: String? = null,
    val goldWebPurchaseUrl: String? = null
)

fun HostedPlan.deviceLimit(): Int? = if (this == HostedPlan.FREE) 2 else null
fun HostedPlan.householdLimit(): Int? = if (this == HostedPlan.GOLD) null else 1
fun HostedPlan.turnEnabled(): Boolean = this >= HostedPlan.SILVER
fun HostedPlan.bankReadingEnabled(): Boolean = this >= HostedPlan.SILVER
