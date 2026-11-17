@file:OptIn(org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)
package finance.shilling.shared.data.store.backup

import finance.shilling.core.backup.*
import finance.shilling.shared.data.store.readLocalSourceOfTruth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mobilenativefoundation.store.store5.*
import java.sql.Connection

/** Hosted control-plane metadata only; this role has no payload-table or object-storage access. */
class CloudPolicyRepository(private val database: BackupDatabase, private val workers: List<String>,
    private val trustedAvailable: Boolean, private val capacity: Int) : AutoCloseable {
    init { require(workers.distinct().size == workers.size && capacity > 0) }
    private fun store(db: Connection) = MutableStoreBuilder.from<String, List<BackupPolicy>, List<BackupPolicy>, List<BackupPolicy>>(
        fetcher = Fetcher.of { _: String -> error("Policies use Postgres source of truth") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { household: String -> flowOf(db.prepareStatement("SELECT policy FROM private_backup.cloud_policies WHERE household=?").use {
                it.setString(1, household); it.executeQuery().use { r -> if (r.next()) listOf(Json.decodeFromString<BackupPolicy>(r.getString(1))) else emptyList() }
            }) },
            writer = { household, values ->
                val policy = values.single()
                db.prepareStatement("INSERT INTO private_backup.cloud_policies(household,worker,enabled,policy) VALUES(?,?,?,?) ON CONFLICT(household) DO UPDATE SET worker=excluded.worker,enabled=excluded.enabled,policy=excluded.policy").use {
                    it.setString(1, household); it.setString(2, policy.workerId); it.setBoolean(3, policy.enabled); it.setString(4, Json.encodeToString(policy)); it.executeUpdate(); Unit
                }
            }
        ), converter = Converter.Builder<List<BackupPolicy>, List<BackupPolicy>, List<BackupPolicy>>().fromNetworkToLocal { it }.fromOutputToLocal { it }.build()
    ).disableCache().build(updater = Updater.by<String, List<BackupPolicy>, Unit>(post = { _, _ -> UpdaterResult.Success.Typed(Unit) }))
    private fun counts(db: Connection): Map<String, Int> = db.createStatement().use { s ->
        s.executeQuery("SELECT worker,count(*) FROM private_backup.cloud_policies WHERE enabled=true GROUP BY worker").use { r -> buildMap { while(r.next()) put(r.getString(1), r.getInt(2)) } }
    }
    private fun decorate(current: BackupPolicy, available: Boolean, counts: Map<String, Int>): BackupPolicy {
        val worker = current.workerId ?: workers.filter { (counts[it] ?: 0) < capacity }.minByOrNull { counts[it] ?: 0 }
        return current.copy(available = available && worker != null && worker in workers && (current.enabled || (counts[worker] ?: 0) < capacity), trustedCloudAvailable = trustedAvailable)
    }
    fun get(household: String, available: Boolean): BackupPolicy = runBlocking {
        database.open(household).use { db -> decorate(store(db).readLocalSourceOfTruth(household).firstOrNull() ?: BackupPolicy(), available, if (available) counts(db) else emptyMap()) }
    }
    fun update(household: String, request: BackupPolicyUpdate, available: Boolean, actor: String?): BackupPolicy = runBlocking {
        database.open(household).use { db ->
            db.autoCommit = false
            try {
                // Global assignment lock is short and only held for settings writes; prevents capacity races across signaling processes.
                database.lock(db, "cloud-policy-assignments")
                val store = store(db)
                val counts = counts(db)
                val current = decorate(store.readLocalSourceOfTruth(household).firstOrNull() ?: BackupPolicy(), available, counts)
                require(current.revision == request.revision) { "Settings changed on another device. Refresh and try again." }
                require(!request.enabled || current.available) { "Cloud sync is unavailable or capacity is full." }
                require(!current.enabled || request.turnEnabled || !request.enabled) { "Disable cloud sync before turning off TURN." }
                require(request.encryption != BackupEncryption.TRUSTED_CLOUD || (!request.enabled && current.encryption == request.encryption) || trustedAvailable)
                require(request.encryption != BackupEncryption.TRUSTED_CLOUD || current.encryption == request.encryption || request.consent == TRUSTED_CLOUD_CONSENT) { "Explicit consent to service access is required." }
                val worker = current.workerId ?: workers.filter { (counts[it] ?: 0) < capacity }.minByOrNull { counts[it] ?: 0 }
                val next = BackupPolicy(enabled = request.enabled, turnEnabled = if (request.enabled && !current.enabled) true else request.turnEnabled,
                    available = available, workerId = worker, revision = current.revision + 1, encryption = request.encryption, trustedCloudAvailable = trustedAvailable,
                    encryptionRevision = if (request.encryption != current.encryption) current.revision + 1 else current.encryptionRevision,
                    recoveryKeyRequired = current.recoveryKeyRequired || (request.enabled && request.encryption == BackupEncryption.END_TO_END),
                    trustedConsentBy = if (request.consent == TRUSTED_CLOUD_CONSENT) actor else current.trustedConsentBy,
                    mcpClients = cloudMcpGrants(current, request, actor, System.currentTimeMillis()))
                require(!next.enabled || next.turnEnabled)
                store.write(StoreWriteRequest.of<String, List<BackupPolicy>, Unit>(household, listOf(next.copy(available = false))))
                check(store.readLocalSourceOfTruth(household).single() == next.copy(available = false))
                db.commit(); next
            } catch (e: Exception) { db.rollback(); throw e }
        }
    }
    fun assignments(worker: String): List<BackupAssignment> = database.open("").use { db ->
        db.prepareStatement("SELECT household,policy FROM private_backup.cloud_policies WHERE worker=? AND enabled=true").use {
            it.setString(1, worker); it.executeQuery().use { r -> buildList { while(r.next()) Json.decodeFromString<BackupPolicy>(r.getString(2)).let { add(BackupAssignment(r.getString(1), it.encryption, it.mcpClients)) } } }
        }
    }
    fun assigned(household: String, worker: String): Boolean = database.open(household).use { db ->
        db.prepareStatement("SELECT 1 FROM private_backup.cloud_policies WHERE household=? AND worker=? AND enabled=true").use {
            it.setString(1, household); it.setString(2, worker); it.executeQuery().use { r -> r.next() }
        }
    }
    fun checkReady() { database.open("").use { db -> db.createStatement().use { it.executeQuery("SELECT household FROM private_backup.cloud_policies LIMIT 0").close() } } }
    override fun close() = database.close()
}
