package finance.shilling.server

import finance.shilling.core.auth.HostedBillingConfig
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.billingConfigRoute(tokenVerifier: SupabaseTokenVerifier?) {
    get("/api/billing/config") {
        tokenVerifier?.requireUser(call) ?: return@get
        call.respond(HostedBillingConfig(
            androidPublicKey = System.getenv("SHILLING_REVENUECAT_ANDROID_PUBLIC_KEY")?.takeIf(String::isNotBlank),
            iosPublicKey = System.getenv("SHILLING_REVENUECAT_IOS_PUBLIC_KEY")?.takeIf(String::isNotBlank),
            silverOfferingId = System.getenv("SHILLING_REVENUECAT_SILVER_OFFERING_ID")?.takeIf(String::isNotBlank),
            goldOfferingId = System.getenv("SHILLING_REVENUECAT_GOLD_OFFERING_ID")?.takeIf(String::isNotBlank),
            silverWebPurchaseUrl = System.getenv("SHILLING_REVENUECAT_SILVER_WEB_LINK")?.takeIf(String::isNotBlank),
            goldWebPurchaseUrl = System.getenv("SHILLING_REVENUECAT_GOLD_WEB_LINK")?.takeIf(String::isNotBlank)
        ))
    }
}
