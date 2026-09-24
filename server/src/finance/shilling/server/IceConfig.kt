package finance.shilling.server

import finance.shilling.core.sync.IceServerConfig
import finance.shilling.core.sync.IceServersResponse
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TurnConfig(
    val stunUrls: List<String>,
    val turnUrls: List<String>,
    val turnSecret: String?,
    val ttlSeconds: Int
)

fun createTurnConfig(): TurnConfig {
    val stunRaw = System.getenv("SHILLING_STUN_URLS") ?: "stun:stun.l.google.com:19302"
    val turnRaw = System.getenv("SHILLING_TURN_URLS") ?: ""
    val secret = System.getenv("SHILLING_TURN_SECRET")?.takeIf { it.isNotBlank() }
    val ttl = System.getenv("SHILLING_TURN_TTL")?.toIntOrNull() ?: 86400

    return TurnConfig(
        stunUrls = stunRaw.split(",").map { it.trim() }.filter { it.isNotBlank() },
        turnUrls = turnRaw.split(",").map { it.trim() }.filter { it.isNotBlank() },
        turnSecret = secret,
        ttlSeconds = ttl
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

fun Routing.iceServerRoute(
    turnConfig: TurnConfig,
    authEnabled: Boolean = false,
    tokenVerifier: SupabaseTokenVerifier? = null
) {
    get("/api/ice-servers") {
        val servers = mutableListOf<IceServerConfig>()

        // STUN always included for all users
        if (turnConfig.stunUrls.isNotEmpty()) {
            servers.add(IceServerConfig(urls = turnConfig.stunUrls))
        }

        // TURN only for authenticated users (when auth enabled) or always (self-hosted)
        val includeTurn = if (!authEnabled) {
            true
        } else {
            tokenVerifier?.verifyUserToken(
                call.request.headers[io.ktor.http.HttpHeaders.Authorization]
                    ?.removePrefix("Bearer ")
                    ?.trim()
                    .orEmpty()
            ) != null
        }

        if (includeTurn && turnConfig.turnUrls.isNotEmpty() && turnConfig.turnSecret != null) {
            val (username, credential) = generateTurnCredentials(
                turnConfig.turnSecret, turnConfig.ttlSeconds
            )
            servers.add(IceServerConfig(
                urls = turnConfig.turnUrls,
                username = username,
                credential = credential,
                credentialType = "password"
            ))
        }

        call.respond(IceServersResponse(
            iceServers = servers,
            ttlSeconds = turnConfig.ttlSeconds
        ))
    }
}
