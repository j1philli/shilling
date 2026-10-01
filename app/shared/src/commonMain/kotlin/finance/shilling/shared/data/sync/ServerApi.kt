package finance.shilling.shared.data.sync

import finance.shilling.shared.data.auth.AuthService
import finance.shilling.core.auth.HostedHouseholdResponse
import finance.shilling.core.auth.HostedEntitlementsResponse
import finance.shilling.core.auth.HostedBillingConfig
import finance.shilling.core.auth.RegisteredDevice
import finance.shilling.core.auth.ServerConfig
import finance.shilling.core.sync.IceServersResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.encodeURLPathPart
import io.ktor.http.contentType
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

const val BOOTSTRAP_NETWORK_TIMEOUT_MS = 3_000L

class ServerApi(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authService: AuthService? = null
) {
    private suspend fun HttpRequestBuilder.withAuth() {
        authService?.refreshTokenIfNeeded()?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    suspend fun fetchIceServers(allowRelay: Boolean = false): IceServersResponse =
        httpClient.get("$baseUrl/api/ice-servers?allowRelay=$allowRelay") {
            expectSuccess = true
            withAuth()
        }.body()

    suspend fun fetchConfig(): ServerConfig =
        httpClient.get("$baseUrl/api/config") {
            expectSuccess = true
        }.body()

    suspend fun fetchHousehold(): HostedHouseholdResponse =
        httpClient.get("$baseUrl/api/household") {
            expectSuccess = true
            withAuth()
        }.body()

    suspend fun fetchEntitlements(): HostedEntitlementsResponse =
        httpClient.get("$baseUrl/api/tier?refresh=true") {
            expectSuccess = true
            withAuth()
        }.body()

    suspend fun fetchBillingConfig(): HostedBillingConfig =
        httpClient.get("$baseUrl/api/billing/config") {
            expectSuccess = true
            withAuth()
        }.body()

    suspend fun fetchSpaces(): finance.shilling.core.auth.HostedSpacesResponse =
        httpClient.get("$baseUrl/api/spaces") { expectSuccess = true; withAuth() }.body()

    suspend fun spaceCommand(command: finance.shilling.core.auth.HostedSpaceCommand): finance.shilling.core.auth.HostedSpacesResponse =
        httpClient.post("$baseUrl/api/spaces") {
            expectSuccess = true; withAuth()
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(command)
        }.body()

    suspend fun fetchDevices(): List<String> =
        httpClient.get("$baseUrl/api/devices") {
            expectSuccess = true
            withAuth()
        }.body()

    suspend fun fetchDeviceDetails(): List<RegisteredDevice> =
        httpClient.get("$baseUrl/api/devices/details") {
            expectSuccess = true
            withAuth()
        }.body()

    suspend fun removeDevice(deviceId: String) {
        httpClient.delete("$baseUrl/api/devices/${deviceId.encodeURLPathPart()}") {
            expectSuccess = true
            withAuth()
        }
    }
}

fun createSyncHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(WebSockets) {
        pingIntervalMillis = 10_000 // Client-side ping to keep connection alive
    }
    install(HttpTimeout) {
        connectTimeoutMillis = BOOTSTRAP_NETWORK_TIMEOUT_MS
        requestTimeoutMillis = BOOTSTRAP_NETWORK_TIMEOUT_MS
        socketTimeoutMillis = BOOTSTRAP_NETWORK_TIMEOUT_MS
    }
}
