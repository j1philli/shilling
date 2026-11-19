package finance.shilling.shared.mcp

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_MCP_PORT
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpAccessTest {
    /** Records start calls; refuses ports in [busy] the way the Tauri host does. */
    private class FakeHost(var busy: Set<Int> = emptySet()) : McpHost {
        val starts = mutableListOf<Int>()

        override suspend fun start(port: Int, token: String, handler: suspend (String) -> String?) {
            starts += port
            if (port in busy) error("Port $port is already in use by another app")
        }

        override suspend fun stop() {}
    }

    // The fake host never forwards requests, so the handler is unused.
    private fun access(host: McpHost, settings: Settings = PropertiesSettings(Properties())) =
        McpAccess(host, { null }, settings)

    @Test
    fun usesDefaultPortUntilChanged() = runBlocking {
        val host = FakeHost()
        val access = access(host)
        assertEquals(DEFAULT_MCP_PORT, access.state.value.port)
        access.setEnabled(true)
        assertEquals(listOf(DEFAULT_MCP_PORT), host.starts)
        assertEquals("http://127.0.0.1:$DEFAULT_MCP_PORT/mcp", access.state.value.endpoint)
    }

    @Test
    fun changingPortWhileOnRestartsAndPersists() = runBlocking {
        val settings = PropertiesSettings(Properties())
        val host = FakeHost()
        val access = access(host, settings)
        access.setEnabled(true)

        assertNull(access.setPort(50_000))
        assertEquals(listOf(DEFAULT_MCP_PORT, 50_000), host.starts)
        assertTrue(access.state.value.running)
        assertEquals(50_000, settings.getInt(SETTINGS_KEY_MCP_PORT, 0))

        // A fresh instance (next launch) picks the saved port back up.
        val relaunched = FakeHost()
        access(relaunched, settings).resume()
        assertEquals(listOf(50_000), relaunched.starts)
    }

    @Test
    fun changingPortWhileOffDoesNotStart() = runBlocking {
        val host = FakeHost()
        val access = access(host)
        assertNull(access.setPort(50_001))
        assertTrue(host.starts.isEmpty())
        assertEquals(50_001, access.state.value.port)
    }

    @Test
    fun rejectsPortsOutsideRange() = runBlocking {
        val access = access(FakeHost())
        assertNotNull(access.setPort(80))
        assertNotNull(access.setPort(70_000))
        assertEquals(DEFAULT_MCP_PORT, access.state.value.port)
    }

    @Test
    fun busyPortSurfacesAsError() = runBlocking {
        val access = access(FakeHost(busy = setOf(50_002)))
        access.setEnabled(true)
        access.setPort(50_002)
        assertFalse(access.state.value.running)
        assertEquals("Port 50002 is already in use by another app", access.state.value.error)
    }

    @Test
    fun ignoresSavedPortOutOfRange() {
        val settings = PropertiesSettings(Properties()).apply { putInt(SETTINGS_KEY_MCP_PORT, 22) }
        assertEquals(DEFAULT_MCP_PORT, access(FakeHost(), settings).state.value.port)
    }

    @Test
    fun savingSamePortRetriesAfterBusyPortIsFreed() = runBlocking {
        val host = FakeHost(busy = setOf(50_003))
        val access = access(host)
        access.setEnabled(true)
        access.setPort(50_003)
        assertFalse(access.state.value.running)

        host.busy = emptySet()
        assertNull(access.setPort(50_003))
        assertEquals(listOf(DEFAULT_MCP_PORT, 50_003, 50_003), host.starts)
        assertTrue(access.state.value.running)
        assertNull(access.state.value.error)

        // Once it's running, saving the same port again is a no-op.
        access.setPort(50_003)
        assertEquals(3, host.starts.size)
    }

    /** Host whose start is slow, recording the order calls finish in. */
    private class SlowHost : McpHost {
        val events = mutableListOf<String>()
        override suspend fun start(port: Int, token: String, handler: suspend (String) -> String?) {
            delay(50)
            events += "start:$port"
        }
        override suspend fun stop() {
            events += "stop"
        }
    }

    @Test
    fun overlappingChangesReachTheHostInOrder() = runBlocking {
        val host = SlowHost()
        val access = access(host)
        // Turn on, then off before the (slow) start has finished: the endpoint must end up off.
        listOf(
            async { access.setEnabled(true) },
            async { delay(10); access.setEnabled(false) }
        ).awaitAll()
        assertEquals(listOf("start:$DEFAULT_MCP_PORT", "stop"), host.events)
        assertFalse(access.state.value.running)
        assertFalse(access.state.value.enabled)
    }
}
