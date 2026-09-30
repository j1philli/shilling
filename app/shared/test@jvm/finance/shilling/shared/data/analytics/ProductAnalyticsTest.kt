package finance.shilling.shared.data.analytics

import com.russhwolf.settings.Settings
import finance.shilling.shared.data.IdGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductAnalyticsTest {
    private val idGenerator = object : IdGenerator {
        override fun newId() = "random-installation-id"
    }

    @Test
    fun sendsNothingUntilConfiguredAndConsented() = runBlocking {
        val settings = isolatedSettings()
        var requests = 0
        val client = HttpClient(MockEngine) {
            engine { addHandler { requests++; respond("", HttpStatusCode.OK) } }
        }
        val analytics = ProductAnalytics(settings, idGenerator, client)
        analytics.capture(ProductEvent.ACCOUNT_CREATED)
        analytics.host = "https://posthog.example"
        analytics.projectToken = "phc_test"
        analytics.capture(ProductEvent.ACCOUNT_CREATED)
        assertEquals(0, requests)
        assertFalse(analytics.consent)
        client.close()
    }

    @Test
    fun sendsOnlyAllowlistedPropertiesAndIndependentId() = runBlocking {
        val settings = isolatedSettings()
        var payload = ""
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    payload = (request.body as TextContent).text
                    respond("", HttpStatusCode.OK)
                }
            }
        }
        val analytics = ProductAnalytics(settings, idGenerator, client)
        analytics.host = "https://posthog.example"
        analytics.projectToken = "phc_test"
        analytics.consent = true
        analytics.capture(ProductEvent.SCHEDULE_CREATED, "expense")

        val sent = Json.parseToJsonElement(payload).jsonObject
        assertEquals("random-installation-id", sent.getValue("distinct_id").jsonPrimitive.content)
        assertEquals("schedule_created", sent.getValue("event").jsonPrimitive.content)
        val properties = sent.getValue("properties").jsonObject
        assertEquals("expense", properties.getValue("type").jsonPrimitive.content)
        assertEquals("false", properties.getValue("\$process_person_profile").jsonPrimitive.content)
        assertEquals(2, properties.size)
        assertTrue(analytics.configured)
        client.close()
    }

    private fun isolatedSettings() = Settings().also { settings ->
        listOf("posthog_host", "posthog_project_token", "posthog_consent", "posthog_anonymous_id")
            .forEach(settings::remove)
    }
}
