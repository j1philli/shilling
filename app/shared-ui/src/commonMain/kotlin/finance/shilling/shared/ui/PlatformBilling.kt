package finance.shilling.shared.ui

import finance.shilling.core.auth.HostedBillingConfig

data class BillingOffer(val packageId: String, val title: String, val price: String)

expect object PlatformBilling {
    val usesNativeStore: Boolean
    fun publicKey(config: HostedBillingConfig): String?
    suspend fun configure(apiKey: String, userId: String)
    suspend fun offers(offeringId: String): List<BillingOffer>
    suspend fun purchase(offeringId: String, packageId: String): Boolean
    suspend fun restore()
    suspend fun managementUrl(): String?
    fun openCheckout(url: String)
}
