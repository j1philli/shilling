@file:OptIn(io.ktor.utils.io.ExperimentalKtorApi::class)
package finance.shilling.backup

import com.sun.net.httpserver.HttpServer
import finance.shilling.core.backup.*
import finance.shilling.core.auth.ServerConfig
import finance.shilling.shared.data.store.backup.BackupDatabase
import finance.shilling.shared.data.store.backup.SupabaseObjectStorage
import finance.shilling.core.sync.IceServersResponse
import finance.shilling.shared.data.backup.*
import finance.shilling.shared.data.store.backup.EncryptedBackupRepository
import finance.shilling.shared.data.sync.SignalingClient
import finance.shilling.shared.data.sync.createSyncHttpClient
import finance.shilling.shared.data.store.backup.CloudMcpRepository
import io.ktor.http.HttpStatusCode
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.webrtc.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

fun main() = runBlocking {
    val server = requireNotNull(System.getenv("SHILLING_BACKUP_SERVER_URL")) { "SHILLING_BACKUP_SERVER_URL is required" }.trimEnd('/')
    val uri = URI(server)
    require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1"))) { "Use HTTPS outside localhost" }
    val token = requireNotNull(System.getenv("SHILLING_BACKUP_WORKER_SECRET")) { "SHILLING_BACKUP_WORKER_SECRET is required" }
    require(token.length >= 32)
    val storageKey = System.getenv("SHILLING_BACKUP_STORAGE_KEY")?.let { java.util.Base64.getDecoder().decode(it) }
    require(storageKey == null || storageKey.size == 32) { "Storage key must be 32 bytes, base64 encoded" }
    val http = createSyncHttpClient()
    val database = try {
        val config = http.get("$server/api/config").body<ServerConfig>()
        when (BackupDatabase.storageMode(config.authMode, System.getenv("SHILLING_BACKUP_STORAGE"))) {
            "supabase" -> BackupDatabase.supabase(
                requireNotNull(System.getenv("SHILLING_BACKUP_DATABASE_URL")) { "SHILLING_BACKUP_DATABASE_URL is required in hosted mode" },
                requireNotNull(System.getenv("SHILLING_BACKUP_DATABASE_USER")) { "SHILLING_BACKUP_DATABASE_USER is required" },
                requireNotNull(System.getenv("SHILLING_BACKUP_DATABASE_PASSWORD")) { "SHILLING_BACKUP_DATABASE_PASSWORD is required" },
                System.getenv("SHILLING_BACKUP_DATABASE_POOL_SIZE")?.toInt() ?: 8,
                SupabaseObjectStorage(
                    requireNotNull(System.getenv("SHILLING_CLOUD_STORAGE_URL")) { "SHILLING_CLOUD_STORAGE_URL is required" },
                    requireNotNull(System.getenv("SHILLING_CLOUD_STORAGE_KEY")) { "SHILLING_CLOUD_STORAGE_KEY is required" },
                    System.getenv("SHILLING_CLOUD_STORAGE_BUCKET") ?: "cloud-peer"
                )
            )
            else -> BackupDatabase.sqlite(Path.of(requireNotNull(System.getenv("SHILLING_BACKUP_DATA_DIR")) { "SHILLING_BACKUP_DATA_DIR is required for SQLite" }))
        }
    } catch (e: Exception) { http.close(); throw e }
    val lastPoll = AtomicLong(0)
    // JDK HTTP server limits complement the mandatory hosted reverse-proxy timeouts.
    System.setProperty("sun.net.httpserver.maxReqTime", "15")
    System.setProperty("sun.net.httpserver.maxRspTime", "90")
    val health = HttpServer.create(InetSocketAddress("0.0.0.0", System.getenv("SHILLING_BACKUP_HEALTH_PORT")?.toInt() ?: 8082), 0)
    health.createContext("/health") { exchange ->
        val ready = System.currentTimeMillis() - lastPoll.get() < 60_000
        val bytes = (if (ready) "ready" else "unavailable").toByteArray()
        exchange.sendResponseHeaders(if (ready) 200 else 503, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    var ice: List<WebRtc.IceServer> = emptyList()
    val rtc = WebRtcClient(JvmWebRtc) { defaultConnectionConfig = { iceServers = ice } }
    data class Household(val scope: CoroutineScope, val transport: BackupRtcTransport)
    val sessions = mutableMapOf<String, Household>()
    val modes = java.util.concurrent.ConcurrentHashMap<String, BackupEncryption>()
    val mcpRepositories = java.util.concurrent.ConcurrentHashMap<String, CloudMcpRepository>()
    val mcpAssignments = java.util.concurrent.atomic.AtomicReference<List<BackupAssignment>>(emptyList())
    val mcpExecutor = java.util.concurrent.ThreadPoolExecutor(4, 4, 0, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(16))
    val mcpUrl = System.getenv("SHILLING_CLOUD_MCP_URL")
    mcpUrl?.let { publicUrl ->
        health.executor = mcpExecutor
        installCloudMcp(health, publicUrl, token.toByteArray(), resolve = { hash ->
            if (System.currentTimeMillis() - lastPoll.get() >= 60_000) null else {
                val assignment = mcpAssignments.get().singleOrNull { a -> a.encryption == BackupEncryption.TRUSTED_CLOUD &&
                    a.mcpClients.any { it.tokenSha256 == hash && it.expiresAt > System.currentTimeMillis() } }
                assignment?.let { a -> mcpRepositories[a.householdId]?.let { a.householdId to it } }
            }
        }, authorize = { household, hash ->
            val response = http.post("$server/api/backup/mcp/authorize") {
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                setBody(CloudMcpAuthorizationRequest(household, hash))
            }
            if (response.status == HttpStatusCode.OK) response.body<CloudMcpGrant>() else null
        })
    }
    val maxHouseholds = System.getenv("SHILLING_BACKUP_MAX_HOUSEHOLDS")?.toInt() ?: 256
    val slots = kotlinx.coroutines.sync.Semaphore(System.getenv("SHILLING_BACKUP_MAX_CONNECTIONS")?.toInt() ?: 32)
    require(maxHouseholds > 0)
    health.start()
    val job = coroutineContext[Job]!!
    Runtime.getRuntime().addShutdownHook(Thread { job.cancel() })
    try {
        while (isActive) {
            try {
                withContext(Dispatchers.IO) { database.checkReady() }
                val response = http.get("$server/api/ice-servers") { header("Authorization", "Bearer $token") }.body<IceServersResponse>()
                require(response.ttlSeconds >= 60) { "TURN credential lifetime must be at least 60 seconds" }
                require(response.iceServers.any { entry -> entry.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } && entry.credential != null }) { "TURN is required" }
                ice = response.webRtcServers()
                val assignments = http.get("$server/api/backup/assignments") { header("Authorization", "Bearer $token") }.body<List<BackupAssignment>>()
                require(assignments.size <= maxHouseholds) { "Worker household capacity exceeded" }
                require(storageKey != null || assignments.none { it.encryption == BackupEncryption.TRUSTED_CLOUD }) { "Trusted cloud requires SHILLING_BACKUP_STORAGE_KEY" }
                assignments.forEach { modes[it.householdId] = it.encryption }
                mcpAssignments.set(assignments)
                for (removed in mcpRepositories.keys - assignments.map { it.householdId }.toSet()) mcpRepositories.remove(removed)
                val active = assignments.map { it.householdId }.toSet()
                for (removed in sessions.keys - active) sessions.remove(removed)?.let { it.transport.close(); it.scope.cancel() }
                for (household in active - sessions.keys) {
                    val scope = CoroutineScope(SupervisorJob(job) + Dispatchers.Default)
                    val store = EncryptedBackupRepository(database, household, storageKey) { modes.getValue(household) }
                    if (mcpUrl != null) mcpRepositories[household] = CloudMcpRepository(household, store)
                    val signal = SignalingClient(http, server, BACKUP_WORKER_ID, household, path = "/ws/backup", accessToken = { token })
                    val transport = BackupRtcTransport(rtc, signal, BACKUP_WORKER_ID, scope, offerTo = { false }, connectionSlots = slots) { _, text ->
                        val request = Json.decodeFromString<BackupRequest>(text)
                        Json.encodeToString(store.handle(request))
                    }
                    sessions[household] = Household(scope, transport)
                    transport.start()
                }
                lastPoll.set(System.currentTimeMillis())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Fail closed if assignments cannot be renewed; do not keep accepting stale authorization.
                for (session in sessions.values) { session.transport.close(); session.scope.cancel() }
                sessions.clear()
                mcpRepositories.clear(); mcpAssignments.set(emptyList())
                lastPoll.set(0)
                System.err.println("Backup storage/assignment/ICE refresh failed; transfers paused.")
            }
            delay(15_000)
        }
    } finally {
        withContext(NonCancellable) { sessions.values.forEach { it.transport.close(); it.scope.cancel() } }
        rtc.close(); http.close(); health.stop(0); mcpExecutor.shutdownNow(); database.close()
    }
}
