package finance.shilling.shared.data.auth

import com.russhwolf.settings.Settings
import finance.shilling.core.auth.AuthMode
import finance.shilling.core.auth.ServerConfig
import finance.shilling.shared.data.DeploymentSelection
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_MODE
import finance.shilling.shared.data.SETTINGS_KEY_DEVICE_ID
import finance.shilling.shared.data.SETTINGS_KEY_HOSTED_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_LOCAL_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_SUPABASE_ANON_KEY
import finance.shilling.shared.data.SETTINGS_KEY_SUPABASE_URL
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class AuthBootstrapTest {
    @Test
    fun freshInstallWithServerOutageFallsBackToWaitingForServer() = runBlocking {
        val settings = isolatedSettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { throw IllegalStateException("offline") }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope,
                authServiceFactory = { _, _, deviceId, _, _ -> NoOpAuthService(deviceId) }
            )

            assertEquals(HostedBootstrapPhase.WAITING_FOR_SERVER, resolution.identity.bootstrapStatus.phase)
            assertEquals(DependencyReachability.UNREACHABLE, resolution.identity.bootstrapStatus.serverReachability)
            assertFalse(resolution.identity.bootstrapStatus.syncReady)
            assertEquals("id-1", resolution.identity.activeHouseholdId)
            assertEquals("id-1", resolution.identity.localHouseholdId)
            assertNull(resolution.identity.hostedHouseholdId)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    @Test
    fun cachedHostedConfigWithSupabaseOutageStaysUsable() = runBlocking {
        val settings = hostedSettings()
        val authService = FakeAuthService(
            deviceId = "device-1",
            initialState = unauthenticatedState("device-1"),
            ensureResult = Result.failure(IllegalStateException("supabase unavailable"))
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { throw IllegalStateException("server offline") }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope,
                authServiceFactory = { _, _, _, _, _ -> authService }
            )

            assertEquals(HostedBootstrapPhase.WAITING_FOR_SUPABASE, resolution.identity.bootstrapStatus.phase)
            assertEquals(DependencyReachability.UNREACHABLE, resolution.identity.bootstrapStatus.serverReachability)
            assertEquals(DependencyReachability.UNREACHABLE, resolution.identity.bootstrapStatus.supabaseReachability)
            assertFalse(resolution.identity.bootstrapStatus.syncReady)
            assertEquals("id-1", resolution.identity.activeHouseholdId)
            assertNull(resolution.identity.hostedHouseholdId)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    @Test
    fun cachedHostedHouseholdDoesNotReplaceLocalHouseholdWhenFetchFails() = runBlocking {
        val settings = hostedSettings().also {
            it.putString(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID, "hosted-cached")
        }
        val authService = FakeAuthService(
            deviceId = "device-1",
            initialState = anonymousAuthenticatedState("device-1")
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { request ->
            when (request.url.encodedPath) {
                "/api/config" -> jsonResponse("""{"authMode":"SUPABASE","supabaseUrl":"https://supabase.test","supabaseAnonKey":"anon-key"}""")
                "/api/household" -> jsonResponse("""{"error":"unavailable"}""", HttpStatusCode.ServiceUnavailable)
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope,
                authServiceFactory = { _, _, _, _, _ -> authService }
            )

            assertEquals(HostedBootstrapPhase.WAITING_FOR_HOUSEHOLD, resolution.identity.bootstrapStatus.phase)
            assertEquals(DependencyReachability.REACHABLE, resolution.identity.bootstrapStatus.serverReachability)
            assertEquals(DependencyReachability.REACHABLE, resolution.identity.bootstrapStatus.supabaseReachability)
            assertFalse(resolution.identity.bootstrapStatus.syncReady)
            assertEquals("id-1", resolution.identity.activeHouseholdId)
            assertEquals("id-1", resolution.identity.localHouseholdId)
            assertEquals("hosted-cached", resolution.identity.hostedHouseholdId)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    @Test
    fun selfHostedConfigStillResolvesLocalOnly() = runBlocking {
        val settings = isolatedSettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { request ->
            when (request.url.encodedPath) {
                "/api/config" -> jsonResponse("""{"authMode":"NONE"}""")
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope
            )

            assertEquals(HostedBootstrapPhase.LOCAL_ONLY, resolution.identity.bootstrapStatus.phase)
            assertEquals(DependencyReachability.REACHABLE, resolution.identity.bootstrapStatus.serverReachability)
            assertFalse(resolution.identity.serverConfig.authMode == AuthMode.SUPABASE)
            assertEquals("id-1", resolution.identity.activeHouseholdId)
            assertEquals("id-1", resolution.identity.localHouseholdId)
            assertEquals(true, resolution.identity.bootstrapStatus.syncReady)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    @Test
    fun explicitSelfHostedSelectionStaysLocalOnlyDuringOutage() = runBlocking {
        val settings = hostedSettings().also {
            it.putString(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID, "hosted-cached")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { throw IllegalStateException("server offline") }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope,
                deploymentSelection = DeploymentSelection.SELF_HOSTED
            )

            assertEquals(AuthMode.NONE, resolution.identity.serverConfig.authMode)
            assertEquals(HostedBootstrapPhase.LOCAL_ONLY, resolution.identity.bootstrapStatus.phase)
            assertEquals(DependencyReachability.UNREACHABLE, resolution.identity.bootstrapStatus.serverReachability)
            assertEquals(true, resolution.identity.bootstrapStatus.syncReady)
            assertEquals("id-1", resolution.identity.activeHouseholdId)
            assertEquals("id-1", resolution.identity.localHouseholdId)
            assertEquals("hosted-cached", resolution.identity.hostedHouseholdId)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    @Test
    fun loginModeFailsFastWithRetryableStateDuringOutage() = runBlocking {
        val settings = hostedSettings()
        val authService = FakeAuthService(
            deviceId = "device-1",
            initialState = unauthenticatedState("device-1")
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { throw IllegalStateException("server offline") }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope,
                sessionRequirement = HostedSessionRequirement.ACCOUNT_REQUIRED,
                authServiceFactory = { _, _, _, _, _ -> authService }
            )

            assertEquals(HostedBootstrapPhase.LOGIN_REQUIRED, resolution.identity.bootstrapStatus.phase)
            assertEquals(DependencyReachability.UNREACHABLE, resolution.identity.bootstrapStatus.serverReachability)
            assertFalse(resolution.identity.bootstrapStatus.syncReady)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    @Test
    fun accountRequiredRejectsAuthenticatedAnonymousSession() = runBlocking {
        val settings = hostedSettings()
        val authService = FakeAuthService(
            deviceId = "device-1",
            initialState = anonymousAuthenticatedState("device-1")
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { request ->
            when (request.url.encodedPath) {
                "/api/config" -> jsonResponse("""{"authMode":"SUPABASE","supabaseUrl":"https://supabase.test","supabaseAnonKey":"anon-key"}""")
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope,
                sessionRequirement = HostedSessionRequirement.ACCOUNT_REQUIRED,
                authServiceFactory = { _, _, _, _, _ -> authService }
            )

            assertEquals(HostedBootstrapPhase.LOGIN_REQUIRED, resolution.identity.bootstrapStatus.phase)
            assertEquals(DependencyReachability.REACHABLE, resolution.identity.bootstrapStatus.serverReachability)
            assertFalse(resolution.identity.bootstrapStatus.syncReady)
            assertEquals("id-1", resolution.identity.activeHouseholdId)
            assertNull(resolution.identity.hostedHouseholdId)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    @Test
    fun accountRequiredAcceptsAuthenticatedAccountSession() = runBlocking {
        val settings = hostedSettings()
        val authService = FakeAuthService(
            deviceId = "device-1",
            initialState = accountAuthenticatedState("device-1")
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { request ->
            when (request.url.encodedPath) {
                "/api/config" -> jsonResponse("""{"authMode":"SUPABASE","supabaseUrl":"https://supabase.test","supabaseAnonKey":"anon-key"}""")
                "/api/household" -> jsonResponse("""{"householdId":"hosted-1"}""")
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope,
                sessionRequirement = HostedSessionRequirement.ACCOUNT_REQUIRED,
                authServiceFactory = { _, _, _, _, _ -> authService }
            )

            assertEquals(HostedBootstrapPhase.READY, resolution.identity.bootstrapStatus.phase)
            assertEquals(DependencyReachability.REACHABLE, resolution.identity.bootstrapStatus.serverReachability)
            assertEquals("hosted-1", resolution.identity.activeHouseholdId)
            assertEquals("hosted-1", resolution.identity.hostedHouseholdId)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    @Test
    fun authRuntimeIsReusedWhileConfigStaysTheSame() = runBlocking {
        val settings = isolatedSettings()
        val authService = FakeAuthService(
            deviceId = "device-1",
            initialState = anonymousAuthenticatedState("device-1")
        )
        val existingRuntime = AuthRuntime(
            serverConfig = ServerConfig(
                authMode = AuthMode.SUPABASE,
                supabaseUrl = "https://supabase.test",
                supabaseAnonKey = "anon-key"
            ),
            authService = authService
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val httpClient = mockHttpClient { request ->
            when (request.url.encodedPath) {
                "/api/config" -> jsonResponse("""{"authMode":"SUPABASE","supabaseUrl":"https://supabase.test","supabaseAnonKey":"anon-key"}""")
                "/api/household" -> jsonResponse("""{"householdId":"hosted-1"}""")
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        try {
            val resolution = resolveStartupIdentity(
                httpClient = httpClient,
                serverUrl = "https://example.test",
                settings = settings,
                idGenerator = TestIdGenerator(),
                deviceId = "device-1",
                scope = scope,
                currentAuthRuntime = existingRuntime,
                authServiceFactory = { _, _, _, _, _ ->
                    error("Auth service should not be recreated when config is unchanged")
                }
            )

            assertSame(authService, resolution.authRuntime.authService)
            assertEquals(HostedBootstrapPhase.READY, resolution.identity.bootstrapStatus.phase)
            assertEquals("hosted-1", resolution.identity.activeHouseholdId)
            assertEquals("hosted-1", resolution.identity.hostedHouseholdId)
        } finally {
            httpClient.close()
            scope.cancel()
        }
    }

    private fun mockHttpClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request -> handler(request) }
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

    private fun hostedSettings(): Settings = isolatedSettings().also {
        it.putString(SETTINGS_KEY_AUTH_MODE, AuthMode.SUPABASE.name)
        it.putString(SETTINGS_KEY_SUPABASE_URL, "https://supabase.test")
        it.putString(SETTINGS_KEY_SUPABASE_ANON_KEY, "anon-key")
    }

    private fun isolatedSettings(): Settings = Settings().also {
        listOf(
            SETTINGS_KEY_AUTH_MODE,
            SETTINGS_KEY_SUPABASE_URL,
            SETTINGS_KEY_SUPABASE_ANON_KEY,
            SETTINGS_KEY_LOCAL_HOUSEHOLD_ID,
            SETTINGS_KEY_HOSTED_HOUSEHOLD_ID,
            SETTINGS_KEY_DEVICE_ID
        ).forEach(it::remove)
    }

    private fun unauthenticatedState(deviceId: String) = AuthState(
        isAuthenticated = false,
        userId = null,
        email = null,
        tier = UserTier.ANONYMOUS,
        accessToken = null,
        isAnonymous = true,
        deviceId = deviceId
    )

    private fun anonymousAuthenticatedState(deviceId: String) = AuthState(
        isAuthenticated = true,
        userId = "user-1",
        email = null,
        tier = UserTier.ANONYMOUS,
        accessToken = "token-1",
        isAnonymous = true,
        deviceId = deviceId
    )

    private fun accountAuthenticatedState(deviceId: String) = AuthState(
        isAuthenticated = true,
        userId = "user-1",
        email = "user@example.com",
        tier = UserTier.FREE,
        accessToken = "token-1",
        isAnonymous = false,
        deviceId = deviceId
    )

    private class TestIdGenerator : IdGenerator {
        private var nextId = 0

        override fun newId(): String {
            nextId += 1
            return "id-$nextId"
        }
    }

    private class FakeAuthService(
        deviceId: String,
        initialState: AuthState,
        private val ensureResult: Result<Unit> = Result.success(Unit)
    ) : AuthService {
        override val authState = MutableStateFlow(initialState)

        override suspend fun ensureAuthenticated(): Result<Unit> {
            if (ensureResult.isSuccess) {
                authState.value = authState.value.copy(
                    isAuthenticated = true,
                    userId = authState.value.userId ?: "user-1",
                    accessToken = authState.value.accessToken ?: "token-1"
                )
            }
            return ensureResult
        }

        override suspend fun signUp(email: String, password: String): Result<SignUpResult> =
            Result.failure(UnsupportedOperationException("Not used in tests"))

        override suspend fun signIn(email: String, password: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("Not used in tests"))

        override suspend fun signOut() {}

        override suspend fun deleteAccount(): Result<Unit> =
            Result.failure(UnsupportedOperationException("Not used in tests"))

        override suspend fun refreshTokenIfNeeded(): String? = authState.value.accessToken
    }
}
