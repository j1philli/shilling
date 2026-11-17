@file:OptIn(io.ktor.utils.io.ExperimentalKtorApi::class)
package finance.shilling.server

import finance.shilling.core.backup.*
import finance.shilling.shared.data.backup.*
import finance.shilling.shared.data.store.backup.EncryptedBackupRepository
import finance.shilling.shared.data.sync.SignalingClient
import finance.shilling.shared.data.sync.createSyncHttpClient
import io.ktor.client.webrtc.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import io.ktor.server.netty.Netty
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import finance.shilling.shared.data.store.backup.RecoveryKeyRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*
import kotlin.uuid.Uuid

class BackupWebRtcTest {
    @Test fun roundTripThroughRealJvmWebRtcWithManyHouseholdsAndBothEncryptionModes(): Unit = runBlocking { roundTrip(false) }
    @Test fun forcedTurnRelayBackupAndRecoveryKeySync(): Unit = runBlocking {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("SHILLING_TEST_TURN_URL") != null, "Set SHILLING_TEST_TURN_URL to run relay integration")
        roundTrip(true)
    }
    private suspend fun roundTrip(forceRelay: Boolean) {
        val root = Files.createTempDirectory("backup-webrtc")
        val secret = "integration-test-worker-credential-32"
        val policy = BackupPolicyStore(root.resolve("policy.json"), listOf("worker"), true)
        val households = List(2) { Uuid.random().toString() }
        policy.update(households[0], BackupPolicyUpdate(true, true, 0), true)
        policy.update(households[1], BackupPolicyUpdate(true, true, 0, BackupEncryption.TRUSTED_CLOUD, TRUSTED_CLOUD_CONSENT), true)
        val control = BackupControl(policy, mapOf("worker" to secret), true)
        val keyStorePath = root.resolve("test-tls.p12")
        val password = "test-fixture-only".toCharArray()
        val keytool = ProcessBuilder("keytool", "-genkeypair", "-alias", "test", "-keyalg", "RSA",
            "-keystore", keyStorePath.toString(), "-storetype", "PKCS12", "-storepass", String(password),
            "-keypass", String(password), "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "1")
            .redirectErrorStream(true).redirectOutput(root.resolve("keytool.log").toFile()).start()
        check(keytool.waitFor() == 0) { "Could not create test TLS certificate" }
        val keyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keyStorePath).use { load(it, password) } }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        val server = embeddedServer(Netty, configure = {
            sslConnector(keyStore, "test", { password }, { password }) { host = "127.0.0.1"; port = 0 }
        }) {
            install(WebSockets)
            routing { backupRoutes(control, null, null); signalingRoute(SignalingHub(), path = "/ws/recovery-keys") }
        }.start()
        val url = "https://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        val http = HttpClient(CIO) {
            install(ClientWebSockets)
            engine { https { trustManager = trust.trustManagers.filterIsInstance<X509TrustManager>().single() } }
        }
        val rtc = WebRtcClient(JvmWebRtc) {
            if (forceRelay) defaultConnectionConfig = {
                iceServers = listOf(WebRtc.IceServer(System.getenv("SHILLING_TEST_TURN_URL"), "backup-test", "backup-test-password"))
                iceTransportPolicy = WebRtc.IceTransportPolicy.RELAY
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transports = mutableListOf<BackupRtcTransport>()
        try {
            withTimeout(90_000) {
                for ((index, household) in households.withIndex()) {
                    val mode = if (index == 0) BackupEncryption.END_TO_END else BackupEncryption.TRUSTED_CLOUD
                    val store = EncryptedBackupRepository(root, household, ByteArray(32) { 7 }) { mode }
                    val workerSignal = SignalingClient(http, url, BACKUP_WORKER_ID, household, path = "/ws/backup", accessToken = { secret })
                    val worker = BackupRtcTransport(rtc, workerSignal, BACKUP_WORKER_ID, scope, offerTo = { false }) { _, text ->
                        Json.encodeToString(store.handle(Json.decodeFromString<BackupRequest>(text)))
                    }
                    transports += worker
                    worker.start()
                    val clientId = Uuid.random().toString()
                    val clientSignal = SignalingClient(http, url, clientId, household, path = "/ws/backup")
                    val peer = BackupPeerClient()
                    val client = BackupRtcTransport(rtc, clientSignal, clientId, scope, offerTo = { it == BACKUP_WORKER_ID }, onMessage = peer::receive)
                    peer.transport = client; transports += client
                    client.start()
                    val key = if (mode == BackupEncryption.END_TO_END) BackupCrypto().newKey() else null
                    val bytes = ByteArray(70_000) { (it % 251).toByte() }
                    val id = Uuid.random().toString()
                    val parts = bytes.asList().chunked(BACKUP_CHUNK_BYTES).map { it.toByteArray() }
                    val crypto = BackupCrypto()
                    peer.request(BackupRequest.Operation.BEGIN, id, chunks = parts.size, encryption = mode, keyId = key?.let { crypto.fingerprint(it) } ?: "")
                    parts.forEachIndexed { index, part ->
                        val context = finance.shilling.shared.data.store.cloud.cloudContext(household, id, parts.size, index)
                        val chunk = if (key == null) kotlin.io.encoding.Base64.encode(part) else crypto.encrypt(key, context, part)
                        peer.request(BackupRequest.Operation.PUT, id, index, chunk = chunk, encryption = mode)
                    }
                    peer.request(BackupRequest.Operation.COMMIT, id, encryption = mode)
                    val info = peer.request(BackupRequest.Operation.LIST).backups.single()
                    assertEquals(mode, info.encryption)
                    assertContentEquals(bytes, downloadCloud(household, key, info, peer))
                    client.close()
                    // Committed objects survive a new connection, with no application payload on signaling.
                }
                val vault = object : BackupKeyVault {
                    val values = mutableMapOf<String, String>()
                    override suspend fun read(namespace: String) = values[namespace]
                    override suspend fun write(namespace: String, secret: String) { values[namespace] = secret }
                    override suspend fun delete(namespace: String) { values.remove(namespace) }
                }
                val sourceKeys = RecoveryKeyRepository(vault, "source")
                val targetKeys = RecoveryKeyRepository(vault, "target")
                val recovery = sourceKeys.generate()
                lateinit var sourceTransport: BackupRtcTransport
                lateinit var targetTransport: BackupRtcTransport
                val sourceSync = RecoveryKeySync(households[0], "device-source", sourceKeys) { peer, text -> sourceTransport.send(peer, text) }
                val targetSync = RecoveryKeySync(households[0], "device-target", targetKeys) { peer, text -> targetTransport.send(peer, text) }
                sourceTransport = BackupRtcTransport(rtc, SignalingClient(http, url, "device-source", households[0], path = "/ws/recovery-keys"), "device-source", scope, offerTo = { true }, label = "keys", onMessage = sourceSync::receive)
                targetTransport = BackupRtcTransport(rtc, SignalingClient(http, url, "device-target", households[0], path = "/ws/recovery-keys"), "device-target", scope, offerTo = { false }, label = "keys", onMessage = targetSync::receive)
                transports += sourceTransport; transports += targetTransport
                sourceTransport.start(); targetTransport.start()
                sourceTransport.awaitPeer("device-target"); targetTransport.awaitPeer("device-source")
                targetSync.join(sourceSync.invitation()); targetSync.tick()
                withTimeout(10_000) { sourceSync.pendingApprovals.first { it.isNotEmpty() } }
                sourceSync.approve("device-target"); sourceSync.tick()
                withTimeout(10_000) { sourceSync.status.first { it["device-target"] == KeySyncStatus.SYNCED } }
                assertEquals(recovery, targetKeys.activeKey())
            }
        } finally {
            withContext(NonCancellable) { transports.forEach { it.close() } }
            scope.cancel(); rtc.close(); http.close(); server.stop(0, 1000)
        }
    }
}
