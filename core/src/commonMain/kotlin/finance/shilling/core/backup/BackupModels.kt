package finance.shilling.core.backup

import kotlinx.serialization.Serializable

const val BACKUP_WORKER_ID = "backup-worker"
const val BACKUP_CHUNK_BYTES = 24 * 1024
const val BACKUP_MAX_CHUNKS = 4096
const val BACKUP_MAX_FRAME_CHARS = 48 * 1024

@Serializable
enum class BackupEncryption { END_TO_END, TRUSTED_CLOUD }

const val TRUSTED_CLOUD_CONSENT = "I allow Shilling to access my cloud data"

@Serializable
data class BackupPolicy(
    val enabled: Boolean = false,
    val turnEnabled: Boolean = true,
    val available: Boolean = false,
    val workerId: String? = null,
    val revision: Long = 0,
    val encryption: BackupEncryption = BackupEncryption.END_TO_END,
    val trustedCloudAvailable: Boolean = false,
    val encryptionRevision: Long = 0,
    val recoveryKeyRequired: Boolean = false,
    val trustedConsentBy: String? = null,
    val mcpClients: List<CloudMcpGrant> = emptyList()
)

@Serializable
data class BackupPolicyUpdate(
    val enabled: Boolean, val turnEnabled: Boolean, val revision: Long,
    val encryption: BackupEncryption = BackupEncryption.END_TO_END,
    val consent: String? = null,
    val mcpClients: List<CloudMcpGrant>? = null,
    val mcpConsent: String? = null
)

@Serializable
data class BackupAssignment(val householdId: String, val encryption: BackupEncryption = BackupEncryption.END_TO_END, val mcpClients: List<CloudMcpGrant> = emptyList())

@Serializable enum class CloudObjectKind { DELTA, RECEIPT, CHECKPOINT }

@Serializable
data class BackupInfo(
    val id: String, val chunks: Int, val createdAt: Long,
    val encryption: BackupEncryption = BackupEncryption.END_TO_END,
    val keyId: String = "",
    val sequence: Long = 0,
    val kind: CloudObjectKind = CloudObjectKind.DELTA,
    val references: List<String> = emptyList()
)

/** Cloud sync chunks only. Trusted-cloud chunks travel inside DTLS and are encrypted by the worker before persistence. */
@Serializable
data class BackupRequest(
    val requestId: String,
    val operation: Operation,
    val backupId: String = "",
    val index: Int = 0,
    val chunks: Int = 0,
    val chunk: String = "",
    val encryption: BackupEncryption = BackupEncryption.END_TO_END,
    val keyId: String = "",
    val cursor: Long = 0,
    val kind: CloudObjectKind = CloudObjectKind.DELTA,
    val references: List<String> = emptyList(),
    val baseSequence: Long = 0
) {
    @Serializable enum class Operation { BEGIN, PUT, COMMIT, LIST, GET, STAT, ABORT }
}

@Serializable
data class BackupResponse(
    val requestId: String,
    val error: String? = null,
    val chunk: String? = null,
    val backups: List<BackupInfo> = emptyList(),
    val checkpoint: BackupInfo? = null,
    val headSequence: Long = 0,
    val checkpointSequence: Long = 0
)

fun validBackupId(value: String): Boolean = value.length in 16..64 && value.all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-'
}

const val CLOUD_MCP_CONSENT = "I authorize these clients to access my cloud budget with the selected permissions"

/** Only a token digest is stored. The client keeps its random 256-bit bearer credential. */
@Serializable data class CloudMcpGrant(
    val clientId: String,
    val tokenSha256: String,
    val read: Boolean = false,
    val write: Boolean = false,
    val expiresAt: Long,
    val consentBy: String? = null
)

@Serializable data class CloudMcpAuthorizationRequest(val householdId: String, val tokenSha256: String)

/** Shared policy validation for SQLite/file and hosted Postgres control planes. */
fun cloudMcpGrants(current: BackupPolicy, update: BackupPolicyUpdate, actor: String?, now: Long): List<CloudMcpGrant> {
    if (!update.enabled || update.encryption != BackupEncryption.TRUSTED_CLOUD) {
        require(update.mcpClients.isNullOrEmpty()) { "Cloud MCP requires enabled trusted-cloud storage." }
        return emptyList()
    }
    val requested = update.mcpClients ?: return current.mcpClients
    require(requested.size <= 32 && requested.map { it.clientId }.distinct().size == requested.size &&
        requested.map { it.tokenSha256 }.distinct().size == requested.size) { "Invalid or duplicate MCP clients." }
    val previous = current.mcpClients.associateBy { it.clientId }
    return requested.map { grant ->
        require(grant.clientId.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && grant.tokenSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid MCP client credential." }
        require(grant.read && (!grant.write || grant.read)) { "Editing tools also require read permission." }
        val old = previous[grant.clientId]
        if (old != null && grant.copy(consentBy = old.consentBy) == old) old
        else {
            require(update.mcpConsent == CLOUD_MCP_CONSENT && !actor.isNullOrBlank()) { "Explicit consent for MCP clients is required." }
            require(grant.expiresAt > now && grant.expiresAt - now <= 90L * 24 * 60 * 60 * 1000) { "MCP grants expire within 90 days." }
            grant.copy(consentBy = actor)
        }
    }
}
