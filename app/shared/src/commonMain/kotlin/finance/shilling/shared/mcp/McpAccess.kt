package finance.shilling.shared.mcp

import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_MCP_ENABLED
import finance.shilling.shared.data.SETTINGS_KEY_MCP_PORT
import finance.shilling.shared.data.SETTINGS_KEY_MCP_TOKEN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Loopback port the assistant endpoint listens on unless the user picks another. */
const val DEFAULT_MCP_PORT = 47_615
/** Unprivileged ports only, so no elevated permissions are needed. */
val MCP_PORT_RANGE = 1024..65535
const val MCP_PATH = "/mcp"

/**
 * Platform transport for the assistant (MCP) endpoint: a Streamable HTTP server on 127.0.0.1 that
 * checks the bearer token and hands each POST body to the handler. Only the Tauri desktop app
 * provides one; elsewhere [McpAccess.available] is false.
 */
interface McpHost {
    /** Starts (or restarts) listening. Throws with a user-facing message if it can't. */
    suspend fun start(port: Int, token: String, handler: suspend (String) -> String?)
    suspend fun stop()
}

data class McpAccessState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val port: Int = DEFAULT_MCP_PORT,
    val token: String = "",
    val error: String? = null
) {
    val endpoint: String get() = "http://127.0.0.1:$port$MCP_PATH"
}

/**
 * Opt-in assistant access: persists the toggle, port and bearer token in [Settings] and runs the
 * [handler] (normally [McpServer.handle]) on the platform [host] while enabled.
 */
class McpAccess(
    private val host: McpHost?,
    private val handler: suspend (String) -> String?,
    private val settings: Settings
) {
    private val _state = MutableStateFlow(
        McpAccessState(
            enabled = host != null && settings.getBoolean(SETTINGS_KEY_MCP_ENABLED, false),
            port = settings.getInt(SETTINGS_KEY_MCP_PORT, DEFAULT_MCP_PORT).takeIf { it in MCP_PORT_RANGE } ?: DEFAULT_MCP_PORT,
            token = settings.getStringOrNull(SETTINGS_KEY_MCP_TOKEN).orEmpty()
        )
    )
    val state: StateFlow<McpAccessState> = _state.asStateFlow()

    val available: Boolean get() = host != null

    /**
     * Serializes start/stop so a toggle, port change and token change made in quick succession
     * reach the host in order (a slow start can't land after a later stop).
     */
    private val lock = Mutex()

    /** Called once at app start: brings the endpoint back up if it was left on. */
    suspend fun resume() = lock.withLock {
        if (_state.value.enabled) startHost()
    }

    suspend fun setEnabled(enabled: Boolean) = lock.withLock {
        if (host == null) return@withLock
        settings.putBoolean(SETTINGS_KEY_MCP_ENABLED, enabled)
        _state.update { it.copy(enabled = enabled, error = null) }
        if (enabled) startHost() else stopHost()
    }

    /**
     * Moves the endpoint to [port], restarting it if it's on. Returns an error message when the
     * port is out of range; a port already in use shows up as the state's error instead. Saving
     * the current port again retries it when the endpoint is on but couldn't start.
     */
    suspend fun setPort(port: Int): String? = lock.withLock {
        if (port !in MCP_PORT_RANGE) {
            return@withLock "Port must be between ${MCP_PORT_RANGE.first} and ${MCP_PORT_RANGE.last}"
        }
        val current = _state.value
        if (port == current.port && (!current.enabled || current.running)) return@withLock null
        settings.putInt(SETTINGS_KEY_MCP_PORT, port)
        _state.update { it.copy(port = port) }
        if (_state.value.enabled) startHost()
        null
    }

    /** Issues a new token; clients using the old one stop working. */
    suspend fun regenerateToken() = lock.withLock {
        saveToken(newToken())
        if (_state.value.enabled) startHost()
    }

    private suspend fun startHost() {
        val host = host ?: return
        val token = _state.value.token.ifEmpty { newToken().also(::saveToken) }
        try {
            host.start(_state.value.port, token, handler)
            _state.update { it.copy(running = true, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(running = false, error = e.message ?: "Couldn't start the assistant endpoint") }
        }
    }

    private suspend fun stopHost() {
        runCatching { host?.stop() }
        _state.update { it.copy(running = false) }
    }

    private fun saveToken(token: String) {
        settings.putString(SETTINGS_KEY_MCP_TOKEN, token)
        _state.update { it.copy(token = token) }
    }

    /** Two random UUIDs (~244 bits from a secure RNG), hex only so it pastes cleanly into headers. */
    @OptIn(ExperimentalUuidApi::class)
    private fun newToken(): String = Uuid.random().toHexString() + Uuid.random().toHexString()
}
