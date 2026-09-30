package finance.shilling.server

import finance.shilling.core.auth.turnEnabled
import finance.shilling.core.sync.IceServerConfig
import finance.shilling.core.sync.IceServersResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TurnConfig(
    val stunUrls: List<String>,
    val turnUrls: List<String>,
    val turnSecret: String?,
    val ttlSeconds: Int,
    val cloudflareKeyId: String? = null,
    val cloudflareApiToken: String? = null
) {
    val cloudflareEnabled: Boolean get() = cloudflareKeyId != null && cloudflareApiToken != null
}

fun createTurnConfig(): TurnConfig {
    val stunRaw = System.getenv("SHILLING_STUN_URLS") ?: "stun:stun.cloudflare.com:3478"
    val turnRaw = System.getenv("SHILLING_TURN_URLS") ?: ""
    val secret = System.getenv("SHILLING_TURN_SECRET")?.takeIf { it.isNotBlank() }
    val cloudflareKeyId = System.getenv("SHILLING_CLOUDFLARE_TURN_KEY_ID")?.takeIf { it.isNotBlank() }
    val cloudflareApiToken = System.getenv("SHILLING_CLOUDFLARE_TURN_API_TOKEN")?.takeIf { it.isNotBlank() }
    val ttl = System.getenv("SHILLING_TURN_TTL")?.toIntOrNull() ?: 86400

    require(ttl in 1..172800) { "SHILLING_TURN_TTL must be between 1 and 172800 seconds" }
    require((cloudflareKeyId == null) == (cloudflareApiToken == null)) {
        "Both Cloudflare TURN key ID and API token must be configured"
    }
    require(cloudflareKeyId == null || (secret == null && turnRaw.isBlank())) {
        "Cloudflare TURN and coturn settings cannot be combined"
    }

    return TurnConfig(
        stunUrls = stunRaw.split(",").map { it.trim() }.filter { it.isNotBlank() },
        turnUrls = turnRaw.split(",").map { it.trim() }.filter { it.isNotBlank() },
        turnSecret = secret,
        ttlSeconds = ttl,
        cloudflareKeyId = cloudflareKeyId,
        cloudflareApiToken = cloudflareApiToken
    )
}

fun generateTurnCredentials(secret: String, ttlSeconds: Int): Pair<String, String> {
    val expiry = System.currentTimeMillis() / 1000 + ttlSeconds
    val username = "$expiry:shilling"
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    val credential = Base64.getEncoder().encodeToString(
        mac.doFinal(username.toByteArray(Charsets.UTF_8))
    )
    return username to credential
}

interface CloudflareTurnProvider {
    suspend fun generateIceServers(keyId: String, apiToken: String, ttlSeconds: Int): List<IceServerConfig>
}

class CloudflareTurnClient : CloudflareTurnProvider {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    override suspend fun generateIceServers(
        keyId: String,
        apiToken: String,
        ttlSeconds: Int
    ): List<IceServerConfig> = withContext(Dispatchers.IO) {
        require(keyId.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid Cloudflare TURN key ID" }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://rtc.live.cloudflare.com/v1/turn/keys/$keyId/credentials/generate-ice-servers"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer $apiToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"ttl":$ttlSeconds}"""))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 201) {
            throw IllegalStateException("Cloudflare TURN credential request failed: HTTP ${response.statusCode()}")
        }
        parseCloudflareIceServers(response.body())
    }
}

@Serializable
private data class CloudflareIceResponse(val iceServers: List<IceServerConfig>)

private val cloudflareJson = Json { ignoreUnknownKeys = true }

internal fun parseCloudflareIceServers(body: String): List<IceServerConfig> {
    val servers = cloudflareJson.decodeFromString<CloudflareIceResponse>(body).iceServers
    require(servers.any { server ->
        server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } &&
            !server.username.isNullOrBlank() && !server.credential.isNullOrBlank()
    }) { "Cloudflare TURN response did not contain usable credentials" }
    return servers
}

internal suspend fun iceServersForRequest(
    turnConfig: TurnConfig,
    includeTurn: Boolean,
    cloudflareProvider: CloudflareTurnProvider
): IceServersResponse {
    val servers = mutableListOf<IceServerConfig>()
    if (turnConfig.stunUrls.isNotEmpty()) servers.add(IceServerConfig(urls = turnConfig.stunUrls))
    if (includeTurn) {
        when {
            turnConfig.cloudflareEnabled -> servers.addAll(cloudflareProvider.generateIceServers(
                turnConfig.cloudflareKeyId!!, turnConfig.cloudflareApiToken!!, turnConfig.ttlSeconds
            ))
            turnConfig.turnUrls.isNotEmpty() && turnConfig.turnSecret != null -> {
                val (username, credential) = generateTurnCredentials(turnConfig.turnSecret, turnConfig.ttlSeconds)
                servers.add(IceServerConfig(
                    urls = turnConfig.turnUrls,
                    username = username,
                    credential = credential,
                    credentialType = "password"
                ))
            }
        }
    }
    return IceServersResponse(iceServers = servers, ttlSeconds = turnConfig.ttlSeconds)
}

fun Routing.iceServerRoute(
    turnConfig: TurnConfig,
    authEnabled: Boolean = false,
    tokenVerifier: SupabaseTokenVerifier? = null,
    householdLookup: HouseholdMembershipLookup? = null,
    entitlements: HostedEntitlementLookup? = null,
    cloudflareProvider: CloudflareTurnProvider = CloudflareTurnClient()
) {
    get("/api/ice-servers") {
        // Hosted TURN is a household entitlement; authentication alone does not grant it.
        val relayRequested = call.request.queryParameters["allowRelay"] == "true"
        val includeTurn = if (!relayRequested) {
            false
        } else if (!authEnabled) {
            true
        } else {
            val user = tokenVerifier?.verifyUserToken(
                call.request.headers[HttpHeaders.Authorization]
                    ?.removePrefix("Bearer ")
                    ?.trim()
                    .orEmpty()
            )
            val householdId = user?.userId?.let { householdLookup?.householdIdForUser(it) }
            if (householdId == null) false else runCatching {
                entitlements?.householdPlan(householdId)?.turnEnabled() == true
            }.getOrDefault(false)
        }

        val response = try {
            iceServersForRequest(turnConfig, includeTurn, cloudflareProvider)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "TURN credentials unavailable"))
            return@get
        }
        call.respond(response)
    }
}
