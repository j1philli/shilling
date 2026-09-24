package finance.shilling.shared.data.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReceiveChannelChoiceTest {

    @Test
    fun prefersExistingChannelWhenPresent() {
        val existing = Any()
        val opened = Any()

        val choice = preferExistingReceiveChannel(existing = existing, opened = opened)

        assertSame(existing, choice.channel)
        assertTrue(choice.usedExisting)
    }

    @Test
    fun usesOpenedChannelWhenNoExistingChannel() {
        val opened = Any()

        val choice = preferExistingReceiveChannel(existing = null, opened = opened)

        assertSame(opened, choice.channel)
        assertFalse(choice.usedExisting)
    }
}
