package finance.shilling.server

import finance.shilling.core.backup.*
import finance.shilling.core.auth.turnEnabled
import finance.shilling.core.sync.SignalingMessage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import java.nio.file.Path
import finance.shilling.shared.data.store.backup.BackupDatabase
import finance.shilling.shared.data.store.backup.CloudPolicyRepository
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupControl(
    val policies: BackupPolicies,
    private val workers: Map<String, String>,
    val turnConfigured: Boolean
) {
    fun workerForToken(token: String?): String? = workers.entries.firstOrNull {
        token != null && MessageDigest.isEqual(it.value.toByteArray(), token.toByteArray())
    }?.key
    suspend fun <T> query(block: BackupPolicies.() -> T): T = withContext(Dispatchers.IO) { policies.block() }
    val hasWorkers: Boolean get() = workers.isNotEmpty()
}

fun createBackupControl(turn: TurnConfig): BackupControl {
    // Separate credentials per worker; never use a user access token as a worker credential.
    val workers = System.getenv("SHILLING_BACKUP_WORKERS").orEmpty().split(',').filter { it.isNotBlank() }
        .associate { entry ->
            val parts = entry.split('=', limit = 2)
            require(parts.size == 2 && parts[0].matches(Regex("[a-zA-Z0-9-]{1,64}")) && parts[1].length >= 32) {
                "SHILLING_BACKUP_WORKERS must contain worker-id=secret entries with secrets of at least 32 characters"
            }
            parts[0] to parts[1]
        }
    // An unconfigured optional worker must not prevent existing hosted servers starting.
    if (workers.isEmpty()) return BackupControl(object : BackupPolicies {
        override fun get(household: String, available: Boolean) = BackupPolicy()
        override fun update(household: String, request: BackupPolicyUpdate, available: Boolean, actor: String?): BackupPolicy =
            throw IllegalArgumentException("Cloud backup is not configured")
        override fun assignments(workerId: String) = emptyList<BackupAssignment>()
        override fun assigned(householdId: String, workerId: String) = false
    }, emptyMap(), false)
    val trusted = System.getenv("SHILLING_BACKUP_TRUSTED_CLOUD_ENABLED") == "true"
    val capacity = System.getenv("SHILLING_BACKUP_WORKER_CAPACITY")?.toInt() ?: 256
    val policies: BackupPolicies = if (System.getenv("SHILLING_AUTH_MODE") == "supabase" || System.getenv("SHILLING_CLOUD_CONTROL_DATABASE_URL") != null) {
        val database = BackupDatabase.supabase(
            requireNotNull(System.getenv("SHILLING_CLOUD_CONTROL_DATABASE_URL")),
            requireNotNull(System.getenv("SHILLING_CLOUD_CONTROL_DATABASE_USER")),
            requireNotNull(System.getenv("SHILLING_CLOUD_CONTROL_DATABASE_PASSWORD")), 4)
        val repository = CloudPolicyRepository(database, workers.keys.toList(), trusted, capacity)
        repository.checkReady()
        object : BackupPolicies {
            override fun get(household: String, available: Boolean) = repository.get(household, available)
            override fun update(household: String, request: BackupPolicyUpdate, available: Boolean, actor: String?) = repository.update(household, request, available, actor)
            override fun assignments(workerId: String) = repository.assignments(workerId)
            override fun assigned(householdId: String, workerId: String) = repository.assigned(householdId, workerId)
            override fun close() = repository.close()
        }
    } else BackupPolicyStore(Path.of(System.getenv("SHILLING_BACKUP_POLICY_FILE") ?: "data/backup-policy.json"), workers.keys.toList(), trusted, capacity)
    return BackupControl(policies, workers,
        turn.turnSecret != null && turn.turnUrls.any { it.startsWith("turn:") || it.startsWith("turns:") } && turn.ttlSeconds >= 60)

}

fun Routing.backupRoutes(control: BackupControl, verifier: SupabaseTokenVerifier?, lookup: HouseholdMembershipLookup?, entitlements: HostedEntitlementLookup? = null) {
    suspend fun ApplicationCall.household(): Triple<String, Boolean, String>? {
        if (verifier == null && lookup == null) {
            val household = request.headers["X-Shilling-Household"]
            if (household == null || !validBackupId(household)) { respond(HttpStatusCode.BadRequest); return null }
            return Triple(household, true, "self-hosted-operator")
        }
        if (verifier == null || lookup == null) { respond(HttpStatusCode.ServiceUnavailable); return null }
        val user = verifier.requireUser(this) ?: return null
        val household = lookup.householdIdForUser(user.userId)
        if (household == null) {
            respond(HttpStatusCode.Forbidden)
            return null
        }
        return Triple(household, entitlements?.householdPlan(household)?.turnEnabled() == true, user.userId)
    }
    get("/api/backup/policy") {
        val (household, paid) = call.household() ?: return@get
        call.respond(control.query { get(household, paid && control.turnConfigured && control.hasWorkers) })
    }
    post("/api/backup/toggle") {
        val (household, paid, actor) = call.household() ?: return@post
        val update = call.receive<BackupPolicyUpdate>()
        try {
            call.respond(control.query { update(household, update, paid && control.turnConfigured && control.hasWorkers, actor) })
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Invalid settings")))
        }
    }
    get("/api/backup/assignments") {
        val worker = control.workerForToken(call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "))
        if (worker == null) { call.respond(HttpStatusCode.Unauthorized); return@get }
        call.respond(control.query { assignments(worker) })
    }
    // Authorization metadata only. MCP requests and budget results go directly to the worker.
    post("/api/backup/mcp/authorize") {
        val worker = control.workerForToken(call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "))
        if (worker == null) { call.respond(HttpStatusCode.Unauthorized); return@post }
        val request = call.receive<CloudMcpAuthorizationRequest>()
        val grant = control.query {
            val policy = get(request.householdId, false)
            if (!policy.enabled || policy.workerId != worker || policy.encryption != BackupEncryption.TRUSTED_CLOUD) null
            else policy.mcpClients.singleOrNull {
                it.expiresAt > System.currentTimeMillis() && it.consentBy != null &&
                    MessageDigest.isEqual(it.tokenSha256.toByteArray(), request.tokenSha256.toByteArray())
            }
        }
        if (grant == null) call.respond(HttpStatusCode.Forbidden) else call.respond(grant)
    }
    // A distinct signaling room prevents existing clients broadcasting ordinary entity data to workers.
    val hub = SignalingHub()
    webSocket("/ws/backup") {
        var joined: SignalingMessage.Join? = null
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = runCatching { Json.decodeFromString<SignalingMessage>(frame.readText()) }.getOrNull()
                if (message is SignalingMessage.Join && joined == null) {
                    val worker = control.workerForToken(message.accessToken)
                    val allowed = if (worker != null) {
                        message.deviceId == BACKUP_WORKER_ID && control.query { assigned(message.householdId, worker) }
                    } else {
                        message.deviceId != BACKUP_WORKER_ID &&
                            (verifier == null || authorizeHostedJoin(verifier, lookup, message.accessToken, message.householdId) is JoinAuthorizationResult.Authorized) &&
                            control.query { get(message.householdId, false).enabled }
                    }
                    if (!allowed) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized")); break }
                    joined = message
                    hub.register(message.householdId, message.deviceId, this)
                } else {
                    val identity = joined
                    if (identity == null || message == null || !isValidClientSignal(message, identity.deviceId) ||
                        !control.query { get(identity.householdId, false).enabled }) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized")); break
                    }
                    val target = when (message) {
                        is SignalingMessage.Offer -> message.toDeviceId
                        is SignalingMessage.Answer -> message.toDeviceId
                        is SignalingMessage.IceCandidate -> message.toDeviceId
                        else -> ""
                    }
                    if (identity.deviceId != BACKUP_WORKER_ID && target != BACKUP_WORKER_ID) continue
                    hub.relay(identity.householdId, identity.deviceId, message)
                }
            }
        } finally { joined?.let { hub.unregister(it.householdId, it.deviceId, this) } }
    }
}
