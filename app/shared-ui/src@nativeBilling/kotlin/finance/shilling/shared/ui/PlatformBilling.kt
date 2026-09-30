package finance.shilling.shared.ui

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.configure
import finance.shilling.core.auth.HostedBillingConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

actual object PlatformBilling {
    actual val usesNativeStore = true
    actual fun publicKey(config: HostedBillingConfig): String? = nativeBillingPublicKey(config)

    actual suspend fun configure(apiKey: String, userId: String) {
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

    actual suspend fun offers(offeringId: String): List<BillingOffer> =
        suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.getOfferings(
                onError = { continuation.resumeWithException(IllegalStateException(it.toString())) },
                onSuccess = { offerings ->
                    continuation.resume(offerings.all[offeringId]?.availablePackages.orEmpty().map { pkg ->
                        BillingOffer(pkg.identifier, pkg.storeProduct.title, pkg.storeProduct.price.formatted)
                    })
                }
            )
        }

    actual suspend fun purchase(offeringId: String, packageId: String): Boolean {
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

    actual suspend fun restore() {
        suspendCancellableCoroutine<Unit> { continuation ->
            Purchases.sharedInstance.restorePurchases(
                onError = { continuation.resumeWithException(IllegalStateException(it.toString())) },
                onSuccess = { continuation.resume(Unit) })
        }
    }
    actual suspend fun managementUrl(): String? = suspendCancellableCoroutine { continuation ->
        Purchases.sharedInstance.getCustomerInfo(
            onError = { continuation.resumeWithException(IllegalStateException(it.toString())) },
            onSuccess = { continuation.resume(it.managementUrlString) }
        )
    }
    actual fun openCheckout(url: String) = Unit
}

internal expect fun nativeBillingPublicKey(config: HostedBillingConfig): String?
