package finance.shilling.server

import finance.shilling.core.auth.AuthMode
import finance.shilling.core.auth.ServerConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import co.touchlab.kermit.Logger
import kotlin.time.Duration.Companion.seconds

private val log = Logger.withTag("Server")

data class AuthConfig(
    val authMode: String,
    val supabaseUrl: String?,
    val supabaseAnonKey: String?,
    val supabaseServiceKey: String?,
    val supabaseJwtSecret: String?
)

fun readAuthConfig(): AuthConfig = AuthConfig(
    authMode = System.getenv("SHILLING_AUTH_MODE") ?: "none",
    supabaseUrl = System.getenv("SHILLING_SUPABASE_URL"),
    supabaseAnonKey = System.getenv("SHILLING_SUPABASE_ANON_KEY"),
    supabaseServiceKey = System.getenv("SHILLING_SUPABASE_SERVICE_KEY"),
    supabaseJwtSecret = System.getenv("SHILLING_SUPABASE_JWT_SECRET")
)

fun Routing.configRoute(authConfig: AuthConfig) {
    get("/api/config") {
        val response = if (authConfig.authMode == "supabase") {
            ServerConfig(
                authMode = AuthMode.SUPABASE,
                supabaseUrl = authConfig.supabaseUrl,
                supabaseAnonKey = authConfig.supabaseAnonKey
            )
        } else {
            ServerConfig(authMode = AuthMode.NONE)
        }
        call.respond(response)
    }
}

fun main() {
    val signalingHub = SignalingHub()
    val turnConfig = createTurnConfig()
    val authConfig = readAuthConfig()
    require(authConfig.authMode in setOf("none", "supabase")) { "Unsupported SHILLING_AUTH_MODE" }
    val householdLookup = createHouseholdMembershipLookup(authConfig)
    val tokenVerifier = createSupabaseTokenVerifier(authConfig)
    if (authConfig.authMode == "supabase") {
        require(tokenVerifier != null) { "Hosted auth requires SHILLING_SUPABASE_URL" }
        require(householdLookup != null) { "Hosted auth requires Supabase URL and server key" }
        require(!authConfig.supabaseAnonKey.isNullOrBlank()) { "Hosted auth requires a publishable key" }
    }

    log.i { "Shilling server starting on http://localhost:8081" }
    log.i { "Auth mode: ${authConfig.authMode}" }
    log.i { "Mode: signaling + discovery only (no user data stored or relayed)" }
    if (turnConfig.turnSecret != null) {
        log.i { "TURN: enabled (${turnConfig.turnUrls.size} server(s), TTL ${turnConfig.ttlSeconds}s)" }
    } else {
        log.i { "TURN: disabled (no SHILLING_TURN_SECRET set)" }
    }

    val authEnabled = authConfig.authMode == "supabase"

    embeddedServer(Netty, port = 8081, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 15.seconds
        }
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            exposeHeader("Cross-Origin-Resource-Policy")
        }
        // Required for browsers with Cross-Origin-Embedder-Policy: require-corp
        // (set by serve.json for SharedArrayBuffer support in wasmJs)
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.header("Cross-Origin-Resource-Policy", "cross-origin")
        }

        routing {
            get("/health") { call.respondText("ok") }
            configRoute(authConfig)
            iceServerRoute(turnConfig, authEnabled = authEnabled, tokenVerifier = tokenVerifier)
            if (authEnabled) {
                householdRoute(householdLookup, tokenVerifier)
            }
            signalingRoute(signalingHub, tokenVerifier, householdLookup)
        }
    }.start(wait = true)
}
