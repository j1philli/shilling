@file:OptIn(io.ktor.utils.io.ExperimentalKtorApi::class)
package finance.shilling.shared.data.backup

import finance.shilling.core.backup.*
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.store.cloud.CloudSyncRepository
import finance.shilling.shared.data.store.backup.RecoveryKeyRepository
import finance.shilling.shared.data.sync.ServerApi
import finance.shilling.shared.data.sync.SignalingClient
import io.ktor.client.HttpClient
import io.ktor.client.webrtc.WebRtc
import io.ktor.client.webrtc.WebRtcClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Session-scoped controller; replacing account/server/household disposes every connection. */
class BackupController(
    private val http: HttpClient,
    private val serverUrl: String,
    private val household: String,
    val deviceId: String,
    private val auth: AuthService,
    vault: BackupKeyVault,
    private val createRtc: (() -> List<WebRtc.IceServer>) -> WebRtcClient,
    parent: CoroutineScope,
    private val cloudRepository: CloudSyncRepository
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val api = ServerApi(http, serverUrl, auth, household)
    private val keys = RecoveryKeyRepository(vault, "$serverUrl|$household")
    private val currentPolicy = MutableStateFlow<BackupPolicy?>(null)
    val policy = currentPolicy.asStateFlow()
    private val currentStatus = MutableStateFlow("Checking cloud sync settings…")
    val status = currentStatus.asStateFlow()
    private val busyState = MutableStateFlow(false)
    val busy = busyState.asStateFlow()
    private val keyPresent = MutableStateFlow(false)
    val hasKey = keyPresent.asStateFlow()
    private val keySaved = MutableStateFlow(false)
    val savedKey = keySaved.asStateFlow()
    private val trustedConsentState = MutableStateFlow(false)
    val trustedConsent = trustedConsentState.asStateFlow()
    private val operation = Mutex()
    private var cloudPeer: BackupPeerClient? = null
    private var cloudRtc: WebRtcClient? = null
    private val cloudSession = CloudSyncSession(cloudRepository, keys, "$serverUrl|$household", household, deviceId)
    private var keyTransport: BackupRtcTransport? = null
    private var keyRtc: WebRtcClient? = null
    private var ice: List<WebRtc.IceServer> = emptyList()
    val keySync = RecoveryKeySync(household, deviceId, keys) { peer, text ->
        keyTransport?.send(peer, text) ?: error("Key sync is not connected")
    }

    fun start() {
        scope.launch {
            while (isActive) {
                delay(5_000)
                val current = currentPolicy.value ?: continue
                if (current.enabled && current.available && !busy.value) syncNow()
                else if (!current.enabled) closeCloudPeer()
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    refresh()
                    val response = api.fetchIceServers(allowRelay = true)
                    ice = response.webRtcServers()
                    if (currentPolicy.value?.enabled == true) require(response.iceServers.any { cfg ->
                        cfg.credential != null && cfg.urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
                    }) { "TURN is unavailable. Recovery-key sync may be delayed." }
                    if (keyTransport == null) {
                        val rtc = createRtc { ice }
                        keyRtc = rtc
                        val signal = SignalingClient(http, serverUrl, deviceId, household, auth, path = "/ws/recovery-keys")
                        keyTransport = BackupRtcTransport(rtc, signal, deviceId, scope, offerTo = { deviceId < it }, label = "shilling-recovery-v1", onMessage = keySync::receive).also { it.start() }
                    }
                    keyTransport?.reconnectIfIdle()
                    keyTransport?.ping()
                    keySync.tick()
                    refreshKeys()
                    if (!busy.value && currentStatus.value.startsWith("Cloud connection unavailable")) currentStatus.value = "Cloud connection restored."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (!busy.value) currentStatus.value = "Cloud connection unavailable. Key sync will retry; keep your saved recovery key." }
                delay(15_000)
            }
        }
    }
    private suspend fun closeCloudPeer() {
        withContext(NonCancellable) { cloudPeer?.transport?.close(); cloudRtc?.close() }
        cloudPeer = null; cloudRtc = null
    }
    private suspend fun connectedCloudPeer(): BackupPeerClient {
        cloudPeer?.let { it.transport.reconnectIfIdle(); return it }
        val config = api.fetchIceServers(allowRelay = true)
        require(config.iceServers.any { it.credential != null && it.urls.any { url -> url.startsWith("turn:") || url.startsWith("turns:") } }) { "TURN is unavailable. Cloud sync will retry." }
        ice = config.webRtcServers()
        val rtc = createRtc { ice }; cloudRtc = rtc
        val signal = SignalingClient(http, serverUrl, deviceId, household, auth, path = "/ws/backup")
        val peer = BackupPeerClient()
        peer.transport = BackupRtcTransport(rtc, signal, deviceId, scope, offerTo = { it == BACKUP_WORKER_ID }, maxConnections = 1, onMessage = peer::receive)
        cloudPeer = peer; peer.transport.start()
        return peer
    }
    suspend fun syncNow() = perform {
        refresh()
        try {
            val caughtUp = cloudSession.sync(requireNotNull(currentPolicy.value), connectedCloudPeer())
            currentStatus.value = if (caughtUp) "Cloud sync up to date." else "Syncing remaining cloud changes…"
        } catch (e: Exception) { closeCloudPeer(); throw e }
    }
    suspend fun refresh() {
        val updated = api.backupPolicy()
        if (updated.encryption == BackupEncryption.END_TO_END && keys.cloudConsentRevision() != null) keys.consentToTrustedCloud(null)
        // Publish policy only after local key state is ready. Otherwise an enabled
        // policy can open recovery setup during startup even though the vault has a key.
        refreshKeys()
        trustedConsentState.value = keys.cloudConsentRevision() == updated.encryptionRevision
        currentPolicy.value = updated
        if (currentStatus.value == "Checking cloud sync settings…") currentStatus.value = "Cloud sync settings loaded."
    }
    private suspend fun refreshKeys() { keyPresent.value = keys.activeKey() != null; keySaved.value = keys.saved() }
    suspend fun recoveryKey(): String {
        refresh()
        val existing = keys.activeKey()
        require(existing != null || currentPolicy.value?.recoveryKeyRequired == false) {
            "Connect an existing device or import your saved recovery key."
        }
        return (existing ?: keys.generate()).also { refreshKeys() }
    }
    suspend fun importRecoveryKey(value: String) { keys.importKey(value, saved = true); refreshKeys() }
    suspend fun confirmSavedKey() { keys.confirmSaved(); refreshKeys() }
    suspend fun update(enabled: Boolean, turn: Boolean, encryption: BackupEncryption, consent: String? = null) = perform {
        val current = currentPolicy.value ?: error("Load cloud settings first.")
        if (enabled && encryption == BackupEncryption.END_TO_END) require(keys.activeKey() != null && keys.saved()) { "Save and confirm your recovery key first." }
        currentPolicy.value = api.updateBackupPolicy(BackupPolicyUpdate(enabled, turn, current.revision, encryption, consent))
        if (consent == TRUSTED_CLOUD_CONSENT) keys.consentToTrustedCloud(currentPolicy.value!!.encryptionRevision)
        if (encryption == BackupEncryption.END_TO_END) keys.consentToTrustedCloud(null)
        trustedConsentState.value = keys.cloudConsentRevision() == currentPolicy.value?.encryptionRevision
        currentStatus.value = "Cloud sync settings saved. Previously uploaded data keeps its encryption mode."
    }
    private suspend fun perform(block: suspend () -> Unit) = withContext(scope.coroutineContext) {
        operation.withLock {
            busyState.value = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { currentStatus.value = e.message ?: "Backup settings are invalid." }
            catch (e: IllegalStateException) { currentStatus.value = e.message ?: "Backup operation failed." }
            catch (_: Exception) { currentStatus.value = "Cloud operation failed. Check your connection and refresh settings before retrying." }
            finally { busyState.value = false }
        }
    }
    fun cancel() { scope.cancel() }

    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        withContext(NonCancellable) { closeCloudPeer(); keyTransport?.close(); keyRtc?.close() }
    }
}
