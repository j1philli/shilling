package finance.shilling.shared.data.store.backup

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.core.backup.*
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.store.cloud.*
import finance.shilling.shared.data.usecase.*
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.mcp.*
import finance.shilling.shared.presentation.OccurrenceActions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * A bounded, disposable trusted working view. Durable data stays encrypted in the cloud repository;
 * all entity reads, writes, validation and conflict merges use the same Store5 path as devices.
 * The worker admits a bounded number of these calls, rather than running a database per household.
 */
class CloudMcpRepository(private val household: String, private val storage: EncryptedBackupRepository) {
    init {
        // HttpServer executor threads can inherit the platform context class loader. If their
        // first JDBC use initializes DriverManager, service discovery misses the SQLite driver.
        // Register via this application's class loader, including on a cold worker restart.
        Class.forName("org.sqlite.JDBC")
    }
    private val mutex = Mutex()

    suspend fun handle(body: String, idempotencyKey: String?, authorize: suspend () -> CloudMcpGrant?): String? = mutex.withLock {
        val grant = authorize() ?: throw McpAccessDenied()
        if (!grant.read) throw McpAccessDenied()
        val message = Json.parseToJsonElement(body) as? JsonObject ?: error("Send one MCP request per POST")
        val params = message["params"] as? JsonObject
        val toolName = (params?.get("name") as? JsonPrimitive)?.contentOrNull
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ShillingDatabase.Schema.create(driver).await()
            val db = ShillingDatabase(driver)
            val device = "cloud-mcp"
            val ids = object : IdGenerator { override fun newId() = Uuid.random().toString() }
            val notifier = ChangeNotifier()
            val deps = StoreSyncDeps(db, null, device, ids)
            val accountStore = createAccountStore(db, deps)
            val categoryStore = createCategoryStore(db, deps)
            val scheduleStore = createScheduleStore(db, deps)
            val exceptionStore = createScheduleExceptionStore(db, deps)
            val postingStore = createPostingStore(db, deps)
            val accounts = AccountRepository(notifier, accountStore, deps)
            val categories = CategoryRepository(notifier, categoryStore, deps)
            val schedules = ScheduleRepository(notifier, scheduleStore, exceptionStore, deps)
            val postings = PostingRepository(ids, notifier, postingStore, accountStore, categoryStore, scheduleStore, deps)
            val facade = SyncStoreFacade(db)
            val toolbox = McpToolbox(accounts, categories, schedules, postings,
                ComputeWindowUseCase(notifier, accounts, categories, schedules, postings),
                ComputeBudgetUseCase(notifier, categories, schedules), OccurrenceActions(postings, schedules), ids)
            val server = McpServer(toolbox, authorize = {
                authorize()?.takeIf { it.clientId == grant.clientId && it.tokenSha256 == grant.tokenSha256 }
                    ?.let { McpPermissions(it.read, it.write) }
            })
            val tool = if (message["method"]?.jsonPrimitive?.content == "tools/call") toolbox.tools.find { it.name == toolName } else null
            if (tool == null || !McpPermissions(grant.read, grant.write).allows(tool)) return@withLock server.handle(body)
            // Notifications cannot invoke tools or mutate the budget.
            if (message["id"] == null || message["id"] is JsonNull) return@withLock null
            val commandId: String?
            val expires: Long
            if (!tool.readOnly) {
                val key = requireNotNull(idempotencyKey) { "Editing requests require Idempotency-Key: <epoch-millis>.<UUID>" }
                require(key.matches(Regex("[0-9]{13}\\.[a-fA-F0-9-]{36}"))) { "Invalid Idempotency-Key" }
                val created = key.substringBefore('.').toLong()
                expires = created + 7L * 24 * 60 * 60 * 1000
                require(created <= System.currentTimeMillis() + 300_000 && expires > System.currentTimeMillis()) { "Expired Idempotency-Key; do not retry expired writes" }
                commandId = sha256("${grant.tokenSha256}|$key")
                storage.command(commandId)?.let { previous ->
                    require(previous.requestHash == sha256(body)) { "Idempotency-Key was used with a different request" }
                    return@withLock previous.response
                }
            } else { commandId = null; expires = 0 }
            var cursor = 0L
            var totalBytes = 0
            var totalChanges = 0
            var baseline = false
            // A checkpoint can advance during reconstruction. Restarting from its complete state is
            // safe under the same LWW/tombstone merge rules as an offline device.
            for (pageIndex in 0 until 128) {
                val page = request(BackupRequest.Operation.LIST, cursor = cursor)
                if (!baseline) require(page.checkpoint?.encryption == BackupEncryption.TRUSTED_CLOUD) {
                    "Cloud MCP is waiting for a complete trusted checkpoint from a device"
                }
                for (entry in listOfNotNull(page.checkpoint) + page.backups) {
                    require(entry.encryption == BackupEncryption.TRUSTED_CLOUD) { "Cloud MCP is waiting for a trusted checkpoint" }
                    val bytes = download(entry)
                    try {
                        totalBytes += bytes.size
                        require(totalBytes <= 64 * 1024 * 1024) { "Cloud working-view limit exceeded" }
                        val delta = decodeCloudDelta(household, bytes)
                        require(delta.checkpoint == (entry.kind == CloudObjectKind.CHECKPOINT))
                        require(if (delta.checkpoint) delta.baseSequence == entry.sequence && entry.sequence > cursor else entry.sequence == cursor + 1)
                        require(delta.files.all { it.objectInfo.encryption == BackupEncryption.TRUSTED_CLOUD })
                        totalChanges += delta.changes.size
                        require(totalChanges <= 100_000) { "Cloud working-view limit exceeded" }
                        delta.changes.forEach { facade.applyIncomingChange(it) }
                        cursor = entry.sequence
                        baseline = true
                    } finally { bytes.fill(0) }
                }
                if (cursor == page.headSequence) break
                require(pageIndex < 127) { "Cloud catch-up limit exceeded; retry after a device checkpoints" }
            }
            notifier.notifyChanged()
            val before = facade.cloudState(device).associateBy { it.entityType to it.entityId }
            val response = server.handle(body) ?: return@withLock null
            if (tool.readOnly) return@withLock response
            val result = Json.parseToJsonElement(response).jsonObject
            if (result["error"] != null || result["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull == true) return@withLock response
            val changes = facade.cloudState(device).filter { before[it.entityType to it.entityId] != it }
            val current = authorize() ?: throw McpAccessDenied()
            if (current.tokenSha256 != grant.tokenSha256 || !current.read || !current.write) throw McpAccessDenied()
            val command = CloudCommand(requireNotNull(commandId), sha256(body), response, expires)
            if (changes.isEmpty()) {
                check(storage.commitCommand(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.LIST), command).error == null)
            } else {
                require(changes.size <= 512)
                val bytes = Json.encodeToString(CloudDelta(household = household, changes = changes, files = emptyList())).encodeToByteArray()
                try {
                    // Same validated format that device catch-up consumes over WebRTC.
                    decodeCloudDelta(household, bytes)
                    val chunks = bytes.asList().chunked(BACKUP_CHUNK_BYTES).map { Base64.encode(it.toByteArray()) }
                    val uploadId = Uuid.random().toString()
                    request(BackupRequest.Operation.BEGIN, uploadId, chunks = chunks.size)
                    try {
                        chunks.forEachIndexed { index, chunk -> request(BackupRequest.Operation.PUT, uploadId, index = index, chunk = chunk) }
                        check(storage.commitCommand(BackupRequest(Uuid.random().toString(), BackupRequest.Operation.COMMIT, uploadId,
                            encryption = BackupEncryption.TRUSTED_CLOUD), command).error == null) { "Cloud commit failed; retry with the same Idempotency-Key" }
                    } catch (e: Exception) { request(BackupRequest.Operation.ABORT, uploadId); throw e }
                } finally { bytes.fill(0) }
            }
            response
        } finally { driver.close() }
    }

    private suspend fun request(op: BackupRequest.Operation, id: String = "", index: Int = 0, chunk: String = "", chunks: Int = 0, cursor: Long = 0): BackupResponse =
        storage.handle(BackupRequest(Uuid.random().toString(), op, id, index, chunks, chunk,
            encryption = BackupEncryption.TRUSTED_CLOUD, cursor = cursor)).also { check(it.error == null) { "Cloud storage request failed" } }

    private suspend fun download(info: BackupInfo): ByteArray {
        require(info.chunks in 1..((MAX_CLOUD_BATCH_BYTES + BACKUP_CHUNK_BYTES - 1) / BACKUP_CHUNK_BYTES))
        val output = java.io.ByteArrayOutputStream()
        for (i in 0 until info.chunks) {
            val bytes = Base64.decode(requireNotNull(request(BackupRequest.Operation.GET, info.id, index = i).chunk))
            try {
                require(bytes.size in 1..BACKUP_CHUNK_BYTES && output.size() + bytes.size <= MAX_CLOUD_BATCH_BYTES)
                output.write(bytes)
            } finally { bytes.fill(0) }
        }
        return output.toByteArray()
    }
}

class McpAccessDenied : Exception("MCP authorization required")
fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
