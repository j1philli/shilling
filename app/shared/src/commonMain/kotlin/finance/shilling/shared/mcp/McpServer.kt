package finance.shilling.shared.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** MCP protocol revisions this server speaks, newest first. */
internal val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
private const val INTERNAL_ERROR = -32603

private val SERVER_INSTRUCTIONS = """
    Shilling is a household budget. Accounts hold balances; schedules are planned recurring or one-off
    income, expenses and transfers; occurrences are a schedule's projected dates; transactions are what
    actually happened. Amounts are positive numbers (the type says which way money moves). Dates are
    ISO-8601 (YYYY-MM-DD). Look up ids with the list tools before writing. Account balances are entered
    by the user and do not change when transactions are recorded. Changes sync to the household's other
    devices.
""".trimIndent().replace('\n', ' ')

/**
 * Stateless MCP server: turns one JSON-RPC message (a Streamable HTTP POST body) into its response.
 * Transport, auth and sessions belong to the platform [McpHost]; tools come from [McpToolbox].
 */
class McpServer(private val toolbox: McpToolbox, private val version: String = "1.0") {
    private val json = Json { encodeDefaults = false }

    /** Returns the response body, or null when nothing is owed (notifications and responses). */
    suspend fun handle(body: String): String? {
        val message = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return error(JsonNull, PARSE_ERROR, "Parse error").toString()
        }
        val response = when (message) {
            is JsonObject -> handleMessage(message)
            // Batches were dropped in 2025-06-18 but older clients may still send them.
            is JsonArray -> message.mapNotNull { (it as? JsonObject)?.let { m -> handleMessage(m) } }
                .takeIf { it.isNotEmpty() }?.let(::JsonArray)
            else -> error(JsonNull, INVALID_REQUEST, "Invalid request")
        }
        return response?.toString()
    }

    private suspend fun handleMessage(message: JsonObject): JsonElement? {
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val id = message["id"]
        // Notifications have no id; responses (to requests we never send) have no method.
        if (id == null || id is JsonNull) return null
        if (method == null) return error(id, INVALID_REQUEST, "Missing method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject { put("tools", toolList()) })
                "tools/call" -> callTool(id, params)
                else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(id, INTERNAL_ERROR, e.message ?: "Internal error")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: SUPPORTED_PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", "shilling")
                put("title", "Shilling")
                put("version", version)
            }
            put("instructions", SERVER_INSTRUCTIONS)
        }
    }

    private fun toolList(): JsonArray = buildJsonArray {
        toolbox.tools.forEach { tool ->
            add(buildJsonObject {
                put("name", tool.name)
                put("title", tool.title)
                put("description", tool.description)
                put("inputSchema", tool.inputSchema)
                putJsonObject("annotations") {
                    put("title", tool.title)
                    put("readOnlyHint", tool.readOnly)
                    if (!tool.readOnly) {
                        put("destructiveHint", tool.destructive)
                        put("idempotentHint", tool.idempotent)
                    }
                    put("openWorldHint", false)
                }
            })
        }
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return error(id, INVALID_PARAMS, "Missing tool name")
        val tool = toolbox.tools.firstOrNull { it.name == name }
            ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val arguments = when (val raw = params["arguments"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> return error(id, INVALID_PARAMS, "Tool arguments must be an object")
        }
        // Bad arguments and failed lookups go back as tool errors so the model can correct itself.
        val (text, isError) = try {
            tool.handler(arguments).toString() to false
        } catch (e: McpToolException) {
            e.message.orEmpty() to true
        }
        return result(id, buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("isError", isError)
        })
    }

    private fun result(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }
}

/** A tool failure reported to the model (bad arguments, unknown ids) rather than as a protocol error. */
class McpToolException(message: String) : Exception(message)
