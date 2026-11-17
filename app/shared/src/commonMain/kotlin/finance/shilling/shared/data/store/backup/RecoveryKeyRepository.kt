package finance.shilling.shared.data.store.backup

import finance.shilling.shared.data.backup.BackupCrypto
import finance.shilling.shared.data.backup.BackupKeyVault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class RecoverySecrets(
    val active: String? = null,
    val keys: Map<String, String> = emptyMap(),
    val peers: Map<String, String> = emptyMap(),
    val saved: Boolean = false,
    val cloudConsentRevision: Long? = null
)

/** Serialized read/modify/write of the platform vault. A successful write is a durable receipt. */
class RecoveryKeyRepository(private val vault: BackupKeyVault, private val namespace: String) {
    private val mutex = Mutex()
    private val crypto = BackupCrypto()
    private suspend fun read(): RecoverySecrets = vault.read(namespace)?.let { Json.decodeFromString(it) } ?: RecoverySecrets()
    private suspend fun save(value: RecoverySecrets) { vault.write(namespace, Json.encodeToString(value)) }
    suspend fun cloudConsentRevision(): Long? = mutex.withLock { read().cloudConsentRevision }
    suspend fun consentToTrustedCloud(revision: Long?) = mutex.withLock { val state = read(); save(state.copy(cloudConsentRevision = revision)) }
    suspend fun activeKey(): String? = mutex.withLock { read().let { it.keys[it.active] } }
    suspend fun key(id: String): String? = mutex.withLock { read().keys[id] }
    suspend fun saved(): Boolean = mutex.withLock { read().saved }
    suspend fun confirmSaved() = mutex.withLock { val state = read(); require(state.active != null); save(state.copy(saved = true)) }
    suspend fun importKey(key: String, saved: Boolean = false) = mutex.withLock {
        crypto.validateKey(key)
        val clean = key.trim()
        val id = crypto.fingerprint(clean)
        val current = read()
        require(current.keys.size < 32 || id in current.keys)
        save(current.copy(active = if (saved) id else current.active ?: id, keys = current.keys + (id to clean), saved = current.saved || saved))
    }
    suspend fun generate(): String = mutex.withLock {
        val state = read()
        state.keys[state.active] ?: crypto.newKey().also { key ->
            val id = crypto.fingerprint(key)
            save(state.copy(active = id, keys = state.keys + (id to key), saved = false))
        }
    }
    suspend fun peers(): Set<String> = mutex.withLock { read().peers.keys }
    suspend fun peerSecret(peer: String): String? = mutex.withLock { read().peers[peer] }
    suspend fun approve(peer: String, secret: String) = mutex.withLock {
        crypto.validateKey(secret)
        val state = read()
        require(state.peers.size < 16 || peer in state.peers)
        save(state.copy(peers = state.peers + (peer to secret)))
    }
    suspend fun revoke(peer: String) = mutex.withLock { val state = read(); save(state.copy(peers = state.peers - peer)) }
}
