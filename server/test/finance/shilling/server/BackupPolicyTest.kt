package finance.shilling.server

import finance.shilling.core.backup.*
import java.nio.file.Files
import kotlin.test.*

class BackupPolicyTest {
    @Test fun cloudMcpGrantsNeedSeparateConsentAndAreRevokedWithTrustedMode() {
        val store = BackupPolicyStore(Files.createTempDirectory("mcp-policy").resolve("policy.json"), listOf("worker"), true)
        val grant = CloudMcpGrant("reader", "a".repeat(64), read = true, expiresAt = System.currentTimeMillis() + 60_000)
        val request = BackupPolicyUpdate(true, true, 0, BackupEncryption.TRUSTED_CLOUD, TRUSTED_CLOUD_CONSENT, listOf(grant))
        assertFailsWith<IllegalArgumentException> { store.update("household", request, true, "owner") }
        val policy = store.update("household", request.copy(mcpConsent = CLOUD_MCP_CONSENT), true, "owner")
        assertEquals("owner", policy.mcpClients.single().consentBy)
        assertFalse(policy.mcpClients.single().write)
        assertEquals(policy.mcpClients, store.assignments("worker").single().mcpClients)
        val write = request.copy(revision = policy.revision, mcpClients = listOf(grant.copy(write = true)))
        assertFailsWith<IllegalArgumentException> { store.update("household", write, true, "owner") }
        assertFailsWith<IllegalArgumentException> {
            store.update("household", write.copy(mcpConsent = CLOUD_MCP_CONSENT, mcpClients = listOf(grant.copy(expiresAt = 0))), true, "owner")
        }
        val encrypted = store.update("household", BackupPolicyUpdate(true, true, policy.revision), true, "owner")
        assertTrue(encrypted.mcpClients.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            store.update("household", BackupPolicyUpdate(true, true, encrypted.revision, mcpClients = listOf(grant), mcpConsent = CLOUD_MCP_CONSENT), true, "owner")
        }
    }
    @Test fun cloudBackupForcesTurnAndRejectsDisablingIt() {
        val store = BackupPolicyStore(Files.createTempDirectory("backup-policy").resolve("policy.json"), listOf("worker"))
        val enabled = store.update("household", BackupPolicyUpdate(true, false, 0), true)
        assertTrue(enabled.enabled && enabled.turnEnabled)
        assertTrue(enabled.recoveryKeyRequired)
        assertFailsWith<IllegalArgumentException> { store.update("household", BackupPolicyUpdate(true, false, enabled.revision), true) }
        val disabled = store.update("household", BackupPolicyUpdate(false, false, enabled.revision), true)
        assertFalse(disabled.enabled || disabled.turnEnabled)
        assertTrue(disabled.recoveryKeyRequired)
    }
    @Test fun unavailableOrStaleUpdatesCannotEnableBackup() {
        val store = BackupPolicyStore(Files.createTempDirectory("backup-policy").resolve("policy.json"), listOf("worker"))
        assertFailsWith<IllegalArgumentException> { store.update("household", BackupPolicyUpdate(true, true, 0), false) }
        store.update("household", BackupPolicyUpdate(true, true, 0), true)
        assertFailsWith<IllegalArgumentException> { store.update("household", BackupPolicyUpdate(false, false, 0), true) }
    }
    @Test fun trustedCloudRequiresExplicitConsentAndPersistsWithoutConvertingOldMode() {
        val path = Files.createTempDirectory("backup-policy").resolve("policy.json")
        val store = BackupPolicyStore(path, listOf("worker"), true)
        assertEquals(BackupEncryption.END_TO_END, store.get("household", true).encryption)
        val request = BackupPolicyUpdate(true, true, 0, BackupEncryption.TRUSTED_CLOUD)
        assertFailsWith<IllegalArgumentException> { store.update("household", request, true) }
        val trusted = store.update("household", request.copy(consent = TRUSTED_CLOUD_CONSENT), true, "user-1")
        assertFalse(trusted.recoveryKeyRequired)
        assertEquals("user-1", trusted.trustedConsentBy)
        assertEquals(trusted, BackupPolicyStore(path, listOf("worker"), true).get("household", true))
        assertEquals(BackupEncryption.TRUSTED_CLOUD, store.assignments("worker").single().encryption)
        val encrypted = store.update("household", BackupPolicyUpdate(true, true, trusted.revision), true)
        assertTrue(encrypted.recoveryKeyRequired)
        assertEquals(BackupEncryption.END_TO_END, encrypted.encryption)
        assertTrue(encrypted.encryptionRevision > trusted.encryptionRevision)
    }
    @Test fun fullWorkerRejectsNewAssignmentsWithoutDisruptingExistingHouseholds() {
        val store = BackupPolicyStore(Files.createTempDirectory("backup-policy").resolve("policy.json"), listOf("worker"), maxHouseholdsPerWorker = 1)
        store.update("first", BackupPolicyUpdate(true, true, 0), true)
        assertFalse(store.get("second", true).available)
        assertFailsWith<IllegalArgumentException> { store.update("second", BackupPolicyUpdate(true, true, 0), true) }
        assertTrue(store.get("first", true).enabled)
        assertEquals(1, store.assignments("worker").size)
    }
    @Test fun householdsShareWorkersAndAssignmentsAreScoped() {
        val store = BackupPolicyStore(Files.createTempDirectory("backup-policy").resolve("policy.json"), listOf("a", "b"))
        repeat(20) { store.update("household-$it", BackupPolicyUpdate(true, true, 0), true) }
        assertEquals(10, store.assignments("a").size)
        assertEquals(10, store.assignments("b").size)
        assertTrue(store.assignments("unknown").isEmpty())
        assertFalse(store.assigned("household-0", "b"))
    }
}
