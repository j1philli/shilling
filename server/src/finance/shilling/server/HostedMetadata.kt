package finance.shilling.server

import finance.shilling.core.auth.HostedHouseholdResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

private val hostedMetadataLog = co.touchlab.kermit.Logger.withTag("HostedMetadata")

interface HouseholdMembershipLookup {
    suspend fun householdIdForUser(userId: String): String?
    suspend fun roleForUser(userId: String, householdId: String): HostedSpaceRole? = null
}

enum class HostedSpaceRole { OWNER, ADMIN, MEMBER }

internal fun HostedSpaceRole?.canManageSpace(): Boolean =
    this == HostedSpaceRole.OWNER || this == HostedSpaceRole.ADMIN

sealed class JoinAuthorizationResult {
    data class Authorized(val userId: String) : JoinAuthorizationResult()
    data class Rejected(val reason: String) : JoinAuthorizationResult()
}

class SupabaseHouseholdMembershipLookup(
    supabaseUrl: String,
    private val serviceKey: String,
    private val httpClient: HttpClient = hostedHttpClient,
    private val requestTimeout: Duration = hostedRequestTimeout
) : HouseholdMembershipLookup {
    private val profilesUrl = supabaseUrl.trimEnd('/') + "/rest/v1/user_profiles"
    private val membershipsUrl = supabaseUrl.trimEnd('/') + "/rest/v1/hosted_space_memberships"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun householdIdForUser(userId: String): String? {
        val encodedUser = URLEncoder.encode(userId, Charsets.UTF_8)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$profilesUrl?select=household_id&user_id=eq.$encodedUser&limit=1"))
            .timeout(requestTimeout)
            .header("apikey", serviceKey)
            .header("Authorization", "Bearer $serviceKey")
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = httpClient.sendHostedRequest(request, requestTimeout)
        if (response.statusCode() !in 200..299) {
            throw HostedUpstreamUnavailableException("Household lookup failed with status ${response.statusCode()}")
        }

        val rows = runCatching {
            json.decodeFromString<List<UserProfileRow>>(response.body())
        }.getOrElse { error ->
            throw HostedUpstreamUnavailableException("Invalid household lookup response", error)
        }

        val activeSpace = rows.firstOrNull()?.householdId ?: return null
        return if (roleForUser(userId, activeSpace) == null) null else activeSpace
    }

    override suspend fun roleForUser(userId: String, householdId: String): HostedSpaceRole? {
        val encodedUser = URLEncoder.encode(userId, Charsets.UTF_8)
        val encodedHousehold = URLEncoder.encode(householdId, Charsets.UTF_8)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$membershipsUrl?select=role&user_id=eq.$encodedUser&space_id=eq.$encodedHousehold&limit=1"))
            .timeout(requestTimeout)
            .header("apikey", serviceKey)
            .header("Authorization", "Bearer $serviceKey")
            .header("Accept", "application/json")
            .GET().build()
        val response = httpClient.sendHostedRequest(request, requestTimeout)
        if (response.statusCode() !in 200..299) {
            throw HostedUpstreamUnavailableException("Hosted space role lookup failed with status ${response.statusCode()}")
        }
        return runCatching { json.decodeFromString<List<SpaceRoleRow>>(response.body()) }
            .getOrElse { throw HostedUpstreamUnavailableException("Invalid hosted space role response", it) }
            .firstOrNull()?.role?.uppercase()
            ?.let { runCatching { HostedSpaceRole.valueOf(it) }.getOrNull() }
    }
}

fun createHouseholdMembershipLookup(authConfig: AuthConfig): HouseholdMembershipLookup? {
    if (authConfig.authMode != "supabase") return null
    val supabaseUrl = authConfig.supabaseUrl?.takeIf { it.isNotBlank() } ?: return null
    val serviceKey = authConfig.supabaseServiceKey?.takeIf { it.isNotBlank() } ?: return null
    return SupabaseHouseholdMembershipLookup(supabaseUrl, serviceKey)
}

suspend fun authorizeHostedJoin(
    tokenVerifier: AccessTokenVerifier,
    lookup: HouseholdMembershipLookup?,
    accessToken: String?,
    requestedHouseholdId: String
): JoinAuthorizationResult {
    if (accessToken == null) {
        return JoinAuthorizationResult.Rejected("Missing token")
    }

    val verified = tokenVerifier.verifyUserToken(accessToken)
        ?: return JoinAuthorizationResult.Rejected("Invalid token")
    val userId = verified.userId
    if (lookup == null) {
        return JoinAuthorizationResult.Rejected("Hosted household lookup unavailable")
    }

    val householdId = try {
        lookup.householdIdForUser(userId)
    } catch (_: HostedUpstreamUnavailableException) {
        return JoinAuthorizationResult.Rejected("Hosted household lookup unavailable")
    }
        ?: return JoinAuthorizationResult.Rejected("No hosted household")
    if (householdId != requestedHouseholdId) {
        return JoinAuthorizationResult.Rejected("Household mismatch")
    }

    return JoinAuthorizationResult.Authorized(userId)
}

fun Route.householdRoute(
    lookup: HouseholdMembershipLookup?,
    tokenVerifier: SupabaseTokenVerifier?
) {
    route("/api/household") {
        get {
            if (tokenVerifier == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted token verifier unavailable"))
                return@get
            }
            val user = tokenVerifier.requireUser(call) ?: return@get
            if (lookup == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted household lookup unavailable"))
                return@get
            }

            val householdId = try {
                lookup.householdIdForUser(user.userId)
            } catch (_: HostedUpstreamUnavailableException) {
                hostedMetadataLog.w { "Hosted household lookup unavailable" }
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted household lookup unavailable"))
                return@get
            }
            if (householdId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No hosted household"))
                return@get
            }

            call.respond(HostedHouseholdResponse(householdId))
        }
    }
}

@Serializable
private data class UserProfileRow(
    @SerialName("household_id")
    val householdId: String
)

@Serializable
private data class SpaceRoleRow(val role: String)
