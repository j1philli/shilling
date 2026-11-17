@file:OptIn(io.ktor.utils.io.ExperimentalKtorApi::class)
package finance.shilling.server

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.core.backup.*
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.backup.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.store.backup.RecoveryKeyRepository
import finance.shilling.shared.data.store.cloud.CloudSyncRepository
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import io.ktor.client.webrtc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import finance.shilling.shared.data.store.backup.sha256
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlin.uuid.Uuid

/** Opt-in acceptance test: production server and worker JARs in separate OS processes. */
class BackupLiveProcessTest {
    private val postgresUrl = System.getenv("SHILLING_TEST_BACKUP_DATABASE_URL")
    private val postgresUser = System.getenv("SHILLING_TEST_BACKUP_DATABASE_USER")
    private val postgresPassword = System.getenv("SHILLING_TEST_BACKUP_DATABASE_PASSWORD")
    private val chunkTable get() = if (postgresUrl == null) "cloud_chunks" else "private_backup.cloud_chunks"
    private fun storageConnection(path: Path) = if (postgresUrl == null) DriverManager.getConnection("jdbc:sqlite:$path")
        else DriverManager.getConnection(postgresUrl, postgresUser, postgresPassword)

    @Test fun liveCloudPeerSurvivesInterruptionsAndSyncsBothModes(): Unit = runBlocking {
        val serverJar = System.getenv("SHILLING_TEST_SERVER_JAR")
        val workerJar = System.getenv("SHILLING_TEST_WORKER_JAR")
        val turnUrl = System.getenv("SHILLING_TEST_LIVE_TURN_URL")
        val turnSecret = System.getenv("SHILLING_TEST_LIVE_TURN_SECRET")
        org.junit.jupiter.api.Assumptions.assumeTrue(listOf(serverJar, workerJar, turnUrl, turnSecret).all { it != null }, "Run scripts/test-live-backup.sh")
        val root = Files.createTempDirectory("shilling-live-backup-")
        val workerSecret = Uuid.random().toString() + Uuid.random().toString()
        val storageKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val port = freePort()
        val healthPort = freePort()
        val url = "http://127.0.0.1:$port"
        val serverEnv = mutableMapOf(
            "SHILLING_SERVER_PORT" to "$port", "SHILLING_SERVER_HOST" to "127.0.0.1", "SHILLING_AUTH_MODE" to "none",
            "SHILLING_BACKUP_WORKERS" to "live-worker=$workerSecret", "SHILLING_BACKUP_POLICY_FILE" to root.resolve("policy.json").toString(),
            "SHILLING_BACKUP_TRUSTED_CLOUD_ENABLED" to "true", "SHILLING_STUN_URLS" to "", "SHILLING_TURN_URLS" to turnUrl!!,
            "SHILLING_TURN_SECRET" to turnSecret!!, "SHILLING_TURN_TTL" to "120"
        )
        val workerEnv = mutableMapOf(
            "SHILLING_BACKUP_SERVER_URL" to url, "SHILLING_BACKUP_WORKER_SECRET" to workerSecret,
            "SHILLING_BACKUP_DATA_DIR" to root.resolve("objects").toString(), "SHILLING_BACKUP_HEALTH_PORT" to "$healthPort",
            "SHILLING_BACKUP_STORAGE_KEY" to Base64.getEncoder().encodeToString(storageKey),
            "SHILLING_CLOUD_MCP_URL" to "http://127.0.0.1:$healthPort/mcp"
        )
        if (postgresUrl != null) {
            workerEnv.remove("SHILLING_BACKUP_DATA_DIR") // Hosted worker must survive with no local payload volume.
            workerEnv.putAll(mapOf("SHILLING_BACKUP_STORAGE" to "supabase", "SHILLING_BACKUP_DATABASE_URL" to postgresUrl,
                "SHILLING_BACKUP_DATABASE_USER" to postgresUser!!, "SHILLING_BACKUP_DATABASE_PASSWORD" to postgresPassword!!))
        }
        if (postgresUrl != null) {
            serverEnv.putAll(mapOf("SHILLING_CLOUD_CONTROL_DATABASE_URL" to postgresUrl,
                "SHILLING_CLOUD_CONTROL_DATABASE_USER" to "shilling_cloud_control",
                "SHILLING_CLOUD_CONTROL_DATABASE_PASSWORD" to postgresPassword!!))
            workerEnv.putAll(mapOf("SHILLING_CLOUD_STORAGE_URL" to requireNotNull(System.getenv("SHILLING_TEST_STORAGE_URL")),
                "SHILLING_CLOUD_STORAGE_KEY" to requireNotNull(System.getenv("SHILLING_TEST_STORAGE_KEY"))))
        }
        val server = launch(serverJar!!, serverEnv, root.resolve("server.log"))
        var worker: Process? = null
        val http = createSyncHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transports = mutableListOf<BackupRtcTransport>()
        var rtc: WebRtcClient? = null
        val report = mutableListOf<String>()
        fun passed(message: String) { report += message; println("LIVE BACKUP: $message") }
        try {
            withTimeout(600_000) {
                waitReady("$url/health", server)
                val households = List(2) { Uuid.random().toString() }
                val apis = households.map { ServerApi(http, url, householdId = it) }
                for ((i, api) in apis.withIndex()) {
                    val mode = BackupEncryption.entries[i]
                    val initial = api.backupPolicy()
                    assertEquals(BackupEncryption.END_TO_END, initial.encryption)
                    if (mode == BackupEncryption.TRUSTED_CLOUD) assertFails { api.updateBackupPolicy(BackupPolicyUpdate(true, true, initial.revision, mode)) }
                    val enabled = api.updateBackupPolicy(BackupPolicyUpdate(true, false, initial.revision, mode, if (i == 1) TRUSTED_CLOUD_CONSENT else null))
                    assertTrue(enabled.turnEnabled)
                    assertFails { api.updateBackupPolicy(BackupPolicyUpdate(true, false, enabled.revision, mode)) }
                }
                passed("Real HTTP policy enforces explicit trusted consent and mandatory TURN")
                var ice = apis[0].fetchIceServers(allowRelay = true).webRtcServers()
                rtc = WebRtcClient(JvmWebRtc) { defaultConnectionConfig = { iceServers = ice; iceTransportPolicy = WebRtc.IceTransportPolicy.RELAY } }
                worker = launch(workerJar!!, workerEnv, root.resolve("worker.log"))
                waitReady("http://127.0.0.1:$healthPort/health", worker!!)
                suspend fun connect(household: String): BackupPeerClient {
                    // Refresh expiring TURN credentials just as foreground clients do; the full
                    // multi-restart/MCP suite can exceed one credential lifetime.
                    ice = apis[0].fetchIceServers(allowRelay = true).webRtcServers()
                    val id = Uuid.random().toString()
                    val peer = BackupPeerClient()
                    val transport = BackupRtcTransport(rtc!!, SignalingClient(http, url, id, household, path = "/ws/backup"), id, scope,
                        offerTo = { it == BACKUP_WORKER_ID }, onMessage = peer::receive)
                    transports += transport; peer.transport = transport; transport.start()
                    transport.awaitPeer(BACKUP_WORKER_ID)
                    return peer
                }
                for ((i, household) in households.withIndex()) {
                    val mode = BackupEncryption.entries[i]
                    val crypto = BackupCrypto()
                    val key = if (mode == BackupEncryption.END_TO_END) crypto.newKey() else null
                    var peer = connect(household)
                    val source = Budget()
                    val target = Budget()
                    try {
                        source.initialize(); target.initialize()
                        val marker = "LIVE-BUDGET-PRIVATE-$household"
                        val receipt = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
                        AccountRepository(source.notifier, createAccountStore(source.db)).upsert(Account("checking", marker, 1234.56))
                        ReceiptRepository(source.notifier, createReceiptStore(source.db))
                            .save(Receipt("receipt-live", null, "receipts/receipt-live.bin", "receipt.bin", 123))
                        source.files.store("receipt-live", "receipt.bin", receipt)
                        // A receiving device already has its own data and receipt metadata from direct P2P.
                        AccountRepository(target.notifier, createAccountStore(target.db)).upsert(Account("target-local", "Keep local data", 99.0))
                        ReceiptRepository(target.notifier, createReceiptStore(target.db))
                            .save(Receipt("receipt-live", null, "receipts/receipt-live.bin", "receipt.bin", 123))
                        val dbPath = objectPath(root, household)
                        val policy = apis[i].backupPolicy()
                        val sourceKeys = RecoveryKeyRepository(MemoryVault(), "source")
                        val targetKeys = RecoveryKeyRepository(MemoryVault(), "target")
                        if (key != null) { sourceKeys.importKey(key, true); targetKeys.importKey(key, true) }
                        else { sourceKeys.consentToTrustedCloud(policy.encryptionRevision); targetKeys.consentToTrustedCloud(policy.encryptionRevision) }
                        val sourceCloud = CloudSyncRepository(source.db, SyncStoreFacade(source.db), source.files, source.notifier)
                        val targetCloud = CloudSyncRepository(target.db, SyncStoreFacade(target.db), target.files, target.notifier)
                        val sourceSync = CloudSyncSession(sourceCloud, sourceKeys, "cloud", household, "source-device")
                        val targetSync = CloudSyncSession(targetCloud, targetKeys, "cloud", household, "target-device")
                        val sourceDeps = StoreSyncDeps(source.db, null, "source-device", object : IdGenerator { override fun newId() = Uuid.random().toString() })
                        val sourceAccounts = AccountRepository(source.notifier, createAccountStore(source.db, sourceDeps), sourceDeps)
                        sourceAccounts.upsert(Account("cloud-only", "Cloud handoff", 777.0))
                        // Persist the exact outbox, acknowledge one chunk, then hard-kill the worker.
                        val pending = requireNotNull(sourceCloud.prepare("cloud", household, "source-device", policy, key))
                        val receiptUpload = pending.objects.single()
                        peer.request(BackupRequest.Operation.BEGIN, receiptUpload.info.id, chunks = receiptUpload.chunks.size, encryption = mode, keyId = pending.keyId, kind = CloudObjectKind.RECEIPT)
                        peer.request(BackupRequest.Operation.PUT, receiptUpload.info.id, 0, chunk = receiptUpload.chunks.first(), encryption = mode)
                        assertTrue(peer.request(BackupRequest.Operation.LIST).backups.isEmpty())
                        assertFails { peer.request(BackupRequest.Operation.COMMIT, receiptUpload.info.id, encryption = mode) }
                        worker!!.destroyForcibly(); assertTrue(worker!!.waitFor(10, TimeUnit.SECONDS)); peer.transport.close()
                        worker = launch(workerJar, workerEnv, root.resolve("worker.log"))
                        waitReady("http://127.0.0.1:$healthPort/health", worker!!)
                        peer = connect(household)
                        sourceSync.sync(policy, peer)
                        passed("$mode: interrupted upload resumed from durable outbox; acknowledged chunk replay and commit succeeded")
                        val cloudInfo = peer.request(BackupRequest.Operation.LIST).backups.first()
                        assertEquals(pending.id, cloudInfo.id)
                        if (postgresUrl != null) storageConnection(dbPath).use { db -> db.prepareStatement("SELECT count(*) FROM $chunkTable WHERE snapshot=?").use {
                            it.setString(1, receiptUpload.info.id); it.executeQuery().use { r -> r.next(); assertEquals(0, r.getInt(1)) }
                        } }
                        val atRest = readChunk(dbPath, cloudInfo.id)
                        val wire = peer.request(BackupRequest.Operation.GET, cloudInfo.id, 0).chunk!!
                        if (postgresUrl == null) assertFalse(String(Files.readAllBytes(dbPath), Charsets.ISO_8859_1).contains(marker))
                        else assertFalse(Files.exists(root.resolve("objects")))
                        assertFalse(String(Base64.getDecoder().decode(atRest), Charsets.ISO_8859_1).contains(marker))
                        if (key != null) {
                            assertEquals(atRest, wire)
                            assertFails { decryptAtRest(storageKey, household, cloudInfo, atRest) }
                            assertFails { downloadCloud(household, crypto.newKey(), cloudInfo, peer) }
                        } else {
                            assertNotEquals(atRest, wire)
                            assertContentEquals(Base64.getDecoder().decode(wire), decryptAtRest(storageKey, household, cloudInfo, atRest))
                        }
                        val bytes = downloadCloud(household, key, cloudInfo, peer)
                        val tampered = Base64.getDecoder().decode(atRest).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                        writeChunk(dbPath, cloudInfo.id, Base64.getEncoder().encodeToString(tampered))
                        assertFails { downloadCloud(household, key, cloudInfo, peer) }
                        writeChunk(dbPath, cloudInfo.id, atRest)
                        assertContentEquals(bytes, downloadCloud(household, key, cloudInfo, peer))
                        val other = connect(households[1 - i])
                        assertTrue(other.request(BackupRequest.Operation.LIST).backups.none { it.id == cloudInfo.id })
                        assertFails { other.request(BackupRequest.Operation.GET, cloudInfo.id, 0) }
                        other.transport.close()
                        assertFails { peer.request(BackupRequest.Operation.BEGIN, Uuid.random().toString(), chunks = 1, encryption = BackupEncryption.entries[1 - i]) }
                        passed("$mode: encrypted storage, service-key access boundary, tamper rejection and household isolation verified")
                        peer.transport.close()
                        // All source devices are offline, and the actual worker is hard-restarted.
                        worker!!.destroyForcibly(); assertTrue(worker!!.waitFor(10, TimeUnit.SECONDS))
                        worker = launch(workerJar, workerEnv, root.resolve("worker.log"))
                        waitReady("http://127.0.0.1:$healthPort/health", worker!!)
                        target.files.delete("receipt-live")
                        peer = connect(household)
                        targetSync.sync(policy, peer)
                        assertTrue(target.db.accountQueries.selectAll("__local__").awaitAsList().any { it.id == "cloud-only" && it.balance == 777.0 })
                        assertContentEquals(receipt, target.files.read("receipt-live"))
                        assertTrue(target.db.accountQueries.selectAll("__local__").awaitAsList().any { it.id == "target-local" && it.balance == 99.0 })
                        assertTrue(target.db.accountQueries.selectAll("__local__").awaitAsList().any { it.id == "checking" && it.name == marker && it.balance == 1234.56 })
                        peer.transport.close()
                        sourceAccounts.delete("cloud-only")
                        peer = connect(household); sourceSync.sync(policy, peer); peer.transport.close()
                        peer = connect(household); targetSync.sync(policy, peer)
                        assertTrue(target.db.accountQueries.selectAll("__local__").awaitAsList().none { it.id == "cloud-only" })
                        passed("$mode CLOUD PEER: nonempty device catches up with source offline after hard worker restart; receipt bytes recovered; deletion propagated")
                        peer.transport.close()
                        peer = connect(household)
                        repeat(64) { version ->
                            sourceAccounts.upsert(Account("checkpoint-tail", "Checkpoint version $version", version.toDouble()))
                            sourceSync.sync(policy, peer)
                        }
                        assertTrue(peer.request(BackupRequest.Operation.LIST).checkpointSequence >= 64)
                        peer.transport.close()
                        worker!!.destroyForcibly(); assertTrue(worker!!.waitFor(10, TimeUnit.SECONDS))
                        worker = launch(workerJar, workerEnv, root.resolve("worker.log"))
                        waitReady("http://127.0.0.1:$healthPort/health", worker!!)
                        target.files.delete("receipt-live")
                        peer = connect(household)
                        targetSync.sync(policy, peer)
                        assertTrue(target.db.accountQueries.selectAll("__local__").awaitAsList().any { it.id == "checkpoint-tail" && it.balance == 63.0 })
                        assertTrue(target.db.accountQueries.selectAll("__local__").awaitAsList().none { it.id == "cloud-only" })
                        assertContentEquals(receipt, target.files.read("receipt-live"))
                        passed("$mode CHECKPOINT: automatic compaction and WebRTC checkpoint/tail catch-up survived restart with receipts and tombstones")
                        peer.transport.close()
                        // Three independent device databases and simultaneously open TURN channels.
                        val devices = List(3) { Budget() }
                        val connections = mutableListOf<BackupPeerClient>()
                        try {
                            val sessions = devices.mapIndexed { index, device ->
                                device.initialize()
                                val keys = RecoveryKeyRepository(MemoryVault(), "concurrent-$index")
                                if (key != null) keys.importKey(key, true) else keys.consentToTrustedCloud(policy.encryptionRevision)
                                CloudSyncSession(CloudSyncRepository(device.db, SyncStoreFacade(device.db), device.files, device.notifier),
                                    keys, "cloud", household, "concurrent-$index")
                            }
                            val accounts = devices.mapIndexed { index, device ->
                                val deps = StoreSyncDeps(device.db, null, "concurrent-$index", object : IdGenerator { override fun newId() = Uuid.random().toString() })
                                AccountRepository(device.notifier, createAccountStore(device.db, deps), deps)
                            }
                            repeat(3) { connections += connect(household) }
                            var retries = 0
                            suspend fun syncDevice(index: Int) {
                                // Production foreground sync retries on the next tick. Bound retries here so
                                // contention is tolerated but a permanent failure cannot masquerade as success.
                                repeat(20) { attempt ->
                                    try { sessions[index].sync(policy, connections[index]); return }
                                    catch (e: IllegalStateException) {
                                        retries++
                                        if (attempt == 19) throw e
                                        delay(40L * (index + 1) + attempt * 20L)
                                    }
                                }
                            }
                            suspend fun syncTogether(indices: List<Int> = listOf(0, 1, 2)) = coroutineScope {
                                indices.map { index -> async { syncDevice(index) } }.awaitAll()
                            }
                            repeat(3) { syncDevice(it) }
                            val receipts = List(3) { index -> ByteArray(256 * 1024 + index) { ((it + index) % 251).toByte() } }
                            coroutineScope {
                                devices.mapIndexed { index, device -> async {
                                    accounts[index].upsert(Account("concurrent-own-$index", "Device $index", index.toDouble()))
                                    ReceiptRepository(device.notifier, createReceiptStore(device.db))
                                        .save(Receipt("concurrent-receipt-$index", null, "receipts/concurrent-$index.bin", "concurrent-$index.bin", 123))
                                    device.files.store("concurrent-receipt-$index", "concurrent-$index.bin", receipts[index])
                                    repeat(5) { version ->
                                        accounts[index].upsert(Account("concurrent-conflict", "Device $index version $version", (index * 100 + version).toDouble()))
                                        syncDevice(index)
                                    }
                                } }.awaitAll()
                            }
                            repeat(3) { syncTogether() }
                            suspend fun assertConverged() {
                                val rows = devices.map { it.db.accountQueries.selectAll("__local__").awaitAsList().sortedBy { it.id } }
                                assertEquals(rows[0], rows[1]); assertEquals(rows[0], rows[2])
                                repeat(3) { index ->
                                    assertTrue(rows[0].any { it.id == "concurrent-own-$index" })
                                    devices.forEach { assertContentEquals(receipts[index], it.files.read("concurrent-receipt-$index")) }
                                }
                            }
                            assertConverged()
                            // The disconnected device makes an older conflicting edit. Connected devices
                            // delete it and compact the log while that device remains offline.
                            connections[2].transport.close()
                            accounts[2].upsert(Account("concurrent-conflict", "Offline stale edit", -1.0))
                            accounts[2].upsert(Account("offline-survivor", "Keep offline work", 42.0))
                            delay(25)
                            accounts[0].delete("concurrent-conflict")
                            repeat(34) { version ->
                                coroutineScope {
                                    listOf(0, 1).map { index -> async {
                                        accounts[index].upsert(Account("concurrent-own-$index", "Online version $version", version.toDouble()))
                                        syncDevice(index)
                                    } }.awaitAll()
                                }
                            }
                            val compacted = connections[0].request(BackupRequest.Operation.LIST)
                            assertTrue(compacted.checkpointSequence > 64)
                            connections.forEach { it.transport.close() }
                            worker!!.destroyForcibly(); assertTrue(worker!!.waitFor(10, TimeUnit.SECONDS))
                            worker = launch(workerJar, workerEnv, root.resolve("worker.log"))
                            waitReady("http://127.0.0.1:$healthPort/health", worker!!)
                            connections.clear(); repeat(3) { connections += connect(household) }
                            repeat(4) { syncTogether() }
                            assertConverged()
                            devices.forEach { device ->
                                val rows = device.db.accountQueries.selectAll("__local__").awaitAsList()
                                assertTrue(rows.none { it.id == "concurrent-conflict" }, "Older offline edit must not resurrect deletion")
                                assertTrue(rows.any { it.id == "offline-survivor" && it.balance == 42.0 })
                            }
                            passed("$mode THREE CLIENTS: concurrent conflicting writes and receipts converged; offline edits, deletion, compaction and worker restart converged ($retries bounded retries)")
                            if (mode == BackupEncryption.TRUSTED_CLOUD) {
                                checkCloudMcp(apis[i], healthPort, restart = {
                                    connections.forEach { it.transport.close() }
                                    worker!!.destroyForcibly(); assertTrue(worker!!.waitFor(10, TimeUnit.SECONDS))
                                    worker = launch(workerJar, workerEnv, root.resolve("worker.log"))
                                    waitReady("http://127.0.0.1:$healthPort/health", worker!!)
                                    connections.clear(); repeat(3) { connections += connect(household) }
                                }, assertCatchUp = {
                                    repeat(3) { syncTogether() }
                                    devices.forEach { device ->
                                        assertEquals(1, device.db.postingQueries.selectAll("__local__").awaitAsList().count { it.title == "Remote MCP transaction" })
                                    }
                                })
                                passed("CLOUD MCP: reader/write grants, consent, origin/session isolation, concurrent idempotent write, restart retry, immediate revocation and WebRTC catch-up on three clients passed")
                            }

                        } finally { connections.forEach { it.transport.close() }; devices.forEach { it.close() } }

                    } finally { peer.transport.close(); source.close(); target.close() }
                }
                System.getenv("SHILLING_TEST_BROWSER_SCRIPT")?.let { script ->
                    val browserLog = root.resolve("browser.log")
                    val browser = ProcessBuilder("node", script).apply {
                        environment().keys.filter { it.startsWith("SHILLING_") }.toList().forEach { environment().remove(it) }
                        environment()["SHILLING_TEST_WEB_URL"] = requireNotNull(System.getenv("SHILLING_TEST_WEB_URL"))
                        environment()["SHILLING_TEST_SERVER_URL"] = url
                        environment()["SHILLING_TEST_BROWSER_ARTIFACTS"] = root.resolve("browser").toString()
                        redirectErrorStream(true); redirectOutput(browserLog.toFile())
                    }.start()
                    try {
                        assertTrue(withContext(Dispatchers.IO) { browser.waitFor(360, TimeUnit.SECONDS) }, "Browser test timed out; see $browserLog")
                        assertEquals(0, browser.exitValue(), "Browser test failed; see $browserLog")
                        passed("HEADLESS WEB: three isolated production clients passed both encryption modes over cloud-only TURN; see $browserLog")
                    } finally { if (browser.isAlive) { browser.destroyForcibly(); browser.waitFor(10, TimeUnit.SECONDS) } }
                }
            }
        } finally {
            withContext(NonCancellable) { transports.forEach { it.close() } }
            scope.cancel(); rtc?.close(); http.close()
            worker?.let { it.destroy(); if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly() }
            server.destroy(); if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly()
            Files.writeString(root.resolve("report.txt"), report.joinToString("\n"))
            println("LIVE BACKUP artifacts: $root")
        }
    }
    private suspend fun checkCloudMcp(api: ServerApi, healthPort: Int, restart: suspend () -> Unit, assertCatchUp: suspend () -> Unit) {
        val readerToken = "1".repeat(64)
        val writerToken = "2".repeat(64)
        val expiry = System.currentTimeMillis() + 3600_000
        val grants = listOf(CloudMcpGrant("reader", sha256(readerToken), true, false, expiry),
            CloudMcpGrant("writer", sha256(writerToken), true, true, expiry))
        var mcpPolicy = api.backupPolicy()
        mcpPolicy = api.updateBackupPolicy(BackupPolicyUpdate(true, true, mcpPolicy.revision, BackupEncryption.TRUSTED_CLOUD,
            mcpClients = grants, mcpConsent = CLOUD_MCP_CONSENT))
        val client = HttpClient.newHttpClient()
        suspend fun mcp(token: String, body: String, session: String? = null, origin: String? = null): HttpResponse<String> = withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$healthPort/mcp"))
                .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
            session?.let { builder.header("Mcp-Session-Id", it) }
            origin?.let { builder.header("Origin", it) }
            client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        }
        val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        suspend fun initialize(token: String): String = withTimeout(35_000) {
            var response = mcp(token, initialize)
            while (response.statusCode() != 200) { delay(250); response = mcp(token, initialize) }
            response.headers().firstValue("Mcp-Session-Id").orElseThrow()
        }
        val readerSession = initialize(readerToken)
        val writerSession = initialize(writerToken)
        val write = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"record_transaction","arguments":{"title":"Remote MCP transaction","amount":12.34,"type":"EXPENSE","accountId":"concurrent-own-0","date":"2026-10-01"}}}"""
        assertTrue("-32003" in mcp(readerToken, write, readerSession).body())
        assertEquals(403, mcp(writerToken, write, writerSession, "https://attacker.example").statusCode())
        assertEquals(404, mcp(readerToken, write, writerSession).statusCode())
        val results = coroutineScope { List(2) { async { mcp(writerToken, write, writerSession) } }.awaitAll() }
        results.forEach { assertEquals(200, it.statusCode(), it.body()); assertFalse("\"error\":" in it.body(), it.body()); assertFalse("\"isError\":true" in it.body(), it.body()) }
        assertEquals(results[0].body(), results[1].body())
        restart()
        assertEquals(results[0].body(), mcp(writerToken, write, writerSession).body())
        assertCatchUp()
        mcpPolicy = api.updateBackupPolicy(BackupPolicyUpdate(true, true, mcpPolicy.revision, BackupEncryption.TRUSTED_CLOUD,
            mcpClients = emptyList()))
        assertEquals(403, mcp(writerToken, write, writerSession).statusCode())
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
    private fun launch(jar: String, variables: Map<String, String>, log: Path): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar
    ).apply {
        environment().keys.filter { it.startsWith("SHILLING_") }.toList().forEach { environment().remove(it) }
        environment().putAll(variables)
        redirectErrorStream(true); redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
    }.start()
    private suspend fun waitReady(url: String, process: Process) = withTimeout(30_000) {
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
        while (true) {
            check(process.isAlive) { "Process exited with ${process.exitValue()} while waiting for $url" }
            val status = withContext(Dispatchers.IO) { runCatching { client.send(HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrNull() }
            if (status == 200) break
            delay(250)
        }
    }
    private fun objectPath(root: Path, household: String): Path = root.resolve("objects").resolve(
        MessageDigest.getInstance("SHA-256").digest(household.toByteArray()).joinToString("") { "%02x".format(it) } + ".sqlite")
    private fun readChunk(path: Path, id: String): String = storageConnection(path).use { db ->
        db.prepareStatement("SELECT ciphertext FROM $chunkTable WHERE snapshot=? AND idx=0").use { it.setString(1, id); it.executeQuery().use { r -> check(r.next()); Base64.getEncoder().encodeToString(r.getBytes(1)) } }
    }
    private fun writeChunk(path: Path, id: String, chunk: String) = storageConnection(path).use { db ->
        db.prepareStatement("UPDATE $chunkTable SET ciphertext=? WHERE snapshot=? AND idx=0").use { it.setBytes(1, Base64.getDecoder().decode(chunk)); it.setString(2, id); assertEquals(1, it.executeUpdate()) }
    }
    private fun decryptAtRest(key: ByteArray, household: String, info: BackupInfo, stored: String): ByteArray {
        val bytes = Base64.getDecoder().decode(stored)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD("cloud|$household|${info.id}|${info.chunks}|0".toByteArray())
        }.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    private class Budget {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = ShillingDatabase(driver)
        val notifier = ChangeNotifier()
        val files = ReceiptFiles()
        suspend fun initialize() { ShillingDatabase.Schema.create(driver).await() }
        fun close() = driver.close()
    }
    private class MemoryVault : BackupKeyVault {
        private val data = mutableMapOf<String, String>()
        override suspend fun read(namespace: String) = data[namespace]
        override suspend fun write(namespace: String, secret: String) { data[namespace] = secret }
        override suspend fun delete(namespace: String) { data.remove(namespace) }
    }
    private class ReceiptFiles : ReceiptFileStore {
        private val data = mutableMapOf<String, ByteArray>()
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) { data[receiptId] = bytes.copyOf() }
        override suspend fun read(receiptId: String) = data[receiptId]
        override suspend fun openReader(receiptId: String): finance.shilling.shared.data.ReceiptFileReader? {
            val bytes = read(receiptId)?.copyOf() ?: return null
            return object : finance.shilling.shared.data.ReceiptFileReader {
                override val size = bytes.size.toLong()
                override suspend fun readRange(offset: Long, byteCount: Int) =
                    finance.shilling.shared.data.ReceiptFileBlock(bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + byteCount)))
            }
        }
        override suspend fun hasFile(receiptId: String) = receiptId in data
        override suspend fun delete(receiptId: String) { data.remove(receiptId) }
        override suspend fun clearAll() { data.clear() }
        override suspend fun openExternally(receiptId: String, originalName: String) = Unit
    }
}
