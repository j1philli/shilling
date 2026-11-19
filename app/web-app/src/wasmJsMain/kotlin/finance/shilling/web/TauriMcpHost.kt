@file:OptIn(ExperimentalWasmJsInterop::class)

package finance.shilling.web

import co.touchlab.kermit.Logger
import finance.shilling.shared.mcp.McpHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.Promise

/**
 * [McpHost] backed by the Tauri shell (src-tauri/src/mcp.rs): Rust runs the loopback HTTP server
 * and authenticates requests, then emits each body as an `mcp-request` event; we answer through
 * the `mcp_respond` command.
 */
class TauriMcpHost(private val scope: CoroutineScope) : McpHost {
    private val log = Logger.withTag("Mcp")
    private var handler: (suspend (String) -> String?)? = null
    private var listening = false

    override suspend fun start(port: Int, token: String, handler: suspend (String) -> String?) {
        this.handler = handler
        if (!listening) {
            listenForMcpRequests { id, body -> scope.launch { respond(id, body) } }
            listening = true
        }
        invokeMcpStart(port, token).await<JsString?>()?.toString()?.let { error(it) }
    }

    override suspend fun stop() {
        invokeMcpStop().await<JsString?>()?.toString()?.let { log.w { "Stopping MCP endpoint failed: $it" } }
    }

    private suspend fun respond(id: Double, body: String) {
        val response = try {
            handler?.invoke(body)
        } catch (e: Exception) {
            log.e(e) { "MCP request failed" }
            null
        }
        invokeMcpRespond(id, response).await<JsString?>()?.toString()?.let { log.w { "MCP respond failed: $it" } }
    }
}

// Each invoke resolves to null on success or the error message, so failures surface as text.

private fun listenForMcpRequests(onRequest: (Double, String) -> Unit): Unit =
    js("{ window.__TAURI__.event.listen('mcp-request', (e) => onRequest(e.payload.id, e.payload.body)); }")

private fun invokeMcpStart(port: Int, token: String): Promise<JsString?> =
    js("window.__TAURI__.core.invoke('mcp_start', { port: port, token: token }).then(() => null, (e) => String(e))")

private fun invokeMcpStop(): Promise<JsString?> =
    js("window.__TAURI__.core.invoke('mcp_stop').then(() => null, (e) => String(e))")

private fun invokeMcpRespond(id: Double, body: String?): Promise<JsString?> =
    js("window.__TAURI__.core.invoke('mcp_respond', { id: id, body: body }).then(() => null, (e) => String(e))")
