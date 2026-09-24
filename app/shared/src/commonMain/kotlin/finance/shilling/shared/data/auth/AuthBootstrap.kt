package finance.shilling.shared.data.auth

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import finance.shilling.core.auth.AuthMode
import finance.shilling.core.auth.ServerConfig
import finance.shilling.shared.data.DeploymentSelection
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.SETTINGS_KEY_AUTH_MODE
import finance.shilling.shared.data.SETTINGS_KEY_HOSTED_HOUSEHOLD_ID
import finance.shilling.shared.data.SETTINGS_KEY_SUPABASE_ANON_KEY
import finance.shilling.shared.data.SETTINGS_KEY_SUPABASE_URL
import finance.shilling.shared.data.sync.ServerApi
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

private val log = Logger.withTag("AuthBootstrap")

enum class DependencyReachability {
    UNKNOWN,
    REACHABLE,
    UNREACHABLE
}

enum class HostedSessionRequirement {
    GUEST_ALLOWED,
    ACCOUNT_REQUIRED
}

enum class HostedBootstrapPhase {
    LOCAL_ONLY,
    WAITING_FOR_SERVER,
    WAITING_FOR_SUPABASE,
    WAITING_FOR_HOUSEHOLD,
    READY,
    LOGIN_REQUIRED
}

data class HostedBootstrapStatus(
    val phase: HostedBootstrapPhase,
    val serverReachability: DependencyReachability = DependencyReachability.UNKNOWN,
    val supabaseReachability: DependencyReachability = DependencyReachability.UNKNOWN,
    val syncReady: Boolean = false,
    val lastError: String? = null,
    val isChecking: Boolean = false
)

fun resolveEffectiveHostedBootstrapStatus(
    authoritativeStatus: HostedBootstrapStatus,
    liveStatus: HostedBootstrapStatus
): HostedBootstrapStatus =
    if (authoritativeStatus.matchesSnapshotOf(liveStatus)) {
        liveStatus
    } else {
        authoritativeStatus
    }

private fun HostedBootstrapStatus.matchesSnapshotOf(other: HostedBootstrapStatus): Boolean =
    phase == other.phase &&
        serverReachability == other.serverReachability &&
        supabaseReachability == other.supabaseReachability &&
        syncReady == other.syncReady &&
        lastError == other.lastError

data class StartupIdentity(
    val serverConfig: ServerConfig,
    val authService: AuthService,
    val activeHouseholdId: String,
    val localHouseholdId: String,
    val hostedHouseholdId: String?,
    val bootstrapStatus: HostedBootstrapStatus
)

data class AuthRuntime(
    val serverConfig: ServerConfig,
    val authService: AuthService
)

data class StartupStateResolution(
    val identity: StartupIdentity,
    val authRuntime: AuthRuntime
)

class HostedBootstrapState(val status: StateFlow<HostedBootstrapStatus>)

class HostedBootstrapRetryCallback(val onRetry: () -> Unit)

fun AuthState.satisfiesHostedSessionRequirement(requirement: HostedSessionRequirement): Boolean =
    when (requirement) {
        HostedSessionRequirement.GUEST_ALLOWED -> isAuthenticated
        HostedSessionRequirement.ACCOUNT_REQUIRED -> isAuthenticated && !isAnonymous
    }

typealias AuthServiceFactory = (
    serverConfig: ServerConfig,
    settings: Settings,
    deviceId: String,
    scope: CoroutineScope,
    httpClient: HttpClient
) -> AuthService

private data class ConfigResolution(
    val serverConfig: ServerConfig,
    val serverReachability: DependencyReachability,
    val lastError: String? = null
)

fun cachedServerConfig(settings: Settings): ServerConfig {
    val authMode = settings.getStringOrNull(SETTINGS_KEY_AUTH_MODE)
        ?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() }
        ?: AuthMode.NONE
    return ServerConfig(
        authMode = authMode,
        supabaseUrl = settings.getStringOrNull(SETTINGS_KEY_SUPABASE_URL),
        supabaseAnonKey = settings.getStringOrNull(SETTINGS_KEY_SUPABASE_ANON_KEY)
    )
}

fun cacheServerConfig(settings: Settings, config: ServerConfig) {
    settings.putString(SETTINGS_KEY_AUTH_MODE, config.authMode.name)
    config.supabaseUrl?.let { settings.putString(SETTINGS_KEY_SUPABASE_URL, it) }
    config.supabaseAnonKey?.let { settings.putString(SETTINGS_KEY_SUPABASE_ANON_KEY, it) }
}

fun createAuthService(
    serverConfig: ServerConfig,
    settings: Settings,
    deviceId: String,
    scope: CoroutineScope,
    httpClient: HttpClient
): AuthService =
    if (serverConfig.authMode == AuthMode.SUPABASE) {
        val url = serverConfig.supabaseUrl.orEmpty()
        val key = serverConfig.supabaseAnonKey.orEmpty()
        if (url.isNotBlank() && key.isNotBlank()) {
            SupabaseAuthService(url, key, deviceId, settings, scope, httpClient)
        } else {
            log.w { "Hosted auth selected but Supabase config is incomplete; disabling hosted auth for this session" }
            NoOpAuthService(deviceId)
        }
    } else {
        NoOpAuthService(deviceId)
    }

fun cachedHostedHouseholdIdOrNull(settings: Settings): String? =
    settings.getStringOrNull(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID)

fun createPlaceholderStartupIdentity(
    settings: Settings,
    idGenerator: IdGenerator,
    deviceId: String,
    expectHosted: Boolean
): StartupIdentity {
    val localHouseholdId = resolveLocalHouseholdId(settings, idGenerator)
    return StartupIdentity(
        serverConfig = if (expectHosted) {
            ServerConfig(authMode = AuthMode.SUPABASE)
        } else {
            ServerConfig(authMode = AuthMode.NONE)
        },
        authService = NoOpAuthService(deviceId),
        activeHouseholdId = localHouseholdId,
        localHouseholdId = localHouseholdId,
        hostedHouseholdId = cachedHostedHouseholdIdOrNull(settings),
        bootstrapStatus = HostedBootstrapStatus(
            phase = if (expectHosted) {
                HostedBootstrapPhase.WAITING_FOR_SERVER
            } else {
                HostedBootstrapPhase.LOCAL_ONLY
            },
            syncReady = !expectHosted
        )
    )
}

suspend fun resolveStartupIdentity(
    httpClient: HttpClient,
    serverUrl: String,
    settings: Settings,
    idGenerator: IdGenerator,
    deviceId: String,
    scope: CoroutineScope,
    deploymentSelection: DeploymentSelection? = null,
    sessionRequirement: HostedSessionRequirement = HostedSessionRequirement.GUEST_ALLOWED,
    currentAuthRuntime: AuthRuntime? = null,
    authServiceFactory: AuthServiceFactory = ::createAuthService
): StartupStateResolution {
    val localHouseholdId = resolveLocalHouseholdId(settings, idGenerator)
    val cachedHostedHouseholdId = cachedHostedHouseholdIdOrNull(settings)
    val configResolution = resolveServerConfig(
        httpClient = httpClient,
        serverUrl = serverUrl,
        settings = settings,
        deploymentSelection = deploymentSelection
    )
    val authRuntime = createOrReuseAuthRuntime(
        currentAuthRuntime = currentAuthRuntime,
        serverConfig = configResolution.serverConfig,
        settings = settings,
        deviceId = deviceId,
        scope = scope,
        httpClient = httpClient,
        authServiceFactory = authServiceFactory
    )
    val authService = authRuntime.authService

    if (configResolution.serverConfig.authMode != AuthMode.SUPABASE) {
        return StartupStateResolution(
            identity = StartupIdentity(
                serverConfig = configResolution.serverConfig,
                authService = authService,
                activeHouseholdId = localHouseholdId,
                localHouseholdId = localHouseholdId,
                hostedHouseholdId = cachedHostedHouseholdId,
                bootstrapStatus = HostedBootstrapStatus(
                    phase = HostedBootstrapPhase.LOCAL_ONLY,
                    serverReachability = configResolution.serverReachability,
                    supabaseReachability = DependencyReachability.UNKNOWN,
                    syncReady = true,
                    lastError = configResolution.lastError
                )
            ),
            authRuntime = authRuntime
        )
    }

    if (!hasUsableSupabaseConfig(configResolution.serverConfig)) {
        return StartupStateResolution(
            identity = StartupIdentity(
                serverConfig = configResolution.serverConfig,
                authService = authService,
                activeHouseholdId = localHouseholdId,
                localHouseholdId = localHouseholdId,
                hostedHouseholdId = cachedHostedHouseholdId,
                bootstrapStatus = HostedBootstrapStatus(
                    phase = HostedBootstrapPhase.WAITING_FOR_SERVER,
                    serverReachability = configResolution.serverReachability,
                    supabaseReachability = DependencyReachability.UNKNOWN,
                    syncReady = false,
                    lastError = configResolution.lastError ?: "Managed auth config is not available yet."
                )
            ),
            authRuntime = authRuntime
        )
    }

    if (!authService.authState.value.satisfiesHostedSessionRequirement(sessionRequirement)) {
        if (sessionRequirement == HostedSessionRequirement.ACCOUNT_REQUIRED) {
            return StartupStateResolution(
                identity = StartupIdentity(
                    serverConfig = configResolution.serverConfig,
                    authService = authService,
                    activeHouseholdId = localHouseholdId,
                    localHouseholdId = localHouseholdId,
                    hostedHouseholdId = cachedHostedHouseholdId,
                    bootstrapStatus = HostedBootstrapStatus(
                        phase = HostedBootstrapPhase.LOGIN_REQUIRED,
                        serverReachability = configResolution.serverReachability,
                        supabaseReachability = DependencyReachability.UNKNOWN,
                        syncReady = false,
                        lastError = configResolution.lastError
                    )
                ),
                authRuntime = authRuntime
            )
        }
        val authResult = authService.ensureAuthenticated()
        if (authResult.isFailure || !authService.authState.value.satisfiesHostedSessionRequirement(sessionRequirement)) {
            val error = authResult.exceptionOrNull()
            log.w { "Hosted auth did not reach an authenticated state; signaling startup will stay disabled" }
            return StartupStateResolution(
                identity = StartupIdentity(
                    serverConfig = configResolution.serverConfig,
                    authService = authService,
                    activeHouseholdId = localHouseholdId,
                    localHouseholdId = localHouseholdId,
                    hostedHouseholdId = cachedHostedHouseholdId,
                    bootstrapStatus = HostedBootstrapStatus(
                        phase = HostedBootstrapPhase.WAITING_FOR_SUPABASE,
                        serverReachability = configResolution.serverReachability,
                        supabaseReachability = DependencyReachability.UNREACHABLE,
                        syncReady = false,
                        lastError = errorMessage(
                            configResolution.lastError,
                            error,
                            "Couldn't restore the managed guest session."
                        )
                    )
                ),
                authRuntime = authRuntime
            )
        }
    }

    val authenticatedApi = ServerApi(httpClient, serverUrl, authService)
    val householdId = runCatching {
        authenticatedApi.fetchHousehold().householdId.also {
            settings.putString(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID, it)
        }
    }.getOrElse { error ->
        log.w { "Hosted household lookup failed: ${error.message}" }
        return StartupStateResolution(
            identity = StartupIdentity(
                serverConfig = configResolution.serverConfig,
                authService = authService,
                activeHouseholdId = localHouseholdId,
                localHouseholdId = localHouseholdId,
                hostedHouseholdId = cachedHostedHouseholdId,
                bootstrapStatus = HostedBootstrapStatus(
                    phase = HostedBootstrapPhase.WAITING_FOR_HOUSEHOLD,
                    serverReachability = resolveReachability(error),
                    supabaseReachability = DependencyReachability.REACHABLE,
                    syncReady = false,
                    lastError = errorMessage(
                        configResolution.lastError,
                        error,
                        "Couldn't load the managed household."
                    )
                )
            ),
            authRuntime = authRuntime
        )
    }

    return StartupStateResolution(
        identity = StartupIdentity(
            serverConfig = configResolution.serverConfig,
            authService = authService,
            activeHouseholdId = householdId,
            localHouseholdId = localHouseholdId,
            hostedHouseholdId = householdId,
            bootstrapStatus = HostedBootstrapStatus(
                phase = HostedBootstrapPhase.READY,
                serverReachability = DependencyReachability.REACHABLE,
                supabaseReachability = DependencyReachability.REACHABLE,
                syncReady = true,
                lastError = null
            )
        ),
        authRuntime = authRuntime
    )
}

private suspend fun resolveServerConfig(
    httpClient: HttpClient,
    serverUrl: String,
    settings: Settings,
    deploymentSelection: DeploymentSelection?
): ConfigResolution {
    val bootstrapApi = ServerApi(httpClient, serverUrl)
    val explicitSelfHosted = deploymentSelection == DeploymentSelection.SELF_HOSTED
    return runCatching {
        bootstrapApi.fetchConfig().also { cacheServerConfig(settings, it) }
    }.fold(
        onSuccess = { config ->
            if (explicitSelfHosted && config.authMode != AuthMode.NONE) {
                ConfigResolution(
                    serverConfig = ServerConfig(authMode = AuthMode.NONE),
                    serverReachability = DependencyReachability.REACHABLE,
                    lastError = "This server requires managed auth. Use the hosted flow instead."
                )
            } else {
                ConfigResolution(
                    serverConfig = config,
                    serverReachability = DependencyReachability.REACHABLE
                )
            }
        },
        onFailure = { error ->
            if (explicitSelfHosted) {
                return@fold ConfigResolution(
                    serverConfig = ServerConfig(authMode = AuthMode.NONE),
                    serverReachability = resolveReachability(error),
                    lastError = errorMessage(
                        previousMessage = null,
                        error = error,
                        defaultMessage = "Couldn't reach the self-hosted server."
                    )
                )
            }
            val hasCachedConfig = settings.getStringOrNull(SETTINGS_KEY_AUTH_MODE) != null
            if (!hasCachedConfig) {
                log.e { "Config fetch failed for $serverUrl with no cached config: ${error.message}" }
                ConfigResolution(
                    serverConfig = ServerConfig(authMode = AuthMode.SUPABASE),
                    serverReachability = resolveReachability(error),
                    lastError = errorMessage(
                        previousMessage = null,
                        error = error,
                        defaultMessage = "Couldn't reach the managed server."
                    )
                )
            } else {
                val cached = cachedServerConfig(settings)
                log.w { "Config fetch failed for $serverUrl: ${error.message}; using cached authMode=${cached.authMode}" }
                ConfigResolution(
                    serverConfig = cached,
                    serverReachability = resolveReachability(error),
                    lastError = errorMessage(
                        previousMessage = null,
                        error = error,
                        defaultMessage = "Couldn't refresh the managed server config."
                    )
                )
            }
        }
    )
}

private fun createOrReuseAuthRuntime(
    currentAuthRuntime: AuthRuntime?,
    serverConfig: ServerConfig,
    settings: Settings,
    deviceId: String,
    scope: CoroutineScope,
    httpClient: HttpClient,
    authServiceFactory: AuthServiceFactory
): AuthRuntime {
    if (currentAuthRuntime?.serverConfig == serverConfig) {
        return currentAuthRuntime
    }
    return AuthRuntime(
        serverConfig = serverConfig,
        authService = authServiceFactory(serverConfig, settings, deviceId, scope, httpClient)
    )
}

private fun hasUsableSupabaseConfig(serverConfig: ServerConfig): Boolean =
    !serverConfig.supabaseUrl.isNullOrBlank() && !serverConfig.supabaseAnonKey.isNullOrBlank()

private fun resolveReachability(error: Throwable): DependencyReachability =
    if (error is ResponseException) {
        DependencyReachability.REACHABLE
    } else {
        DependencyReachability.UNREACHABLE
    }

private fun errorMessage(
    previousMessage: String?,
    error: Throwable?,
    defaultMessage: String
): String {
    val baseMessage = error?.message?.takeIf { it.isNotBlank() } ?: defaultMessage
    return if (previousMessage.isNullOrBlank()) {
        baseMessage
    } else {
        "$previousMessage $baseMessage"
    }
}
