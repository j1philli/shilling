package finance.shilling.server

import finance.shilling.core.auth.HostedPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class HostedEntitlementsTest {
    @Test
    fun goldWinsWhenBothEntitlementsAreActive() {
        assertEquals(HostedPlan.GOLD, planForEntitlementIds(listOf("silver", "gold"), "silver", "gold"))
    }

    @Test
    fun unknownEntitlementsDoNotGrantPaidAccess() {
        assertEquals(HostedPlan.FREE, planForEntitlementIds(listOf("other"), "silver", "gold"))
    }
}
