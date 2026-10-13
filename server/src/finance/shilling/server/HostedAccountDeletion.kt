package finance.shilling.server

import co.touchlab.kermit.Logger
import finance.shilling.core.auth.HostedAccountDeletionConflict
import finance.shilling.core.auth.HostedAccountDeletionRequest
import finance.shilling.core.auth.HostedPlan
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val deletionLog = Logger.withTag("AccountDeletion")

/** Server-owned account metadata. Application data never reaches the hosted server. */
interface HostedAccountStore {
    suspend fun spaceIds(userId: String): List<String>
    /** Removes memberships, owned devices, invitations, and the profile. Idempotent. */
    suspend fun deleteMetadata(userId: String)
    /** Deletes the Auth user; an already-deleted user counts as success. */
    suspend fun deleteAuthUser(userId: String)
}

class SupabaseHostedAccountStore(supabaseUrl: String, private val serviceKey: String) : HostedAccountStore {
    private val baseUrl = supabaseUrl.trimEnd('/')
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun spaceIds(userId: String): List<String> = withContext(Dispatchers.IO) {
        val response = client.send(
            authorized("$baseUrl/rest/v1/hosted_space_memberships?select=space_id&user_id=eq.${encode(userId)}").GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(response.statusCode() in 200..299) { "Membership lookup failed: ${response.statusCode()}" }
        json.decodeFromString<List<MembershipRow>>(response.body()).map { it.spaceId }
    }

    override suspend fun deleteMetadata(userId: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("p_actor", userId) }.toString()
        val response = client.send(
            authorized("$baseUrl/rest/v1/rpc/delete_hosted_account")
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        // 42501: the Auth user is already gone, so a retry has nothing left to remove.
        if (response.statusCode() !in 200..299 && "42501" !in response.body()) {
            error("Account metadata deletion failed: ${response.statusCode()}")
        }
    }

    override suspend fun deleteAuthUser(userId: String) = withContext(Dispatchers.IO) {
        val response = client.send(
            authorized("$baseUrl/auth/v1/admin/users/${encode(userId)}").DELETE().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(response.statusCode() in 200..299 || response.statusCode() == 404) {
            "Auth user deletion failed: ${response.statusCode()}"
        }
    }

    private fun authorized(url: String): HttpRequest.Builder = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(5))
        .header("apikey", serviceKey)
        .header("Authorization", "Bearer $serviceKey")
        .header("Accept", "application/json")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

fun createHostedAccountStore(authConfig: AuthConfig): HostedAccountStore? {
    if (authConfig.authMode != "supabase") return null
    return SupabaseHostedAccountStore(
        authConfig.supabaseUrl ?: return null,
        authConfig.supabaseServiceKey ?: return null
    )
}

sealed interface HostedAccountDeletionResult {
    data object Deleted : HostedAccountDeletionResult
    data class SubscriptionActive(val plan: HostedPlan) : HostedAccountDeletionResult
}

/**
 * Deleting an account does not cancel a store subscription; the purchaser must cancel it with
 * Apple, Google, or the web billing provider. Require an explicit acknowledgement instead of
 * blocking, so deletion is always possible in-app.
 */
suspend fun deleteHostedAccount(
    userId: String,
    acknowledgeActiveSubscription: Boolean,
    entitlements: HostedEntitlementLookup,
    accounts: HostedAccountStore,
    devices: HostedDeviceRegistry?,
    hub: SignalingHub
): HostedAccountDeletionResult {
    entitlements.invalidate(userId)
    val plan = entitlements.accountPlan(userId)
    if (plan > HostedPlan.FREE && !acknowledgeActiveSubscription) {
        return HostedAccountDeletionResult.SubscriptionActive(plan)
    }
    hub.withMembershipPolicy {
        // Snapshot this account's devices so their signaling sessions close with the account.
        val owned = accounts.spaceIds(userId).flatMap { space ->
            devices?.listDetails(space).orEmpty().filter { it.ownerUserId == userId }.map { space to it.deviceId }
        }
        accounts.deleteMetadata(userId)
        owned.forEach { (space, device) -> hub.evict(space, device) }
    }
    accounts.deleteAuthUser(userId)
    try {
        entitlements.deleteCustomer(userId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // The hosted account is already gone; log so the billing record can be removed manually.
        deletionLog.w { "operation=delete_billing_customer result=failed type=${error::class.simpleName}" }
    }
    return HostedAccountDeletionResult.Deleted
}

fun Route.accountDeletionRoute(
    tokenVerifier: SupabaseTokenVerifier?,
    entitlements: HostedEntitlementLookup?,
    accounts: HostedAccountStore?,
    devices: HostedDeviceRegistry?,
    hub: SignalingHub
) {
    post("/api/account/delete") {
        val user = tokenVerifier?.requireUser(call) ?: return@post
        if (entitlements == null || accounts == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Account deletion unavailable"))
            return@post
        }
        val request = runCatching { call.receive<HostedAccountDeletionRequest>() }
            .getOrDefault(HostedAccountDeletionRequest())
        try {
            when (val result = deleteHostedAccount(
                user.userId, request.acknowledgeActiveSubscription, entitlements, accounts, devices, hub
            )) {
                HostedAccountDeletionResult.Deleted -> {
                    deletionLog.i { "operation=delete_account result=deleted" }
                    call.respond(HttpStatusCode.NoContent)
                }
                is HostedAccountDeletionResult.SubscriptionActive -> call.respond(
                    HttpStatusCode.Conflict,
                    HostedAccountDeletionConflict("Cancel or acknowledge your subscription first", result.plan)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            deletionLog.w { "operation=delete_account result=failed type=${error::class.simpleName}" }
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Account deletion unavailable. Try again when you're online."))
        }
    }
}

@Serializable
private data class MembershipRow(@kotlinx.serialization.SerialName("space_id") val spaceId: String)
