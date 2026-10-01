package finance.shilling.shared.data.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class RelayPolicyTest {
    @Test
    fun relayCandidatesAreRecognizedWithoutRejectingDirectCandidates() {
        assertTrue(isRelayCandidate("candidate:1 1 udp 1 203.0.113.1 3478 typ relay raddr 0.0.0.0 rport 0"))
        assertFalse(isRelayCandidate("candidate:2 1 udp 1 192.0.2.1 5000 typ host"))
        assertFalse(isRelayCandidate("candidate:3 1 udp 1 192.0.2.1 5001 typ srflx"))
    }

    @Test
    fun relayCandidatesAreRemovedFromRemoteSdp() {
        val sdp = "v=0\r\na=candidate:1 1 udp 1 203.0.113.1 3478 typ relay\r\na=candidate:2 1 udp 1 192.0.2.1 5000 typ host\r\na=end-of-candidates\r\n"
        assertEquals("v=0\r\na=candidate:2 1 udp 1 192.0.2.1 5000 typ host\r\na=end-of-candidates\r\n", withoutRelayCandidates(sdp))
    }
}
