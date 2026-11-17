package finance.shilling.server

import finance.shilling.core.backup.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface BackupPolicies : AutoCloseable {
    fun get(household: String, available: Boolean): BackupPolicy
    fun update(household: String, request: BackupPolicyUpdate, available: Boolean, actor: String? = null): BackupPolicy
    fun assignments(workerId: String): List<BackupAssignment>
    fun assigned(householdId: String, workerId: String): Boolean
    override fun close() = Unit
}

/** Control-plane metadata only. One writer owns this file; no budget contents or keys. */
class BackupPolicyStore(private val path: Path, private val workerIds: List<String>, private val trustedCloudAvailable: Boolean = false, private val maxHouseholdsPerWorker: Int = 256) : BackupPolicies {
    private val json = Json { ignoreUnknownKeys = true }
    private var policies: Map<String, BackupPolicy> = if (Files.exists(path)) {
        json.decodeFromString(Files.readString(path))
    } else emptyMap()

    init { require(maxHouseholdsPerWorker > 0) }
    private fun activeCount(worker: String) = policies.values.count { it.enabled && it.workerId == worker }
    private fun availableWorker(): String? = workerIds.filter { activeCount(it) < maxHouseholdsPerWorker }.minByOrNull(::activeCount)

    @Synchronized override fun get(household: String, available: Boolean): BackupPolicy {
        val current = policies[household] ?: BackupPolicy()
        val worker = current.workerId ?: availableWorker()
        val capacity = worker != null && worker in workerIds && (current.enabled || activeCount(worker) < maxHouseholdsPerWorker)
        return current.copy(available = available && capacity, trustedCloudAvailable = trustedCloudAvailable)
    }

    @Synchronized override fun update(household: String, request: BackupPolicyUpdate, available: Boolean, actor: String?): BackupPolicy {
        val current = get(household, available)
        require(current.revision == request.revision) { "Settings changed on another device. Refresh and try again." }
        require(!request.enabled || current.available) { "Cloud backup requires a configured worker, TURN, and a paid plan." }
        require(!current.enabled || request.turnEnabled || !request.enabled) { "Disable cloud backup before turning off TURN." }
        require(request.encryption != BackupEncryption.TRUSTED_CLOUD || (!request.enabled && current.encryption == request.encryption) || trustedCloudAvailable) { "Trusted cloud storage is unavailable." }
        require(request.encryption != BackupEncryption.TRUSTED_CLOUD || current.encryption == request.encryption || request.consent == TRUSTED_CLOUD_CONSENT) { "Explicit consent to service access is required." }
        val next = BackupPolicy(
            enabled = request.enabled,
            turnEnabled = if (request.enabled && !current.enabled) true else request.turnEnabled,
            available = available,
            workerId = current.workerId ?: availableWorker(),
            revision = current.revision + 1,
            encryption = request.encryption,
            recoveryKeyRequired = current.recoveryKeyRequired || (request.enabled && request.encryption == BackupEncryption.END_TO_END),
            trustedCloudAvailable = trustedCloudAvailable,
            encryptionRevision = if (request.encryption != current.encryption) current.revision + 1 else current.encryptionRevision,
            trustedConsentBy = if (request.consent == TRUSTED_CLOUD_CONSENT) actor else current.trustedConsentBy,
            mcpClients = cloudMcpGrants(current, request, actor, System.currentTimeMillis())
        )
        require(!next.enabled || next.turnEnabled) { "TURN is required for cloud backup." }
        val updated = policies + (household to next.copy(available = false))
        Files.createDirectories(path.toAbsolutePath().parent)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, "backup-policy-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(updated))
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            policies = updated
        } finally { Files.deleteIfExists(temporary) }
        return next
    }

    @Synchronized override fun assignments(workerId: String): List<BackupAssignment> = policies
        .filterValues { it.workerId == workerId && it.enabled }
        .map { (household, policy) -> BackupAssignment(household, policy.encryption, policy.mcpClients) }

    @Synchronized override fun assigned(household: String, workerId: String): Boolean =
        policies[household]?.let { it.enabled && it.workerId == workerId } == true
}
