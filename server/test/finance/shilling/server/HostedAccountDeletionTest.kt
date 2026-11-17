package finance.shilling.server

import finance.shilling.core.auth.HostedPlan
import finance.shilling.core.auth.RegisteredDevice
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostedAccountDeletionTest {
    private class FakeEntitlements(val plan: HostedPlan, val failCustomerDeletion: Boolean = false) : HostedEntitlementLookup {
        val deletedCustomers = mutableListOf<String>()
        override suspend fun accountPlan(userId: String) = plan
        override suspend fun householdPlan(householdId: String) = plan
        override suspend fun deleteCustomer(userId: String) {
            if (failCustomerDeletion) error("RevenueCat unavailable")
            deletedCustomers += userId
        }
    }

    private class FakeAccounts(val failAuthDeletion: Boolean = false) : HostedAccountStore {
        val calls = mutableListOf<String>()
        override suspend fun spaceIds(userId: String) = listOf("space-a")
        override suspend fun deleteMetadata(userId: String) { calls += "metadata:$userId" }
        override suspend fun deleteAuthUser(userId: String) {
            if (failAuthDeletion) error("Auth unavailable")
            calls += "auth:$userId"
        }
    }

    private class FakeDevices : HostedDeviceRegistry {
        override suspend fun register(householdId: String, deviceId: String, userId: String, plan: HostedPlan) = true
        override suspend fun count(householdId: String) = 0
        override suspend fun list(householdId: String) = emptyList<String>()
        override suspend fun listDetails(householdId: String) = listOf(
            RegisteredDevice("mine", ownerUserId = "user", registeredAt = "now"),
            RegisteredDevice("theirs", ownerUserId = "other", registeredAt = "now")
        )
        override suspend fun remove(householdId: String, deviceId: String) = Unit
    }

    @Test
    fun paidAccountMustAcknowledgeSubscriptionBeforeAnythingIsDeleted() = runBlocking {
        val accounts = FakeAccounts()
        val result = deleteHostedAccount("user", false, FakeEntitlements(HostedPlan.SILVER), accounts, FakeDevices(), SignalingHub())
        assertEquals(HostedAccountDeletionResult.SubscriptionActive(HostedPlan.SILVER), result)
        assertEquals(emptyList(), accounts.calls)
    }

    @Test
    fun acknowledgedPaidAccountDeletesMetadataThenAuthThenBillingCustomer() = runBlocking {
        val accounts = FakeAccounts()
        val entitlements = FakeEntitlements(HostedPlan.GOLD)
        val result = deleteHostedAccount("user", true, entitlements, accounts, FakeDevices(), SignalingHub())
        assertEquals(HostedAccountDeletionResult.Deleted, result)
        assertEquals(listOf("metadata:user", "auth:user"), accounts.calls)
        assertEquals(listOf("user"), entitlements.deletedCustomers)
    }

    @Test
    fun freeAccountNeedsNoAcknowledgementAndBillingFailureDoesNotUndoDeletion() = runBlocking {
        val accounts = FakeAccounts()
        val result = deleteHostedAccount(
            "user", false, FakeEntitlements(HostedPlan.FREE, failCustomerDeletion = true), accounts, FakeDevices(), SignalingHub()
        )
        assertEquals(HostedAccountDeletionResult.Deleted, result)
        assertEquals(listOf("metadata:user", "auth:user"), accounts.calls)
    }

    @Test
    fun authFailureIsReportedSoTheClientCanRetry() {
        assertFailsWith<IllegalStateException> {
            runBlocking {
                deleteHostedAccount("user", false, FakeEntitlements(HostedPlan.FREE), FakeAccounts(failAuthDeletion = true), FakeDevices(), SignalingHub())
            }
        }
    }
}
