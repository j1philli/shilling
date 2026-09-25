package finance.shilling.server

import finance.shilling.core.auth.HostedHouseholdResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val hostedMetadataLog = co.touchlab.kermit.Logger.withTag("HostedMetadata")

interface HouseholdMembershipLookup {
    suspend fun householdIdForUser(userId: String): String?
}

sealed class JoinAuthorizationResult {
    data class Authorized(val userId: String) : JoinAuthorizationResult()
    data class Rejected(val reason: String) : JoinAuthorizationResult()
}

class SupabaseHouseholdMembershipLookup(
    supabaseUrl: String,
    private val serviceKey: String
) : HouseholdMembershipLookup {
    private val httpClient = HttpClient.newHttpClient()
    private val profilesUrl = supabaseUrl.trimEnd('/') + "/rest/v1/user_profiles"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun householdIdForUser(userId: String): String? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$profilesUrl?select=household_id&user_id=eq.$userId"))
            .header("apikey", serviceKey)
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            hostedMetadataLog.w { "Supabase household lookup failed for user=$userId status=${response.statusCode()}" }
            return@withContext null
        }

        val rows = runCatching {
            json.decodeFromString<List<UserProfileRow>>(response.body())
        }.getOrElse { error ->
            hostedMetadataLog.w { "Failed to decode Supabase household lookup response: ${error.message}" }
            return@withContext null
        }

        rows.firstOrNull()?.householdId
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

    val householdId = lookup.householdIdForUser(userId)
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

            val householdId = lookup.householdIdForUser(user.userId)
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
