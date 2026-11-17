package finance.shilling.shared.mcp

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.dataModule
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.presentation.today
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    private lateinit var app: KoinApplication
    private lateinit var server: McpServer
    private var nextRequestId = 0

    @BeforeTest
    fun setUp() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        val db = ShillingDatabase(driver)
        var nextId = 0
        val platformModule = module {
            single { db }
            single { Settings() }
            single<IdGenerator> { object : IdGenerator { override fun newId() = "id-${++nextId}" } }
            single<ReceiptFileStore> { NoReceiptFiles }
        }
        app = koinApplication { modules(platformModule, dataModule) }
        server = McpServer(app.koin.get(), authorize = { McpPermissions(read = true, write = true) })
    }

    @AfterTest
    fun tearDown() = app.close()

    @Test
    fun grantsAreCheckedOnEveryCallAndDiscoveryCannotBeBypassed() = runBlocking {
        var grant: McpPermissions? = McpPermissions(read = true)
        server = McpServer(app.koin.get(), authorize = { grant })
        val tools = request("tools/list").result["tools"]!!.jsonArray
        assertTrue(tools.isNotEmpty())
        assertTrue(tools.all { it.jsonObject["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean })
        val denied = request("tools/call", """{"name":"save_account","arguments":{"name":"Forbidden"}}""")
        assertEquals(-32003, denied.error!!["code"]!!.jsonPrimitive.int)
        grant = McpPermissions(read = true, write = true)
        callOk("save_account", """{"name":"Allowed"}""")
        grant = null
        assertTrue(request("tools/list").result["tools"]!!.jsonArray.isEmpty())
        assertEquals(-32003, request("tools/call", """{"name":"list_accounts"}""").error!!["code"]!!.jsonPrimitive.int)
        grant = McpPermissions(write = true)
        assertTrue(request("tools/list").result["tools"]!!.jsonArray.isEmpty())
    }

    @Test
    fun remoteProjectionWorkIsBounded() = runBlocking {
        val response = request("tools/call", """{"name":"project_balance","arguments":{"date":"9999-01-01"}}""")
        assertTrue(response.result["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun initializeNegotiatesProtocolVersion() = runBlocking {
        val known = request("initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        assertEquals("2025-03-26", known.result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("shilling", known.result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val future = request("initialize", """{"protocolVersion":"2099-01-01"}""")
        assertEquals(SUPPORTED_PROTOCOL_VERSIONS.first(), future.result["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationsGetNoResponse() = runBlocking {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun malformedAndUnknownRequestsAreProtocolErrors() = runBlocking {
        val parse = Json.parseToJsonElement(server.handle("{nope")!!).jsonObject
        assertEquals(-32700, parse["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32601, request("resources/list").error!!["code"]!!.jsonPrimitive.int)
        assertEquals(-32602, request("tools/call", """{"name":"no_such_tool"}""").error!!["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun toolsListMarksReadOnlyAndDestructiveTools() = runBlocking {
        val tools = request("tools/list").result["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(tools.values.all { it.jsonObject["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content == "object" })
        fun hint(tool: String, name: String) =
            tools.getValue(tool).jsonObject["annotations"]!!.jsonObject[name]!!.jsonPrimitive.boolean
        assertTrue(hint("list_accounts", "readOnlyHint"))
        assertTrue(!hint("record_transaction", "readOnlyHint"))
        assertTrue(hint("delete_transaction", "destructiveHint"))
    }

    @Test
    fun recordsAndListsTransactions() = runBlocking {
        val checking = callOk("save_account", """{"name":"Checking","balance":1200.5}""")["account"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val savings = callOk("save_account", """{"name":"Savings"}""")["account"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val groceries = callOk("save_category", """{"name":"Groceries","color":"#4CAF50"}""")["category"]!!.jsonObject["id"]!!.jsonPrimitive.content

        assertEquals(1200.5, callOk("list_accounts")["totalBalance"]!!.jsonPrimitive.double)

        val date = "2026-09-15"
        callOk("record_transaction", """{"title":"Market","amount":42.1,"type":"EXPENSE","accountId":"$checking","categoryId":"$groceries","date":"$date"}""")
        val transferId = callOk("record_transaction", """{"title":"Save","amount":100,"type":"TRANSFER","accountId":"$checking","toAccountId":"$savings","date":"$date"}""")["id"]!!.jsonPrimitive.content

        val rows = callOk("list_transactions", """{"startDate":"$date","endDate":"$date"}""")["transactions"]!!.jsonArray
        assertEquals(3, rows.size)
        val market = rows.single { it.jsonObject["title"]!!.jsonPrimitive.content == "Market" }.jsonObject
        assertEquals("Groceries", market["categoryName"]!!.jsonPrimitive.content)

        callOk("update_transaction", """{"id":"$transferId","amount":150}""")
        val legs = app.koin.get<PostingRepository>().getBetween(LocalDate.parse(date), LocalDate.parse("2026-09-16"))
            .filter { it.title == "Save" }
        assertEquals(listOf(150.0, 150.0), legs.map { it.amount })

        callOk("delete_transaction", """{"id":"$transferId"}""")
        assertEquals(1, callOk("list_transactions", """{"startDate":"$date","endDate":"$date"}""")["transactions"]!!.jsonArray.size)
    }

    @Test
    fun badArgumentsAreToolErrors() = runBlocking {
        val missing = call("record_transaction", """{"title":"x","amount":5,"type":"EXPENSE","accountId":"nope"}""")
        assertTrue(missing.isError)
        assertEquals("No account with id nope", missing.text)

        val negative = call("record_transaction", """{"title":"x","amount":-5,"type":"EXPENSE","accountId":"a"}""")
        assertTrue(negative.isError)

        val badDate = call("list_transactions", """{"startDate":"yesterday"}""")
        assertTrue(badDate.isError)
    }

    @Test
    fun schedulesAndOccurrences() = runBlocking {
        val checking = callOk("save_account", """{"name":"Checking","balance":1000}""")["account"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val start = today().plus(1, DateTimeUnit.DAY)
        val schedule = callOk(
            "save_schedule",
            """{"title":"Rent","amount":800,"type":"EXPENSE","accountId":"$checking","frequency":"MONTHLY_BY_DAY","startDate":"$start"}"""
        )
        val scheduleId = schedule["id"]!!.jsonPrimitive.content
        assertTrue(schedule["recurrence"]!!.jsonPrimitive.content.startsWith("Monthly on the"))

        val upcoming = callOk("list_upcoming")["occurrences"]!!.jsonArray
        val rent = upcoming.single().jsonObject
        assertEquals("due", rent["status"]!!.jsonPrimitive.content)
        assertEquals(start.toString(), rent["date"]!!.jsonPrimitive.content)

        assertEquals(200.0, callOk("project_balance", """{"date":"$start"}""")["balance"]!!.jsonPrimitive.double)

        callOk("change_occurrence_amount", """{"scheduleId":"$scheduleId","date":"$start","amount":750}""")
        callOk("mark_occurrence_paid", """{"scheduleId":"$scheduleId","date":"$start"}""")
        val paid = callOk("list_upcoming")["occurrences"]!!.jsonArray.single().jsonObject
        assertEquals("paid", paid["status"]!!.jsonPrimitive.content)
        assertEquals(750.0, paid["amount"]!!.jsonPrimitive.double)
        assertTrue(call("mark_occurrence_paid", """{"scheduleId":"$scheduleId","date":"$start"}""").isError)

        callOk("unmark_occurrence_paid", """{"scheduleId":"$scheduleId","date":"$start"}""")
        callOk("skip_occurrence", """{"scheduleId":"$scheduleId","date":"$start"}""")
        assertTrue(callOk("list_upcoming")["occurrences"]!!.jsonArray.isEmpty())

        // Partial update keeps the other fields.
        callOk("save_schedule", """{"id":"$scheduleId","amount":900}""")
        val saved = callOk("list_schedules")["schedules"]!!.jsonArray.single().jsonObject
        assertEquals("Rent", saved["title"]!!.jsonPrimitive.content)
        assertEquals(900.0, saved["amount"]!!.jsonPrimitive.double)

        // The skipped occurrence drops out of its month; the next month still has rent.
        val nextMonth = start.plus(1, DateTimeUnit.MONTH).let { YearMonth(it.year, it.month) }
        val budget = callOk("get_monthly_budget", """{"month":"$nextMonth"}""")
        assertEquals("Rent", budget["lines"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)

        callOk("delete_schedule", """{"id":"$scheduleId"}""")
        assertTrue(callOk("list_schedules")["schedules"]!!.jsonArray.isEmpty())
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private class Response(val result: JsonObject, val error: JsonObject?)

    private class ToolResult(val text: String, val isError: Boolean)

    private suspend fun request(method: String, params: String = "{}"): Response {
        val id = ++nextRequestId
        val raw = server.handle("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")!!
        val message = Json.parseToJsonElement(raw).jsonObject
        assertEquals(JsonPrimitive(id), message["id"])
        return Response(message["result"]?.jsonObject ?: JsonObject(emptyMap()), message["error"]?.jsonObject)
    }

    private suspend fun call(tool: String, arguments: String = "{}"): ToolResult {
        val response = request("tools/call", """{"name":"$tool","arguments":$arguments}""")
        val content = response.result["content"] as JsonArray
        return ToolResult(
            text = content.single().jsonObject["text"]!!.jsonPrimitive.content,
            isError = response.result["isError"]!!.jsonPrimitive.boolean
        )
    }

    private suspend fun callOk(tool: String, arguments: String = "{}"): JsonObject {
        val result = call(tool, arguments)
        assertTrue(!result.isError, "$tool failed: ${result.text}")
        return Json.parseToJsonElement(result.text).jsonObject
    }

    private object NoReceiptFiles : ReceiptFileStore {
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {}
        override suspend fun read(receiptId: String): ByteArray? = null
        override suspend fun hasFile(receiptId: String): Boolean = false
        override suspend fun delete(receiptId: String) {}
        override suspend fun clearAll() {}
        override suspend fun openExternally(receiptId: String, originalName: String) {}
    }
}
