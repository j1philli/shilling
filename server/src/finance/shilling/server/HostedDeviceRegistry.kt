package finance.shilling.server

import finance.shilling.core.auth.HostedPlan
import finance.shilling.core.auth.RegisteredDevice
import finance.shilling.core.auth.deviceLimit
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface HostedDeviceRegistry {
    suspend fun register(householdId: String, deviceId: String, userId: String, plan: HostedPlan): Boolean
    suspend fun count(householdId: String): Int
    suspend fun list(householdId: String): List<String>
    suspend fun listDetails(householdId: String): List<RegisteredDevice>
    suspend fun remove(householdId: String, deviceId: String)
}

class SupabaseHostedDeviceRegistry(
    supabaseUrl: String,
    private val serviceKey: String
) : HostedDeviceRegistry {
    private val baseUrl = supabaseUrl.trimEnd('/') + "/rest/v1"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    override suspend fun register(householdId: String, deviceId: String, userId: String, plan: HostedPlan): Boolean =
        withContext(Dispatchers.IO) {
            require(deviceId.isNotBlank() && deviceId.length <= 128)
            val body = json.encodeToString(RegisterDeviceRequest(householdId, deviceId, plan.deviceLimit(), userId))
            val request = authorized("$baseUrl/rpc/register_hosted_device")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "Hosted device registration failed: ${response.statusCode()}" }
            response.body().trim() == "true"
        }

    override suspend fun count(householdId: String): Int = list(householdId).size

    override suspend fun list(householdId: String): List<String> = listDetails(householdId).map { it.deviceId }

    override suspend fun listDetails(householdId: String): List<RegisteredDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<RegisteredDevice>()
        do {
            val offset = devices.size
            val request = authorized(
                "$baseUrl/hosted_devices?select=device_id,owner_user_id,registered_at,last_seen_at&household_id=eq.${encode(householdId)}&order=device_id.asc"
            ).header("Range", "$offset-${offset + 999}").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "Hosted device lookup failed: ${response.statusCode()}" }
            val page = json.decodeFromString<List<DeviceRow>>(response.body()).map {
                RegisteredDevice(it.deviceId, it.ownerUserId, it.registeredAt, it.lastSeenAt)
            }
            devices += page
        } while (page.size == 1000)
        devices
    }

    override suspend fun remove(householdId: String, deviceId: String) = withContext(Dispatchers.IO) {
        val request = authorized(
            "$baseUrl/hosted_devices?household_id=eq.${encode(householdId)}&device_id=eq.${encode(deviceId)}"
        ).DELETE().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Hosted device removal failed: ${response.statusCode()}" }
    }

    private fun authorized(url: String): HttpRequest.Builder = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(5))
        .header("apikey", serviceKey)
        .header("Authorization", "Bearer $serviceKey")
        .header("Accept", "application/json")
}

@Serializable
private data class RegisterDeviceRequest(
    @kotlinx.serialization.SerialName("p_household_id") val householdId: String,
    @kotlinx.serialization.SerialName("p_device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("p_device_limit") val deviceLimit: Int?,
    @kotlinx.serialization.SerialName("p_user_id") val userId: String
)

@Serializable
private data class DeviceRow(
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("owner_user_id") val ownerUserId: String?,
    @kotlinx.serialization.SerialName("registered_at") val registeredAt: String,
    @kotlinx.serialization.SerialName("last_seen_at") val lastSeenAt: String?
)

fun createHostedDeviceRegistry(authConfig: AuthConfig): HostedDeviceRegistry? {
    if (authConfig.authMode != "supabase") return null
    return SupabaseHostedDeviceRegistry(
        authConfig.supabaseUrl ?: return null,
        authConfig.supabaseServiceKey ?: return null
    )
}

fun Route.hostedDevicesRoute(
    tokenVerifier: SupabaseTokenVerifier?,
    householdLookup: HouseholdMembershipLookup?,
    devices: HostedDeviceRegistry?,
    hub: SignalingHub
) {
    route("/api/devices") {
        get("/details") {
            val user = tokenVerifier?.requireUser(call) ?: return@get
            val householdId = householdLookup?.householdIdForUser(user.userId)
            if (householdId == null || devices == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted devices unavailable"))
                return@get
            }
            runCatching { devices.listDetails(householdId) }
                .onSuccess { call.respond(it) }
                .onFailure { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted devices unavailable")) }
        }
        get {
            val user = tokenVerifier?.requireUser(call) ?: return@get
            val householdId = householdLookup?.householdIdForUser(user.userId)
            if (householdId == null || devices == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted devices unavailable"))
                return@get
            }
            runCatching { devices.list(householdId) }
                .onSuccess { call.respond(it) }
                .onFailure { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted devices unavailable")) }
        }
        delete("/{deviceId}") {
            val user = tokenVerifier?.requireUser(call) ?: return@delete
            val householdId = householdLookup?.householdIdForUser(user.userId)
            val deviceId = call.parameters["deviceId"]
            if (householdId == null || deviceId.isNullOrBlank() || devices == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }
            val canManage = runCatching { householdLookup.roleForUser(user.userId, householdId).canManageSpace() }
                .getOrElse {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted space permissions unavailable"))
                    return@delete
                }
            if (!canManage) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Owner or admin role required"))
                return@delete
            }
            runCatching {
                devices.remove(householdId, deviceId)
                hub.evict(householdId, deviceId)
            }
                .onSuccess { call.respond(HttpStatusCode.NoContent) }
                .onFailure { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Hosted devices unavailable")) }
        }
    }
}
