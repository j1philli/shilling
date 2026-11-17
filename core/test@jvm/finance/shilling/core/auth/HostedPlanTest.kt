package finance.shilling.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedPlanTest {
    @Test
    fun freeHasTwoDevicesAndNoPaidConnectionOrBankAccess() {
        assertEquals(2, HostedPlan.FREE.deviceLimit())
        assertEquals(1, HostedPlan.FREE.householdLimit())
        assertFalse(HostedPlan.FREE.turnEnabled())
        assertFalse(HostedPlan.FREE.bankReadingEnabled())
    }

    @Test
    fun silverUnlocksHouseholdSyncAndBankReadingButNotMoreHouseholds() {
        assertNull(HostedPlan.SILVER.deviceLimit())
        assertEquals(1, HostedPlan.SILVER.householdLimit())
        assertTrue(HostedPlan.SILVER.turnEnabled())
        assertTrue(HostedPlan.SILVER.bankReadingEnabled())
    }

    @Test
    fun goldAddsUnlimitedHouseholdMemberships() {
        assertNull(HostedPlan.GOLD.deviceLimit())
        assertNull(HostedPlan.GOLD.householdLimit())
        assertTrue(HostedPlan.GOLD.turnEnabled())
        assertTrue(HostedPlan.GOLD.bankReadingEnabled())
    }
}
