package finance.shilling.shared.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DataChannelRoleTest {

    @Test
    fun offererRoles() {
        assertEquals(DataChannelRole.OWN_RECEIVE, resolveDataChannelRole(isOfferer = true, label = "sync"))
        assertEquals(DataChannelRole.PEER_SEND, resolveDataChannelRole(isOfferer = true, label = "reply"))
    }

    @Test
    fun answererRoles() {
        assertEquals(DataChannelRole.PEER_SEND, resolveDataChannelRole(isOfferer = false, label = "sync"))
        assertEquals(DataChannelRole.OWN_RECEIVE, resolveDataChannelRole(isOfferer = false, label = "reply"))
    }

    @Test
    fun unknownLabelsIgnored() {
        assertEquals(DataChannelRole.IGNORE, resolveDataChannelRole(isOfferer = true, label = "file-sync"))
        assertEquals(DataChannelRole.IGNORE, resolveDataChannelRole(isOfferer = false, label = "unexpected"))
    }

    @Test
    fun outboundChannelPreferenceForOfferer() {
        val send = Any()
        val receive = Any()

        assertSame(send, selectOutboundChannel(isOfferer = true, sendChannel = send, receiveChannel = receive))
        assertSame(receive, selectOutboundChannel(isOfferer = true, sendChannel = null, receiveChannel = receive))
    }

    @Test
    fun outboundChannelPreferenceForAnswerer() {
        val send = Any()
        val receive = Any()

        assertSame(send, selectOutboundChannel(isOfferer = false, sendChannel = send, receiveChannel = receive))
        assertSame(receive, selectOutboundChannel(isOfferer = false, sendChannel = null, receiveChannel = receive))
    }

    @Test
    fun peerSendListenerPolicy() {
        assertTrue(shouldListenOnPeerSendChannel(isOfferer = true))
        assertFalse(shouldListenOnPeerSendChannel(isOfferer = false))
    }
}
