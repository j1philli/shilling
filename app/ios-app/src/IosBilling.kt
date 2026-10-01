package finance.shilling.app

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.configure
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class IosBillingOffer(val packageId: String, val title: String, val price: String)

object IosBilling {
    suspend fun configure(apiKey: String, userId: String) {
        if (!Purchases.isConfigured) {
            Purchases.configure(apiKey = apiKey) { appUserId = userId }
        } else if (Purchases.sharedInstance.appUserID != userId) {
            suspendCancellableCoroutine<Unit> { continuation ->
                Purchases.sharedInstance.logIn(userId,
                    onError = { continuation.resumeWithException(IllegalStateException(it.toString())) },
                    onSuccess = { _, _ -> continuation.resume(Unit) })
            }
        }
    }

    suspend fun offers(offeringId: String): List<IosBillingOffer> =
        suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.getOfferings(
                onError = { continuation.resumeWithException(IllegalStateException(it.toString())) },
                onSuccess = { offerings ->
                    continuation.resume(offerings.all[offeringId]?.availablePackages.orEmpty().map { pkg ->
                        IosBillingOffer(pkg.identifier, pkg.storeProduct.title, pkg.storeProduct.price.formatted)
                    })
                }
            )
        }

    suspend fun purchase(offeringId: String, packageId: String): Boolean {
        val pkg = suspendCancellableCoroutine<com.revenuecat.purchases.kmp.models.Package> { continuation ->
            Purchases.sharedInstance.getOfferings(
                onError = { continuation.resumeWithException(IllegalStateException(it.toString())) },
                onSuccess = { offerings ->
                    val selected = offerings.all[offeringId]?.availablePackages?.firstOrNull { it.identifier == packageId }
                    if (selected == null) continuation.resumeWithException(IllegalArgumentException("Offer no longer available"))
                    else continuation.resume(selected)
                }
            )
        }
        return suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.purchase(pkg,
                onError = { error, cancelled ->
                    if (cancelled) continuation.resume(false)
                    else continuation.resumeWithException(IllegalStateException(error.toString()))
                },
                onSuccess = { _, _ -> continuation.resume(true) })
        }
    }

    suspend fun restore() {
        suspendCancellableCoroutine<Unit> { continuation ->
            Purchases.sharedInstance.restorePurchases(
                onError = { continuation.resumeWithException(IllegalStateException(it.toString())) },
                onSuccess = { continuation.resume(Unit) })
        }
    }

    suspend fun managementUrl(): String? = suspendCancellableCoroutine { continuation ->
        Purchases.sharedInstance.getCustomerInfo(
            onError = { continuation.resumeWithException(IllegalStateException(it.toString())) },
            onSuccess = { continuation.resume(it.managementUrlString) }
        )
    }
}
