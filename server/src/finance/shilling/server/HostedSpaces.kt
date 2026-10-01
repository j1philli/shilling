package finance.shilling.server

import finance.shilling.core.auth.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

internal val spaceActions = setOf("list", "create", "select", "invite", "accept", "decline", "revoke_invitation", "role", "remove", "leave")
internal fun invitationHash(code: String): String = MessageDigest.getInstance("SHA-256")
    .digest(code.toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

class SpaceCommandFailure(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

class SupabaseSpaceManagement(private val baseUrl: String, private val serviceKey: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(actor: String, command: HostedSpaceCommand, membershipLimit: Int?): HostedSpacesResponse = withContext(Dispatchers.IO) {
        require(command.action in spaceActions) { "Unsupported space action" }
        val code = if (command.action == "invite") ByteArray(32).let {
            SecureRandom().nextBytes(it); Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        } else command.invitationCode
        if (command.action in setOf("accept", "decline") && (code == null || code.length !in 32..128)) {
            throw SpaceCommandFailure(HttpStatusCode.BadRequest, "Enter a valid invitation code")
        }
        val body = buildJsonObject {
            put("p_actor", actor); put("p_action", command.action)
            put("p_space", command.spaceId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_name", command.name?.let(::JsonPrimitive) ?: JsonNull)
            put("p_kind", command.kind?.let(::JsonPrimitive) ?: JsonNull)
            put("p_email", command.email?.let(::JsonPrimitive) ?: JsonNull)
            put("p_role", command.role?.let(::JsonPrimitive) ?: JsonNull)
            put("p_target", command.userId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_invitation", command.invitationId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_token_hash", code?.let { JsonPrimitive(invitationHash(it)) } ?: JsonNull)
            put("p_membership_limit", membershipLimit?.let(::JsonPrimitive) ?: JsonNull)
        }
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/rest/v1/rpc/manage_hosted_space"))
            .timeout(Duration.ofSeconds(8)).header("apikey", serviceKey)
            .header("Authorization", "Bearer $serviceKey").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val error = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
            val sqlCode = error?.get("code")?.jsonPrimitive?.content
            val status = when (sqlCode) {
                "42501" -> HttpStatusCode.Forbidden
                "23514" -> HttpStatusCode.Conflict
                "22023", "22P02" -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.ServiceUnavailable
            }
            val message = if (sqlCode in setOf("42501", "23514", "22023"))
                error?.get("message")?.jsonPrimitive?.content ?: "Space action unavailable"
            else "Space action unavailable. Retry when the hosted service is reachable."
            throw SpaceCommandFailure(status, message)
        }
        json.decodeFromString<HostedSpacesResponse>(response.body()).copy(invitationCode = if (command.action == "invite") code else null)
    }
}

fun Route.hostedSpacesRoute(
    tokenVerifier: SupabaseTokenVerifier?, management: SupabaseSpaceManagement?,
    entitlements: HostedEntitlementLookup?, devices: HostedDeviceRegistry?, hub: SignalingHub
) {
    route("/api/spaces") {
        get {
            val user = tokenVerifier?.requireUser(call) ?: return@get
            if (management == null) { call.respond(HttpStatusCode.ServiceUnavailable); return@get }
            runCatching { management.execute(user.userId, HostedSpaceCommand("list"), (entitlements?.accountPlan(user.userId) ?: HostedPlan.FREE).householdLimit()) }
                .onSuccess { call.respond(it) }
                .onFailure { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Space list unavailable")) }
        }
        post {
            val user = tokenVerifier?.requireUser(call) ?: return@post
            if (management == null) { call.respond(HttpStatusCode.ServiceUnavailable); return@post }
            val command = runCatching { call.receive<HostedSpaceCommand>() }.getOrNull()
            if (command == null || command.action !in spaceActions) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported space action")); return@post
            }
            try {
                val plan = entitlements?.accountPlan(user.userId) ?: HostedPlan.FREE
                // Snapshot only device metadata, so a departing member's live sessions can be closed.
                val response = hub.withMembershipPolicy {
                    val previousSpaces = if (command.action == "select" && plan != HostedPlan.GOLD) management.execute(user.userId, HostedSpaceCommand("list"), plan.householdLimit()).spaces else emptyList()
                    val previousDevices = previousSpaces.flatMap { space -> devices?.listDetails(space.id).orEmpty().filter { it.ownerUserId == user.userId }.map { space.id to it.deviceId } }
                    val spaceId = command.spaceId ?: if (command.action in setOf("remove", "leave")) management.execute(user.userId, HostedSpaceCommand("list"), plan.householdLimit()).activeSpaceId else null
                    val removing = command.action in setOf("remove", "leave")
                    val registrations = if (removing && spaceId != null) devices?.listDetails(spaceId).orEmpty() else emptyList()
                    val response = management.execute(user.userId, command.copy(spaceId = spaceId), plan.householdLimit())
                    if (removing && spaceId != null) {
                        val target = if (command.action == "leave") user.userId else command.userId
                        registrations.filter { it.ownerUserId == target }.forEach { hub.evict(spaceId, it.deviceId) }
                    }
                    previousDevices.filter { it.first != response.activeSpaceId }.forEach { (space, device) -> hub.evict(space, device) }
                    response
                }
                call.respond(response)
            } catch (error: SpaceCommandFailure) {
                call.respond(error.status, mapOf("error" to error.message))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Space action unavailable"))
            }
        }
    }
}
