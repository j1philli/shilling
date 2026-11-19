package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.mcp.McpAccess
import finance.shilling.shared.mcp.McpAccessState
import finance.shilling.shared.mcp.MCP_PORT_RANGE
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings → Assistant access (desktop only): the MCP endpoint toggle and how to connect a client. */
data class AssistantAccessUiState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val status: String = "",
    val isError: Boolean = false,
    val endpoint: String = "",
    val port: Int = 0,
    val token: String = "",
    /** Claude Code one-liner. */
    val claudeCodeCommand: String = "",
    /** `mcpServers` entry for clients configured with JSON (Cursor, Claude Desktop via mcp-remote, …). */
    val jsonConfig: String = ""
) {
    val title: String get() = "Assistant access"
    val body: String
        get() = "Let AI assistants on this computer read and update your budget over MCP. " +
            "Only apps you give the token to can connect, and only while Shilling is open."
}

class AssistantAccessViewModel(private val access: McpAccess) : ViewModel() {
    val state: StateFlow<AssistantAccessUiState> = access.state
        .map(::uiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), uiState(access.state.value))

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { access.setEnabled(enabled) }
    }

    /** Returns the snackbar message. */
    suspend fun setPort(text: String): String {
        val port = text.trim().toIntOrNull()
            ?: return "Port must be a number between ${MCP_PORT_RANGE.first} and ${MCP_PORT_RANGE.last}"
        val before = access.state.value
        if (port == before.port && (!before.enabled || before.running)) return "Already using port $port"
        access.setPort(port)?.let { return it }
        val now = access.state.value
        return when {
            !now.enabled -> "Port set to $port"
            now.running -> "Moved to port $port. Update your assistant's configuration."
            else -> now.error ?: "Couldn't listen on port $port"
        }
    }

    /** Returns the snackbar message. */
    suspend fun regenerateToken(): String {
        access.regenerateToken()
        return "New token created. Update your assistant's configuration."
    }

    private fun uiState(s: McpAccessState): AssistantAccessUiState {
        val (status, isError) = when {
            s.error != null -> s.error to true
            s.running -> "Listening on ${s.endpoint}" to false
            s.enabled -> "Starting…" to false
            else -> "Off" to false
        }
        return AssistantAccessUiState(
            available = access.available,
            enabled = s.enabled,
            status = status,
            isError = isError,
            endpoint = s.endpoint,
            port = s.port,
            token = s.token,
            claudeCodeCommand = "claude mcp add --transport http shilling ${s.endpoint} " +
                "--header \"Authorization: Bearer ${s.token}\"",
            jsonConfig = """
                {
                  "mcpServers": {
                    "shilling": {
                      "type": "http",
                      "url": "${s.endpoint}",
                      "headers": { "Authorization": "Bearer ${s.token}" }
                    }
                  }
                }
            """.trimIndent()
        )
    }
}
