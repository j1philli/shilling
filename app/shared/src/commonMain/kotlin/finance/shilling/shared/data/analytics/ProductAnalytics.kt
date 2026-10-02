package finance.shilling.shared.data.analytics

import com.russhwolf.settings.Settings
import finance.shilling.shared.data.IdGenerator
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val HOST_KEY = "posthog_host"
private const val TOKEN_KEY = "posthog_project_token"
private const val CONSENT_KEY = "posthog_consent"
private const val ANONYMOUS_ID_KEY = "posthog_anonymous_id"

// Public ingestion values for production project 633922. Local settings override them.
private const val DEFAULT_POSTHOG_HOST = "https://us.i.posthog.com"
private const val DEFAULT_POSTHOG_PROJECT_TOKEN = "phc_qD4tBfu3ZbwRjPgT2zqG3TsTh8rVLmvWsuVFGbzTfdkj"

/** Only these event names and fixed, non-financial properties may leave the device. */
enum class ProductEvent(val wireName: String) {
    ONBOARDING_COMPLETED("onboarding_completed"),
    ACCOUNT_CREATED("account_created"),
    SCHEDULE_CREATED("schedule_created"),
    POSTING_CREATED("posting_created"),
    CSV_IMPORT_COMPLETED("csv_import_completed"),
    RECEIPT_ATTACHED("receipt_attached")
}

/** Development consent and installation identity must not inherit production settings. */
data class ProductAnalyticsEnvironment(val developmentBuild: Boolean = false)

class ProductAnalytics(
    private val settings: Settings,
    private val idGenerator: IdGenerator,
    private val httpClient: HttpClient,
    environment: ProductAnalyticsEnvironment = ProductAnalyticsEnvironment()
) {
    private val consentKey = if (environment.developmentBuild) "posthog_development_consent" else CONSENT_KEY
    private val anonymousIdKey = if (environment.developmentBuild) "posthog_development_anonymous_id" else ANONYMOUS_ID_KEY
    private val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Dispatch independently of a screen that may close as soon as its action succeeds. */
    fun captureAsync(event: ProductEvent, kind: String? = null) {
        if (!consent || !configured) return
        deliveryScope.launch { capture(event, kind) }
    }

    var host: String
        get() = settings.getStringOrNull(HOST_KEY) ?: DEFAULT_POSTHOG_HOST
        set(value) = settings.putString(HOST_KEY, value.trim().trimEnd('/'))

    var projectToken: String
        get() = settings.getStringOrNull(TOKEN_KEY) ?: DEFAULT_POSTHOG_PROJECT_TOKEN
        set(value) = settings.putString(TOKEN_KEY, value.trim())

    var consent: Boolean
        get() = settings.getBoolean(consentKey, false)
        set(value) = settings.putBoolean(consentKey, value)

    val configured: Boolean
        get() = host.startsWith("https://") && projectToken.isNotBlank()

    suspend fun capture(event: ProductEvent, kind: String? = null) {
        if (!consent || !configured) return
        // An independent random installation ID; never use auth, device, or household IDs.
        val anonymousId = settings.getStringOrNull(anonymousIdKey)
            ?: idGenerator.newId().also { settings.putString(anonymousIdKey, it) }
        val properties = buildJsonObject {
            put("$" + "process_person_profile", false)
            if (event == ProductEvent.ONBOARDING_COMPLETED && kind in setOf("guest", "hosted", "self_hosted")) {
                put("mode", kind!!)
            }
            if (event == ProductEvent.SCHEDULE_CREATED && kind in setOf("expense", "income", "transfer")) {
                put("type", kind!!)
            }
        }
        val body = buildJsonObject {
            put("api_key", projectToken)
            put("event", event.wireName)
            put("distinct_id", anonymousId)
            put("properties", properties)
        }
        // Analytics is best effort and must never interrupt a budgeting action.
        try {
            httpClient.post("$host/i/v0/e/") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A telemetry outage must not change the result of the user action.
        }
    }
}
