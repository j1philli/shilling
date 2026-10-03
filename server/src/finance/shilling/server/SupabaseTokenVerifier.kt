package finance.shilling.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.time.Clock
import java.time.Duration
import java.util.Base64

private val authLog = co.touchlab.kermit.Logger.withTag("SupabaseAuth")

data class VerifiedSupabaseUser(
    val userId: String,
    val issuer: String,
    val role: String?,
    val expiresAtEpochSeconds: Long
)

interface AccessTokenVerifier {
    suspend fun verifyUserToken(token: String): VerifiedSupabaseUser?
}

class SupabaseTokenVerifier(
    private val authConfig: AuthConfig,
    private val httpClient: HttpClient = hostedHttpClient,
    private val requestTimeout: Duration = hostedRequestTimeout,
    private val clock: Clock = Clock.systemUTC()
) : AccessTokenVerifier {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()
    private var jwksCache: CachedJwks? = null
    private var forcedRefreshAfter: Instant = Instant.EPOCH
    private var failedRefreshUntil: Instant = Instant.EPOCH

    override suspend fun verifyUserToken(token: String): VerifiedSupabaseUser? {
        if (token.length > 16_384) return null
        return if (authConfig.supabaseJwtSecret != null) {
            verifyLegacyHs256(token)
        } else {
            verifyAsymmetricJwt(token)
        }
    }

    suspend fun requireUser(call: ApplicationCall): VerifiedSupabaseUser? {
        val token = bearerToken(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing bearer token"))
            return null
        }
        val verified = verifyUserToken(token)
        if (verified == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
            return null
        }
        return verified
    }

    private fun verifyLegacyHs256(token: String): VerifiedSupabaseUser? {
        val jwtSecret = authConfig.supabaseJwtSecret ?: return null
        return runCatching {
            val decoded = JWT.require(Algorithm.HMAC256(jwtSecret))
                .withAudience("authenticated")
                .build()
                .verify(token)
            val expiresAt = decoded.expiresAtAsInstant?.epochSecond ?: return null
            val expectedIssuer = authConfig.supabaseUrl?.trimEnd('/')?.plus("/auth/v1") ?: return null
            if (decoded.issuer != expectedIssuer) return null
            if (decoded.getClaim("role").asString() != "authenticated") return null
            val userId = decoded.subject?.takeIf { it.isNotBlank() } ?: return null
            VerifiedSupabaseUser(
                userId = userId,
                issuer = expectedIssuer,
                role = decoded.getClaim("role").asString(),
                expiresAtEpochSeconds = expiresAt
            )
        }.getOrNull()
    }

    private suspend fun verifyAsymmetricJwt(token: String): VerifiedSupabaseUser? {
        return runCatching {
            val parts = token.split('.')
            if (parts.size != 3) return null

            val header = decodeJsonObject(parts[0]) ?: return null
            val payload = decodeJsonObject(parts[1]) ?: return null
            val kid = header.jsonPrimitive("kid") ?: return null
            val alg = header.jsonPrimitive("alg") ?: return null
            val key = currentJwks(kid).keys.firstOrNull { it.kid == kid } ?: return null
            val publicKey = buildPublicKey(key) ?: return null

            val verifierName = signatureAlgorithmName(alg, key.kty) ?: return null
            val verified = Signature.getInstance(verifierName).run {
                initVerify(publicKey)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
                verify(Base64.getUrlDecoder().decode(parts[2]))
            }
            if (!verified) return null

            val issuer = payload.jsonPrimitive("iss") ?: return null
            val expectedIssuer = authConfig.supabaseUrl?.trimEnd('/') + "/auth/v1"
            if (issuer != expectedIssuer) return null

            val exp = payload.jsonLong("exp") ?: return null
            if (clock.instant().epochSecond >= exp) return null
            if (payload.jsonPrimitive("role") != "authenticated") return null
            val audience = payload["aud"]
            if (audience !is JsonPrimitive || audience.content != "authenticated") return null

            val sub = payload.jsonPrimitive("sub")?.takeIf { it.isNotBlank() } ?: return null
            VerifiedSupabaseUser(
                userId = sub,
                issuer = issuer,
                role = payload.jsonPrimitive("role"),
                expiresAtEpochSeconds = exp
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (error !is HostedUpstreamUnavailableException) {
                authLog.w { "Token verification failed: ${error::class.simpleName}" }
            }
            null
        }
    }

    // Hold the suspending mutex through refresh: cold/expired concurrent misses
    // share one fetch. A short failure backoff also coalesces failed refreshes.
    private suspend fun currentJwks(kid: String): JwksResponse = cacheMutex.withLock {
        val now = clock.instant()
        val cached = jwksCache?.takeIf { now < it.expiresAt }
        if (cached != null) {
            if (cached.response.keys.any { it.kid == kid } || now < forcedRefreshAfter) {
                return@withLock cached.response
            }
            // Permit key rotation before the 10-minute TTL, while limiting
            // attacker-chosen unknown key IDs to one forced refresh per 30s.
            forcedRefreshAfter = now.plusSeconds(30)
        }
        if (now < failedRefreshUntil) {
            throw HostedUpstreamUnavailableException("JWKS refresh temporarily unavailable")
        }
        val supabaseUrl = authConfig.supabaseUrl ?: error("Supabase URL required for JWKS verification")
        val request = HttpRequest.newBuilder()
            .uri(URI.create(supabaseUrl.trimEnd('/') + "/auth/v1/.well-known/jwks.json"))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .GET()
            .build()

        try {
            val response = httpClient.sendHostedRequest(request, requestTimeout)
            if (response.statusCode() !in 200..299) {
                throw HostedUpstreamUnavailableException("JWKS fetch failed with status ${response.statusCode()}")
            }
            val parsed = json.decodeFromString<JwksResponse>(response.body())
            val fetchedAt = clock.instant()
            jwksCache = CachedJwks(parsed, fetchedAt.plusSeconds(600))
            if (parsed.keys.none { it.kid == kid }) forcedRefreshAfter = fetchedAt.plusSeconds(30)
            failedRefreshUntil = Instant.EPOCH
            parsed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failedRefreshUntil = clock.instant().plusSeconds(1)
            authLog.w { "JWKS refresh unavailable: ${failure::class.simpleName}" }
            throw HostedUpstreamUnavailableException("JWKS refresh unavailable", failure)
        }
    }

    private fun buildPublicKey(key: JwkKey): PublicKey? =
        when (key.kty) {
            "EC" -> buildEcPublicKey(key)
            "RSA" -> buildRsaPublicKey(key)
            else -> null
        }

    private fun buildEcPublicKey(key: JwkKey): PublicKey? {
        val curve = when (key.crv) {
            "P-256" -> "secp256r1"
            "P-384" -> "secp384r1"
            "P-521" -> "secp521r1"
            else -> return null
        }
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(curve))
        }.getParameterSpec(ECParameterSpec::class.java)
        val x = BigInteger(1, Base64.getUrlDecoder().decode(key.x ?: return null))
        val y = BigInteger(1, Base64.getUrlDecoder().decode(key.y ?: return null))
        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun buildRsaPublicKey(key: JwkKey): PublicKey? {
        val modulus = BigInteger(1, Base64.getUrlDecoder().decode(key.n ?: return null))
        val exponent = BigInteger(1, Base64.getUrlDecoder().decode(key.e ?: return null))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
    }

    private fun signatureAlgorithmName(alg: String, keyType: String): String? =
        when {
            alg == "ES256" && keyType == "EC" -> "SHA256withECDSAinP1363Format"
            alg == "ES384" && keyType == "EC" -> "SHA384withECDSAinP1363Format"
            alg == "ES512" && keyType == "EC" -> "SHA512withECDSAinP1363Format"
            alg == "RS256" && keyType == "RSA" -> "SHA256withRSA"
            alg == "RS384" && keyType == "RSA" -> "SHA384withRSA"
            alg == "RS512" && keyType == "RSA" -> "SHA512withRSA"
            else -> null
        }

    private fun bearerToken(call: ApplicationCall): String? {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return null
        val prefix = "Bearer "
        return if (header.startsWith(prefix)) header.removePrefix(prefix).trim().takeIf { it.isNotBlank() } else null
    }

    private fun decodeJsonObject(base64Url: String): JsonObject? {
        val decoded = runCatching { Base64.getUrlDecoder().decode(base64Url) }.getOrNull() ?: return null
        val parsed = runCatching {
            json.parseToJsonElement(decoded.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return parsed as? JsonObject
    }

    private fun JsonObject.jsonPrimitive(name: String): String? =
        (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.jsonLong(name: String): Long? =
        (this[name] as? JsonPrimitive)?.content?.toLongOrNull()
}

fun createSupabaseTokenVerifier(authConfig: AuthConfig): SupabaseTokenVerifier? {
    if (authConfig.authMode != "supabase") return null
    if (authConfig.supabaseUrl.isNullOrBlank()) return null
    return SupabaseTokenVerifier(authConfig)
}

@Serializable
private data class JwksResponse(
    val keys: List<JwkKey>
)

@Serializable
private data class JwkKey(
    val kid: String,
    val alg: String? = null,
    val kty: String,
    val crv: String? = null,
    val x: String? = null,
    val y: String? = null,
    val n: String? = null,
    val e: String? = null,
    @SerialName("key_ops")
    val keyOps: List<String> = emptyList()
)

private data class CachedJwks(
    val response: JwksResponse,
    val expiresAt: Instant
)
