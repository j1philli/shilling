package finance.shilling.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    private val authConfig: AuthConfig
) : AccessTokenVerifier {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newHttpClient()
    private val cacheMutex = Mutex()
    private var jwksCache: CachedJwks? = null

    override suspend fun verifyUserToken(token: String): VerifiedSupabaseUser? {
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
            VerifiedSupabaseUser(
                userId = decoded.subject ?: return null,
                issuer = decoded.issuer ?: "",
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
            val key = currentJwks().keys.firstOrNull { it.kid == kid } ?: return null
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
            if (Instant.now().epochSecond >= exp) return null

            val sub = payload.jsonPrimitive("sub") ?: return null
            VerifiedSupabaseUser(
                userId = sub,
                issuer = issuer,
                role = payload.jsonPrimitive("role"),
                expiresAtEpochSeconds = exp
            )
        }.getOrElse { error ->
            authLog.w { "Token verification failed: ${error.message}" }
            null
        }
    }

    private suspend fun currentJwks(): JwksResponse {
        val cached = cacheMutex.withLock {
            jwksCache?.takeIf { !it.isExpired() }?.response
        }
        if (cached != null) return cached

        val supabaseUrl = authConfig.supabaseUrl ?: error("Supabase URL required for JWKS verification")
        val request = HttpRequest.newBuilder()
            .uri(URI.create(supabaseUrl.trimEnd('/') + "/auth/v1/.well-known/jwks.json"))
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
        if (response.statusCode() !in 200..299) {
            error("JWKS fetch failed with status ${response.statusCode()}")
        }

        val parsed = json.decodeFromString<JwksResponse>(response.body())
        cacheMutex.withLock {
            jwksCache = CachedJwks(parsed, Instant.now().plusSeconds(600))
        }
        return parsed
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
    val hasLegacySecret = authConfig.supabaseJwtSecret != null
    val hasModernConfig = authConfig.supabaseUrl != null
    if (!hasLegacySecret && !hasModernConfig) return null
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
) {
    fun isExpired(): Boolean = Instant.now() >= expiresAt
}
