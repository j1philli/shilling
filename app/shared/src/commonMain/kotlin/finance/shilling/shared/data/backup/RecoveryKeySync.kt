package finance.shilling.shared.data.backup

import finance.shilling.shared.data.store.backup.RecoveryKeyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
private data class Invitation(val household: String, val source: String, val id: String, val secret: String, val expires: Long)
@Serializable
private data class KeyFrame(val kind: String, val id: String, val ciphertext: String)
@Serializable
private data class Delivery(val key: String, val fingerprint: String)

enum class KeySyncStatus { PENDING, SYNCED, FAILED }
data class KeyApproval(val peerId: String)

/** Possession of an invitation plus explicit approval establishes a pairwise encrypted channel. */
class RecoveryKeySync(
    private val household: String,
    private val deviceId: String,
    private val keys: RecoveryKeyRepository,
    private val send: suspend (String, String) -> Unit
) {
    private val crypto = BackupCrypto()
    private val statuses = MutableStateFlow<Map<String, KeySyncStatus>>(emptyMap())
    val status = statuses.asStateFlow()
    private val approvals = MutableStateFlow<List<KeyApproval>>(emptyList())
    val pendingApprovals = approvals.asStateFlow()
    private val invitations = mutableMapOf<String, Invitation>()
    private val requests = mutableMapOf<String, Pair<String, String>>()
    private val awaiting = mutableMapOf<String, Pair<String, String>>()
    private var joining: Invitation? = null

    private fun context(kind: String, sender: String, recipient: String, id: String) =
        Json.encodeToString(listOf("shilling.recovery.v1", household, kind, sender, recipient, id))

    suspend fun invitation(): String {
        require(keys.activeKey() != null) { "Create or import your recovery key first." }
        invitations.clear()
        val value = Invitation(household, deviceId, Uuid.random().toString(), crypto.newKey(), Clock.System.now().toEpochMilliseconds() + 600_000)
        invitations[value.id] = value
        return Base64.UrlSafe.encode(Json.encodeToString(value).encodeToByteArray())
    }
    suspend fun join(code: String) {
        require(code.length <= 2048)
        val invite = Json.decodeFromString<Invitation>(Base64.UrlSafe.decode(code.trim()).decodeToString())
        require(invite.household == household && invite.source != deviceId && invite.expires > Clock.System.now().toEpochMilliseconds()) { "Invitation expired or belongs to a different household." }
        crypto.validateKey(invite.secret)
        joining = invite
        statuses.value += invite.source to KeySyncStatus.PENDING
    }
    suspend fun approve(peer: String) {
        val (id, secret) = requests.remove(peer) ?: error("Pairing request expired")
        val invite = invitations.remove(id) ?: error("Pairing invitation expired")
        require(invite.expires > Clock.System.now().toEpochMilliseconds())
        keys.approve(peer, secret)
        approvals.value = approvals.value.filter { it.peerId != peer }
        statuses.value += peer to KeySyncStatus.PENDING
    }
    suspend fun revoke(peer: String) {
        keys.revoke(peer)
        requests.remove(peer); awaiting.remove(peer)
        approvals.value = approvals.value.filter { it.peerId != peer }
        statuses.value -= peer
    }
    suspend fun tick() {
        joining?.let { invite ->
            if (invite.expires <= Clock.System.now().toEpochMilliseconds()) { statuses.value += invite.source to KeySyncStatus.FAILED; joining = null }
            else attempt(invite.source) {
                val body = crypto.encrypt(invite.secret, context("request", deviceId, invite.source, invite.id), deviceId.encodeToByteArray())
                send(invite.source, Json.encodeToString(KeyFrame("request", invite.id, body)))
            }
        }
        val key = keys.activeKey() ?: return
        val fingerprint = crypto.fingerprint(key)
        for (peer in keys.peers()) {
            if (statuses.value[peer] == KeySyncStatus.SYNCED && awaiting[peer]?.second == fingerprint) continue
            statuses.value += peer to KeySyncStatus.PENDING
            attempt(peer) {
                val secret = keys.peerSecret(peer) ?: return@attempt
                val id = awaiting[peer]?.takeIf { it.second == fingerprint }?.first ?: Uuid.random().toString()
                awaiting[peer] = id to fingerprint
                val body = crypto.encrypt(secret, context("delivery", deviceId, peer, id), Json.encodeToString(Delivery(key, fingerprint)).encodeToByteArray())
                send(peer, Json.encodeToString(KeyFrame("delivery", id, body)))
            }
        }
    }
    private suspend fun attempt(peer: String, block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { statuses.value += peer to KeySyncStatus.PENDING }
    }
    suspend fun receive(peer: String, text: String): String? {
        require(text.length <= 4096)
        val frame = Json.decodeFromString<KeyFrame>(text)
        when (frame.kind) {
            "request" -> {
                val invite = invitations[frame.id] ?: return null
                if (invite.expires <= Clock.System.now().toEpochMilliseconds()) return null
                val proof = crypto.decrypt(invite.secret, context("request", peer, deviceId, frame.id), frame.ciphertext).decodeToString()
                require(proof == peer)
                if (requests.size >= 16 && peer !in requests) return null
                requests[peer] = frame.id to invite.secret
                approvals.value = requests.keys.map(::KeyApproval)
            }
            "delivery" -> {
                val invite = joining?.takeIf { it.source == peer && it.expires > Clock.System.now().toEpochMilliseconds() }
                val secret = invite?.secret ?: keys.peerSecret(peer) ?: return null
                val content = crypto.decrypt(secret, context("delivery", peer, deviceId, frame.id), frame.ciphertext)
                val delivery = Json.decodeFromString<Delivery>(content.decodeToString())
                require(crypto.fingerprint(delivery.key) == delivery.fingerprint)
                keys.importKey(delivery.key)
                keys.approve(peer, secret)
                joining = null
                val ack = crypto.encrypt(secret, context("ack", deviceId, peer, frame.id), delivery.fingerprint.encodeToByteArray())
                statuses.value += peer to KeySyncStatus.SYNCED
                return Json.encodeToString(KeyFrame("ack", frame.id, ack))
            }
            "ack" -> {
                val secret = keys.peerSecret(peer) ?: return null
                val expected = awaiting[peer] ?: return null
                if (expected.first != frame.id) return null
                val received = crypto.decrypt(secret, context("ack", peer, deviceId, frame.id), frame.ciphertext).decodeToString()
                require(received == expected.second)
                statuses.value += peer to KeySyncStatus.SYNCED
            }
        }
        return null
    }
}
