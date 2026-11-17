package finance.shilling.shared.ui

import finance.shilling.core.auth.HostedBillingConfig

actual object PlatformBilling {
    actual val usesNativeStore = false
    actual fun publicKey(config: HostedBillingConfig): String? = null
    actual suspend fun configure(apiKey: String, userId: String) = Unit
    actual suspend fun offers(offeringId: String): List<BillingOffer> = emptyList()
    actual suspend fun purchase(offeringId: String, packageId: String) = false
    actual suspend fun restore() = Unit
    actual suspend fun managementUrl(): String? = null
    actual fun openCheckout(url: String) = openExternalCheckout(url)
}

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("(url) => { if (globalThis.__TAURI__?.shell?.open) { globalThis.__TAURI__.shell.open(url); } else { globalThis.open(url, '_blank', 'noopener'); } }")
private external fun openExternalCheckout(url: String)
