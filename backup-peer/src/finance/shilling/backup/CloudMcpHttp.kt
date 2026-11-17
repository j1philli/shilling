package finance.shilling.backup

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import finance.shilling.core.backup.CloudMcpGrant
import finance.shilling.shared.data.store.backup.CloudMcpRepository
import finance.shilling.shared.data.store.backup.McpAccessDenied
import finance.shilling.shared.data.store.backup.sha256
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.Semaphore

/** Direct worker endpoint. The signaling service receives authorization metadata only. */
fun installCloudMcp(
    server: HttpServer,
    publicUrl: String,
    sessionKey: ByteArray,
    resolve: (String) -> Pair<String, CloudMcpRepository>?,
    authorize: suspend (household: String, tokenHash: String) -> CloudMcpGrant?
) {
    val uri = URI(publicUrl)
    require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")))
    require(uri.rawPath == "/mcp" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(sessionKey.size >= 32)
    fun signature(value: String, tokenHash: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(sessionKey, "HmacSHA256"))
        doFinal("shilling-mcp-session|$tokenHash|$value".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val origin = "${uri.scheme}://${uri.rawAuthority}"
    val slots = Semaphore(2) // Bounds plaintext views; no per-household background database/process.
    server.createContext("/mcp") { exchange ->
        try {
            if (exchange.requestURI.rawPath != "/mcp" || exchange.requestURI.rawQuery != null) { exchange.reply(404); return@createContext }
            if (exchange.requestHeaders.getFirst("Host") != uri.rawAuthority ||
                exchange.requestHeaders.getFirst("Origin")?.let { it != origin } == true) {
                exchange.reply(403); return@createContext
            }
            val credential = exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            if (credential == null || !credential.matches(Regex("[a-f0-9]{64}"))) {
                exchange.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"Shilling cloud MCP\"")
                exchange.reply(401); return@createContext
            }
            val hash = sha256(credential)
            val target = resolve(hash)
            if (target == null) { exchange.reply(403); return@createContext }
            if (exchange.requestMethod != "POST") {
                exchange.responseHeaders.set("Allow", "POST")
                exchange.reply(405); return@createContext
            }
            val version = exchange.requestHeaders.getFirst("MCP-Protocol-Version")
            if (version != null && version !in setOf("2025-06-18", "2025-03-26", "2024-11-05")) { exchange.reply(400); return@createContext }
            if (exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim() != "application/json") { exchange.reply(415); return@createContext }
            if (!slots.tryAcquire()) { exchange.responseHeaders.set("Retry-After", "2"); exchange.reply(429); return@createContext }
            try {
                val bytes = exchange.requestBody.readNBytes(1024 * 1024 + 1)
                if (bytes.size > 1024 * 1024) { exchange.reply(413); return@createContext }
                val body = try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
                val message = Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException()
                val initialize = message["method"]?.jsonPrimitive?.contentOrNull == "initialize"
                val session: String
                if (initialize) {
                    val value = "${System.currentTimeMillis()}.${UUID.randomUUID()}"
                    session = "$value.${signature(value, hash)}"
                } else {
                    session = exchange.requestHeaders.getFirst("Mcp-Session-Id") ?: run { exchange.reply(400); return@createContext }
                    val parts = session.split('.')
                    val created = parts.firstOrNull()?.toLongOrNull() ?: 0
                    if (parts.size != 3 || parts[1].length != 36 || created > System.currentTimeMillis() + 300_000 ||
                        created + 7L * 24 * 60 * 60 * 1000 <= System.currentTimeMillis() ||
                        !MessageDigest.isEqual(parts.last().toByteArray(), signature(session.substringBeforeLast('.'), hash).toByteArray())) {
                        exchange.reply(404); return@createContext
                    }
                }
                // MCP request ids are unique within a session. Signed, client-bound sessions remain
                // valid after restart; ordinary SDKs get safe retries without a custom header.
                val automaticKey = session.substringBefore('.') + "." + UUID.nameUUIDFromBytes("$session|${message["id"]}".toByteArray())
                val response = runBlocking {
                    withTimeout(60_000) {
                        target.second.handle(body, exchange.requestHeaders.getFirst("Idempotency-Key") ?: automaticKey) { authorize(target.first, hash) }
                    }
                }
                if (initialize && response != null) exchange.responseHeaders.set("Mcp-Session-Id", session)
                exchange.reply(if (response == null) 202 else 200, response)
            } finally { slots.release() }
        } catch (_: McpAccessDenied) { exchange.reply(403) }
        catch (_: IllegalArgumentException) { exchange.reply(400, "{\"error\":\"Invalid request, missing write idempotency key, or trusted checkpoint not ready\"}") }
        catch (e: Exception) {
            // Diagnostics contain types/locations only, never tokens, arguments, or budget contents.
            val location = e.stackTrace.firstOrNull { it.className.startsWith("finance.shilling") }
            System.err.println("Cloud MCP failed: ${e.javaClass.simpleName} at $location")
            exchange.reply(503, "{\"error\":\"Cloud MCP unavailable; retry writes with the same Idempotency-Key\"}")
        }
        finally { exchange.close() }
    }
}

private fun HttpExchange.reply(status: Int, body: String? = null) {
    responseHeaders.set("Cache-Control", "no-store")
    responseHeaders.set("X-Content-Type-Options", "nosniff")
    if (body == null) sendResponseHeaders(status, -1)
    else {
        responseHeaders.set("Content-Type", "application/json")
        val bytes = body.encodeToByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
