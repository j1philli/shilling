package finance.shilling.server

import finance.shilling.core.auth.HostedEntitlementsResponse
import finance.shilling.core.auth.HostedPlan
import finance.shilling.core.auth.bankReadingEnabled
import finance.shilling.core.auth.deviceLimit
import finance.shilling.core.auth.householdLimit
import finance.shilling.core.auth.turnEnabled
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

interface HostedEntitlementLookup {
    suspend fun accountPlan(userId: String): HostedPlan
    suspend fun householdPlan(householdId: String): HostedPlan
    fun invalidate(userId: String) = Unit
}

object FreeHostedEntitlementLookup : HostedEntitlementLookup {
    override suspend fun accountPlan(userId: String) = HostedPlan.FREE
    override suspend fun householdPlan(householdId: String) = HostedPlan.FREE
}

/** Only the RevenueCat server API can grant a paid plan. Client profile tier is ignored. */
class RevenueCatEntitlementLookup(
    private val supabaseUrl: String,
    private val supabaseServiceKey: String,
    private val projectId: String,
    private val revenueCatSecretKey: String,
    private val silverEntitlementId: String,
    private val goldEntitlementId: String
) : HostedEntitlementLookup {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CachedPlan>()

    override fun invalidate(userId: String) {
        cache.remove(userId)
    }

    override suspend fun accountPlan(userId: String): HostedPlan {
        val now = System.currentTimeMillis()
        cache[userId]?.takeIf { it.expiresAt > now }?.let { return it.plan }
        val plan = withContext(Dispatchers.IO) {
            val url = "https://api.revenuecat.com/v2/projects/${encode(projectId)}/customers/${encode(userId)}/active_entitlements?limit=100"
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer $revenueCatSecretKey")
                .header("Accept", "application/json")
                .GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 404) return@withContext HostedPlan.FREE
            check(response.statusCode() in 200..299) { "RevenueCat entitlement lookup failed: ${response.statusCode()}" }
            val body = json.parseToJsonElement(response.body()).jsonObject
            val ids = body["items"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonObject["entitlement_id"]?.jsonPrimitive?.content
            }
            // A full page requires another request; fail closed rather than miss a Gold entitlement.
            check(body["next_page"] == null || body["next_page"].toString() == "null") {
                "RevenueCat entitlement list exceeds one page"
            }
            planForEntitlementIds(ids, silverEntitlementId, goldEntitlementId)
        }
        cache[userId] = CachedPlan(plan, now + 30_000)
        return plan
    }

    override suspend fun householdPlan(householdId: String): HostedPlan = withContext(Dispatchers.IO) {
        val url = "${supabaseUrl.trimEnd('/')}/rest/v1/hosted_space_memberships?select=user_id&space_id=eq.${encode(householdId)}"
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("apikey", supabaseServiceKey)
            .header("Authorization", "Bearer $supabaseServiceKey")
            .header("Accept", "application/json")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Hosted household members lookup failed: ${response.statusCode()}" }
        val members = json.decodeFromString<List<MemberRow>>(response.body())
        var highest = HostedPlan.FREE
        for (member in members) {
            val plan = accountPlan(member.userId)
            if (plan > highest) highest = plan
            if (highest == HostedPlan.GOLD) break
        }
        highest
    }

    private data class CachedPlan(val plan: HostedPlan, val expiresAt: Long)
}

internal fun planForEntitlementIds(ids: List<String>, silverId: String, goldId: String): HostedPlan = when {
    goldId in ids -> HostedPlan.GOLD
    silverId in ids -> HostedPlan.SILVER
    else -> HostedPlan.FREE
}

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

@Serializable
private data class MemberRow(@kotlinx.serialization.SerialName("user_id") val userId: String)

fun createHostedEntitlementLookup(authConfig: AuthConfig): HostedEntitlementLookup? {
    if (authConfig.authMode != "supabase") return null
    val projectId = System.getenv("SHILLING_REVENUECAT_PROJECT_ID")?.takeIf(String::isNotBlank)
    val secret = System.getenv("SHILLING_REVENUECAT_SECRET_KEY")?.takeIf(String::isNotBlank)
    val silverId = System.getenv("SHILLING_REVENUECAT_SILVER_ENTITLEMENT_ID")?.takeIf(String::isNotBlank)
    val goldId = System.getenv("SHILLING_REVENUECAT_GOLD_ENTITLEMENT_ID")?.takeIf(String::isNotBlank)
    if (listOf(projectId, secret, silverId, goldId).all { it == null }) return FreeHostedEntitlementLookup
    require(listOf(projectId, secret, silverId, goldId).all { it != null }) {
        "RevenueCat project ID, secret key, and Silver/Gold entitlement IDs must all be configured"
    }
    return RevenueCatEntitlementLookup(
        supabaseUrl = authConfig.supabaseUrl ?: return null,
        supabaseServiceKey = authConfig.supabaseServiceKey ?: return null,
        projectId = projectId!!,
        revenueCatSecretKey = secret!!,
        silverEntitlementId = silverId!!,
        goldEntitlementId = goldId!!
    )
}

fun Route.hostedEntitlementRoute(
    tokenVerifier: SupabaseTokenVerifier?,
    householdLookup: HouseholdMembershipLookup?,
    entitlements: HostedEntitlementLookup?,
    devices: HostedDeviceRegistry?,
    turnConfigured: Boolean
) {
    route("/api/tier") {
        get {
            val user = tokenVerifier?.requireUser(call) ?: return@get
            val householdId = householdLookup?.householdIdForUser(user.userId)
            if (householdId == null || entitlements == null || devices == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted entitlements unavailable"))
                return@get
            }
            val result = runCatching {
                if (call.request.queryParameters["refresh"] == "true") entitlements.invalidate(user.userId)
                val accountPlan = entitlements.accountPlan(user.userId)
                val householdPlan = entitlements.householdPlan(householdId)
                HostedEntitlementsResponse(
                    accountPlan = accountPlan,
                    householdPlan = householdPlan,
                    deviceLimit = householdPlan.deviceLimit(),
                    registeredDevices = devices.count(householdId),
                    turnEnabled = householdPlan.turnEnabled() && turnConfigured,
                    bankReadingEnabled = householdPlan.bankReadingEnabled(),
                    householdLimit = accountPlan.householdLimit(),
                    canManageSpace = householdLookup.roleForUser(user.userId, householdId).canManageSpace()
                )
            }
            result.onSuccess { call.respond(it) }
                .onFailure { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted entitlements unavailable")) }
        }
    }
}
