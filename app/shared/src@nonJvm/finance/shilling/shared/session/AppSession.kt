package finance.shilling.shared.session

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import finance.shilling.core.auth.AuthMode
import finance.shilling.shared.data.DEFAULT_SELF_HOSTED_SERVER_URL
import finance.shilling.shared.data.DEFAULT_SERVER_URL
import finance.shilling.shared.data.DeploymentSelection
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.LocalDataWiper
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.SETTINGS_KEY_CLOUD_RELAY_ENABLED
import finance.shilling.shared.data.auth.AuthRuntime
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.data.auth.FeatureGate
import finance.shilling.shared.data.auth.HostedBootstrapLoop
import finance.shilling.shared.data.auth.HostedBootstrapState
import finance.shilling.shared.data.auth.HostedSessionRequirement
import finance.shilling.shared.data.auth.NoOpAuthService
import finance.shilling.shared.data.auth.StartupIdentity
import finance.shilling.shared.data.auth.StartupStateResolution
import finance.shilling.shared.data.auth.createPlaceholderStartupIdentity
import finance.shilling.shared.data.auth.createAuthServiceWithRedirect
import finance.shilling.shared.data.auth.hostedBootstrapRetryDelay
import finance.shilling.shared.data.auth.resolveEffectiveHostedBootstrapStatus
import finance.shilling.shared.data.auth.resolveStartupIdentity
import finance.shilling.shared.data.clearWelcomeHoldState
import finance.shilling.shared.data.completeFirstLaunchOnboarding
import finance.shilling.shared.data.hadPersistedAccountSession
import finance.shilling.shared.data.hasHeldLocalData
import finance.shilling.shared.data.matchesPendingRestore
import finance.shilling.shared.data.resolveDeploymentSelection
import finance.shilling.shared.data.resolveHostedSessionRequirement
import finance.shilling.shared.data.resolveOnboardingRouting
import finance.shilling.shared.data.savedDeploymentSelection
import finance.shilling.shared.data.shouldShowFirstLaunchOnboarding
import finance.shilling.shared.data.softReturnToWelcome
import finance.shilling.shared.data.welcomeNotice
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.StoreSyncDeps
import finance.shilling.shared.data.store.SyncState
import finance.shilling.shared.data.store.SyncStoreFacade
import finance.shilling.shared.data.sync.FileTransferManager
import finance.shilling.shared.data.sync.PeerConnectionStatus
import finance.shilling.shared.data.sync.IncomingChangeRouter
import finance.shilling.shared.data.sync.ServerApi
import finance.shilling.shared.data.sync.SignalingClient
import finance.shilling.shared.data.sync.SyncConfig
import finance.shilling.shared.data.sync.WebRtcConnectionManager
import finance.shilling.shared.data.sync.WebRtcPlatform
import io.ktor.client.HttpClient
import io.ktor.client.webrtc.WebRtc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val MANUAL_RETRY_MIN_LOADING_MS = 2_000L
private const val SELF_HOSTED_MODE_MISMATCH_MESSAGE =
    "This server requires managed auth. Use the hosted flow instead."

/** Platform options for [AppSession]; bind one in the platform Koin module to override. */
data class AppSessionConfig(
    /** Self-hosted distribution (web served by a homelab server): no managed sign-in. */
    val selfHostedOnly: Boolean = false,
    val defaultSelfHostedServerUrl: String = DEFAULT_SELF_HOSTED_SERVER_URL,
    val logTag: String = "AppSession",
    /** Mobile callback URL for email verification. Web and desktop use the Supabase Site URL. */
    val authRedirectUrl: String? = null,
    /** Runs the session state machine. Main everywhere: `delay` works there on iOS. */
    val dispatcher: CoroutineDispatcher = Dispatchers.Main
)

private class SyncRuntime(
    val signalingClient: SignalingClient,
    val peerSyncManager: WebRtcConnectionManager,
    val incomingChangeRouter: IncomingChangeRouter
)

/**
 * App lifecycle outside any UI: onboarding state, hosted bootstrap (auth + household), and the
 * peer-to-peer sync runtime. One per process; every UI (Compose, SwiftUI) renders [phase] and
 * calls the actions below.
 *
 * Ported from the former Compose bootstrap: [reconcile] re-evaluates everything after each state
 * change, and each [Effect] restarts when its key changes, like `LaunchedEffect` /
 * `DisposableEffect`. All state is confined to [AppSessionConfig.dispatcher].
 */
class AppSession(
    private val settings: Settings,
    private val idGenerator: IdGenerator,
    private val httpClient: HttpClient,
    private val deviceIdentity: DeviceIdentity,
    private val graphs: finance.shilling.shared.data.store.FinanceSpaceGraphs,
    private val webRtcPlatform: WebRtcPlatform,
    private val notifier: ChangeNotifier,
    private val peerConnectionStatus: PeerConnectionStatus,
    private val config: AppSessionConfig = AppSessionConfig()
) : SessionState, OnboardingActions {
    private val log = Logger.withTag(config.logTag)
    private val syncExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.e { "Sync uncaught exception: ${throwable::class.simpleName ?: "?"}: ${throwable.message ?: "?"}" }
    }
    private val scope = CoroutineScope(SupervisorJob() + config.dispatcher)
    private val deviceId: String get() = deviceIdentity.deviceId
    private val hostedBootstrapLoop = HostedBootstrapLoop(::hostedBootstrapRetryDelay)
    private val authServiceFactory: finance.shilling.shared.data.auth.AuthServiceFactory =
        { serverConfig, authSettings, authDeviceId, authScope, authHttpClient ->
            createAuthServiceWithRedirect(
                serverConfig, authSettings, authDeviceId, authScope, authHttpClient, config.authRedirectUrl
            )
        }

    // ── State ────────────────────────────────────────────────────────────────
    private var started = false
    private var onboardingComplete = !shouldShowFirstLaunchOnboarding(settings)
    private var settingsRevision = 0
    private var serverUrl = settings.getStringOrNull(SETTINGS_KEY_SERVER_URL) ?: defaultServerUrl()
    private var authRuntime: AuthRuntime? = null
    private var welcomeAuthService: AuthService? = null
    private var authScopeGeneration = 0
    private var startupRetryToken = 0
    private var manualRetryStartedAt: TimeMark? = null
    private var startupIdentity: StartupIdentity? = null
    private var householdId: String? = null
    private var sawAccountSession = false
    private var authScope: CoroutineScope = newAuthScope()
    private var featureGate: FeatureGate? = null
    private var featureGateKey: Pair<AuthService, Boolean>? = null
    private var syncRuntime: SyncRuntime? = null

    /** Read by the WebRTC client per connection, so ICE updates apply to new peers. */
    private var iceServers = listOf(WebRtc.IceServer("stun:stun.l.google.com:19302"))
    private var directIceServers = iceServers
    private var cloudRelayEnabled = settings.getBoolean(SETTINGS_KEY_CLOUD_RELAY_ENABLED, false)
    private val webRtcClient by lazy { webRtcPlatform.createClient { if (cloudRelayEnabled) iceServers else directIceServers } }

    // Settings-derived values, re-read when settingsRevision changes.
    private var derivedRevision = -1
    private var derivedOnboarding = false
    private var savedSelection: DeploymentSelection? = null
    private var heldLocalData = false
    private var notice: String? = null
    private var hadAccountSession = false

    private val statusFlow = MutableStateFlow(
        createPlaceholderStartupIdentity(settings, idGenerator, deviceId, expectHosted = initialExpectHosted()).bootstrapStatus
    )

    /** Live hosted-bootstrap status (Settings shows it). */
    val hostedBootstrapState = HostedBootstrapState(statusFlow)

    private val _phase = MutableStateFlow<SessionPhase>(SessionPhase.Starting)
    override val phase: StateFlow<SessionPhase> = _phase

    // ── Effects (key → restart) ──────────────────────────────────────────────
    private val authScopeEffect = Effect()
    private val authRuntimeReset = Effect()
    private val identityReset = Effect()
    private val welcomeEffect = Effect()
    private val bootstrapEffect = Effect()
    private val statusPublishEffect = Effect()
    private val authWatchEffect = Effect()
    private val householdResetEffect = Effect()
    private val householdSyncEffect = Effect()
    private val runtimeEffect = Effect()
    private val syncEffect = Effect()

    /** Starts the session. Idempotent; call once Koin is up (platform entry point). */
    fun start() {
        scope.launch {
            if (started) return@launch
            started = true
            // The effective status (and so sync readiness) follows live status changes.
            launch { statusFlow.collect { reconcile() } }
            reconcile()
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    /** Settings "Retry now". */
    fun retryBootstrap() {
        scope.launch {
            statusFlow.value = statusFlow.value.copy(isChecking = true)
            manualRetryStartedAt = TimeSource.Monotonic.markNow()
            startupRetryToken += 1
            reconcile()
        }
    }

    /** Welcome: continue as a guest. */
    override suspend fun getStarted(wipeHeldData: Boolean): Unit = onMain { completeHostedOnboarding(wipeHeldData) }

    /** Welcome: signed in (or signed up) with a managed account. */
    override suspend fun completeSignIn(wipeHeldData: Boolean): Unit = onMain { completeHostedOnboarding(wipeHeldData) }

    override suspend fun submitCredentials(
        mode: HostedCredentialsMode,
        email: String,
        password: String
    ): Result<HostedCredentialsSubmitResult> = onMain {
        runCatching {
            val service = ensureWelcomeAuthService()
            if (service is NoOpAuthService) error(MANAGED_AUTH_UNAVAILABLE)
            val submit = when (mode) {
                HostedCredentialsMode.SIGN_IN -> {
                    service.signIn(email, password).getOrThrow()
                    HostedCredentialsSubmitResult(mode = mode)
                }
                HostedCredentialsMode.CREATE_ACCOUNT -> {
                    val signUp = service.signUp(email, password).getOrThrow()
                    HostedCredentialsSubmitResult(mode = mode, signUpResult = signUp)
                }
            }
            val userId = service.authState.value.userId
            if (submit.signUpResult?.existingAccount != true &&
                hasHeldLocalData(settings) && !matchesPendingRestore(settings, userId)) {
                throw NonMatchingAccountException()
            }
            submit
        }
    }

    override suspend fun sendSignInLink(email: String): Result<Unit> = onMain {
        ensureWelcomeAuthService().sendSignInLink(email)
    }

    suspend fun handleAuthCallback(url: String): Result<Unit> = onMain {
        val service = (phase.value as? SessionPhase.Ready)?.authService
            ?: welcomeAuthService ?: ensureWelcomeAuthService()
        service.handleAuthCallback(url).onSuccess {
            if (phase.value is SessionPhase.Onboarding && !service.authState.value.isAnonymous) {
                completeHostedOnboarding(wipeHeldData = false)
            } else {
                startupRetryToken += 1
                reconcile()
            }
        }
    }

    override suspend fun continueSelfHosted(selectedUrl: String): Result<Unit> = onMain {
        validateSelfHostedServer(selectedUrl).onSuccess {
            if (heldLocalData) graphs.wipeAll()
            completeFirstLaunchOnboarding(settings, DeploymentSelection.SELF_HOSTED, selectedUrl)
            serverUrl = selectedUrl
            onboardingComplete = true
            settingsRevision += 1
            startupRetryToken += 1
            reconcile()
        }
    }

    override suspend fun cancelDestructiveAuth(): Unit = onMain {
        runCatching { welcomeAuthService?.signOut() }
        Unit
    }

    /**
     * Settings "Start over" / self-hosted "Sign out and reset": delete everything on this device
     * and return to a fresh Welcome. Sync stops first so no peer changes land during the wipe, and
     * the auth session ends so the next guest is a new user (with a new household) rather than
     * the auth SDK restoring the old one from its own storage.
     */
    suspend fun startOver() = onMain {
        clearMainEffects(includeBootstrap = true)
        val activeServices = listOfNotNull(startupIdentity?.authService, authRuntime?.authService, welcomeAuthService)
        val services = if (activeServices.isEmpty() && savedDeploymentSelection(settings) == DeploymentSelection.HOSTED) {
            listOfNotNull(runCatching { ensureWelcomeAuthService() }.getOrNull())
        } else {
            activeServices
        }
        services
            .distinct()
            .forEach { service -> runCatching { service.signOut() } }
        graphs.wipeAll()
        resetOnboarding()
    }

    /** After local data was wiped: back to a fresh Welcome. */
    private fun resetOnboarding() {
        authScopeGeneration += 1
        authRuntime = null
        welcomeAuthService = null
        startupIdentity = null
        onboardingComplete = false
        serverUrl = defaultServerUrl()
        manualRetryStartedAt = null
        settingsRevision += 1
        statusFlow.value = placeholderStatus(expectHosted = false)
        reconcile()
    }

    /** Sign out to Welcome, keeping local data for a matching re-login. */
    suspend fun restartHostedLogin() = onMain {
        clearMainEffects(includeBootstrap = true)
        val services = listOfNotNull(startupIdentity?.authService, authRuntime?.authService, welcomeAuthService)
            .distinct()
        softReturnToWelcome(settings, notice = "signed_out")
        services.forEach { service -> runCatching { service.signOut() } }
        authScopeGeneration += 1
        authRuntime = null
        welcomeAuthService = null
        startupIdentity = null
        onboardingComplete = false
        manualRetryStartedAt = null
        settingsRevision += 1
        statusFlow.value = placeholderStatus(expectHosted = false)
        reconcile()
    }

    fun changeServerUrl(newUrl: String) {
        settings.putString(SETTINGS_KEY_SERVER_URL, newUrl)
        scope.launch {
            serverUrl = newUrl
            reconcile()
        }
    }

    fun changeHouseholdId(id: String) {
        scope.launch {
            stopSyncEffects()
            graphs.activate(id)
            householdId = id
            reconcile()
        }
    }

    suspend fun applyHostedSpaces(data: finance.shilling.core.auth.HostedSpacesResponse) {
        withContext(config.dispatcher) {
            stopSyncEffects()
            val selected = data.activeSpaceId
            if (selected == null) {
                startupIdentity = startupIdentity?.let { it.copy(bootstrapStatus = it.bootstrapStatus.copy(syncReady = false)) }
                settings.remove(finance.shilling.shared.data.SETTINGS_KEY_HOSTED_HOUSEHOLD_ID)
            } else {
                val space = data.spaces.firstOrNull { it.id == selected } ?: error("Selected space membership is missing")
                graphs.activate(space.id, space.name, space.kind)
                settings.putString(finance.shilling.shared.data.SETTINGS_KEY_HOSTED_HOUSEHOLD_ID, selected)
                startupIdentity = startupIdentity?.copy(activeHouseholdId = selected, hostedHouseholdId = selected)
                householdId = selected
            }
            startupRetryToken += 1
            settingsRevision += 1
            reconcile()
        }
    }

    fun changeCloudRelay(enabled: Boolean) {
        settings.putBoolean(SETTINGS_KEY_CLOUD_RELAY_ENABLED, enabled)
        scope.launch {
            cloudRelayEnabled = enabled
            reconcile()
        }
    }

    // ── State machine ────────────────────────────────────────────────────────

    private fun reconcile() {
        if (!started) return
        refreshDerived()
        val deploymentSelection = resolveDeploymentSelection(onboardingComplete, savedSelection)
        val sessionRequirement = resolveHostedSessionRequirement(deploymentSelection, hadAccountSession)
        val expectHosted = onboardingComplete && deploymentSelection == DeploymentSelection.HOSTED

        authScopeEffect.update(serverUrl to authScopeGeneration) {
            authScope = newAuthScope()
            val current = authScope
            onDispose { current.cancel() }
        }
        authRuntimeReset.update(serverUrl) { authRuntime = null }
        identityReset.update(listOf(serverUrl, onboardingComplete, expectHosted, settingsRevision)) {
            startupIdentity = if (!onboardingComplete) null else placeholderIdentity(expectHosted)
        }

        welcomeEffect.update(listOf(onboardingComplete, heldLocalData, serverUrl, authScope)) {
            if (onboardingComplete || config.selfHostedOnly) return@update
            launch {
                runCatching { ensureWelcomeAuthService() }
                    .onFailure { finance.shilling.shared.data.auth.AuthErrors.logFailure("welcome_bootstrap", it) }
                reconcile()
            }
        }

        if (!onboardingComplete) {
            clearMainEffects(includeBootstrap = true)
            _phase.value = SessionPhase.Onboarding(
                selfHostedOnly = config.selfHostedOnly,
                initialSelfHostedUrl = settings.getStringOrNull(SETTINGS_KEY_SERVER_URL)
                    ?: config.defaultSelfHostedServerUrl,
                hasHeldLocalData = heldLocalData,
                welcomeNotice = notice,
                authService = welcomeAuthService
            )
            return
        }

        bootstrapEffect.update(
            listOf(sessionRequirement, serverUrl, authScope, startupRetryToken, settingsRevision)
        ) {
            val selection = deploymentSelection
            val requirement = sessionRequirement ?: HostedSessionRequirement.GUEST_ALLOWED
            val saved = savedSelection
            val hadAccount = hadAccountSession
            launch { runBootstrapLoop(selection, requirement, saved, hadAccount) }
        }
        statusPublishEffect.update(startupIdentity?.bootstrapStatus) {
            startupIdentity?.let { statusFlow.value = it.bootstrapStatus }
        }

        val startup = startupIdentity
        if (startup == null) {
            clearMainEffects(includeBootstrap = false)
            _phase.value = SessionPhase.Starting
            return
        }

        val authService = startup.authService
        val selfHosted = startup.serverConfig.authMode == AuthMode.NONE
        authWatchEffect.update(listOf(authService, selfHosted)) {
            if (selfHosted) return@update
            var observedUserId = authService.authState.value.userId
            launch {
                authService.authState.collect { authState ->
                    if (authState.isAuthenticated && authState.userId != observedUserId) {
                        observedUserId = authState.userId
                        startupRetryToken += 1
                        reconcile()
                    }
                    if (authState.isAuthenticated && !authState.isAnonymous) {
                        sawAccountSession = true
                    } else if (sawAccountSession) {
                        softReturnToWelcome(settings, notice = "session_expired")
                        sawAccountSession = false
                        onboardingComplete = false
                        startupIdentity = null
                        authRuntime = null
                        welcomeAuthService = null
                        settingsRevision += 1
                        reconcile()
                    }
                }
            }
        }

        householdResetEffect.update(serverUrl) { householdId = startup.activeHouseholdId }
        householdSyncEffect.update(startup.activeHouseholdId) { householdId = startup.activeHouseholdId }
        val activeHousehold = householdId ?: startup.activeHouseholdId

        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
        val syncConfig = SyncConfig(wsUrl, activeHousehold, deviceId)
        val graph = graphs.current
        runtimeEffect.update(listOf(syncConfig, authService, graph)) {
            val runtime = createSyncRuntime(syncConfig, authService)
            syncRuntime = runtime
            graph.sync.state = SyncState(peerSyncManager = runtime.peerSyncManager, deviceId = syncConfig.deviceId)
            // Stores outlive this runtime; don't leave them broadcasting through a stopped manager.
            onDispose {
                syncRuntime = null
                graph.sync.state = SyncState(peerSyncManager = null, deviceId = syncConfig.deviceId)
            }
        }
        val runtime = syncRuntime!!

        val effectiveStatus = resolveEffectiveHostedBootstrapStatus(
            authoritativeStatus = startup.bootstrapStatus,
            liveStatus = statusFlow.value
        )
        syncEffect.update(listOf(effectiveStatus.syncReady, serverUrl, activeHousehold, deviceId, runtime, cloudRelayEnabled)) {
            if (!effectiveStatus.syncReady) {
                log.w { "Hosted bootstrap is not ready; signaling remains disabled until auth and household metadata are available" }
                return@update
            }
            val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + syncExceptionHandler)
            log.i { "Starting sync services (device=$deviceId, server=$serverUrl, ws=$wsUrl, household=$activeHousehold)" }
            val serverApi = ServerApi(httpClient, serverUrl, authService)
            syncScope.launch {
                try {
                    iceServers = directIceServers
                    val response = serverApi.fetchIceServers(allowRelay = cloudRelayEnabled)
                    val fetched = response.iceServers.map { cfg ->
                        WebRtc.IceServer(cfg.urls, cfg.username.orEmpty(), cfg.credential.orEmpty())
                    }
                    val direct = response.iceServers.filter { cfg ->
                        cfg.urls.none { it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true) }
                    }.map { cfg -> WebRtc.IceServer(cfg.urls, cfg.username.orEmpty(), cfg.credential.orEmpty()) }
                    if (direct.isNotEmpty()) directIceServers = direct
                    if (fetched.isNotEmpty()) {
                        iceServers = fetched
                        log.i { "ICE servers updated: ${fetched.size} entries" }
                    }
                } catch (e: Exception) {
                    log.w { "ICE server fetch failed: ${e.message}" }
                }
                runtime.signalingClient.connect(syncScope)
                runtime.peerSyncManager.start(syncScope)
                runtime.incomingChangeRouter.start(syncScope)
            }
            syncScope.launch {
                webRtcPlatform.resumeSignals.collect {
                    log.i { "App resumed — retrying signaling without waiting out the backoff" }
                    runtime.signalingClient.reconnectNow()
                }
            }
            onDispose {
                runtime.peerSyncManager.stop()
                runtime.signalingClient.disconnect()
                syncScope.cancel()
            }
        }

        if (featureGateKey != (authService to selfHosted)) {
            featureGateKey = authService to selfHosted
            featureGate = FeatureGate(authService, selfHosted)
        }
        _phase.value = SessionPhase.Ready(authService, featureGate!!, selfHosted, graphs.current.id)
    }

    private suspend fun runBootstrapLoop(
        deploymentSelection: DeploymentSelection?,
        sessionRequirement: HostedSessionRequirement,
        saved: DeploymentSelection?,
        hadAccount: Boolean
    ) {
        hostedBootstrapLoop.run(
            initialAuthRuntime = authRuntime,
            resolve = { currentAuthRuntime ->
                statusFlow.value = statusFlow.value.copy(isChecking = true)
                resolveStartupIdentity(
                    httpClient = httpClient,
                    serverUrl = serverUrl,
                    settings = settings,
                    idGenerator = idGenerator,
                    deviceId = deviceId,
                    scope = authScope,
                    deploymentSelection = deploymentSelection,
                    sessionRequirement = sessionRequirement,
                    currentAuthRuntime = currentAuthRuntime,
                    authServiceFactory = authServiceFactory
                )
            },
            onResolution = { resolution ->
                val routing = resolveOnboardingRouting(
                    onboardingComplete = true,
                    savedDeploymentSelection = saved,
                    hadPersistedAccountSession = hadAccount,
                    selfHosted = resolution.identity.serverConfig.authMode == AuthMode.NONE,
                    authState = resolution.authRuntime.authService.authState.value
                )
                if (routing.shouldSoftReturnToWelcome) {
                    softReturnToWelcome(settings, notice = "session_expired")
                    authRuntime = null
                    welcomeAuthService = null
                    startupIdentity = null
                    onboardingComplete = false
                    settingsRevision += 1
                    reconcile()
                } else {
                    publishResolution(resolution)
                }
            }
        )
    }

    private suspend fun publishResolution(resolution: StartupStateResolution) {
        manualRetryStartedAt?.let { startedAt ->
            val remaining = MANUAL_RETRY_MIN_LOADING_MS - startedAt.elapsedNow().inWholeMilliseconds
            if (remaining > 0L) delay(remaining)
            manualRetryStartedAt = null
        }
        if (resolution.identity.bootstrapStatus.syncReady && graphs.current.id != resolution.identity.activeHouseholdId) {
            stopSyncEffects()
            graphs.activate(resolution.identity.activeHouseholdId)
        }
        authRuntime = resolution.authRuntime
        startupIdentity = resolution.identity
        statusFlow.value = resolution.identity.bootstrapStatus
        reconcile()
    }

    private suspend fun ensureWelcomeAuthService(): AuthService {
        welcomeAuthService?.let { return it }
        val resolution = resolveStartupIdentity(
            httpClient = httpClient,
            serverUrl = serverUrl,
            settings = settings,
            idGenerator = idGenerator,
            deviceId = deviceId,
            scope = authScope,
            deploymentSelection = DeploymentSelection.HOSTED,
            sessionRequirement = HostedSessionRequirement.ACCOUNT_REQUIRED,
            currentAuthRuntime = authRuntime,
            authServiceFactory = authServiceFactory
        )
        authRuntime = resolution.authRuntime
        welcomeAuthService = resolution.authRuntime.authService
        statusFlow.value = resolution.identity.bootstrapStatus
        return resolution.authRuntime.authService
    }

    private suspend fun completeHostedOnboarding(wipeHeldData: Boolean) {
        if (wipeHeldData) graphs.wipeAll() else clearWelcomeHoldState(settings)
        completeFirstLaunchOnboarding(settings, DeploymentSelection.HOSTED, serverUrl)
        onboardingComplete = true
        settingsRevision += 1
        startupRetryToken += 1
        reconcile()
    }

    private suspend fun validateSelfHostedServer(url: String): Result<Unit> {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("Enter a full URL including http:// or https://."))
        }
        return runCatching {
            val serverConfig = ServerApi(httpClient, trimmedUrl).fetchConfig()
            check(serverConfig.authMode == AuthMode.NONE) { SELF_HOSTED_MODE_MISMATCH_MESSAGE }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error ->
                val message = if (error.message == SELF_HOSTED_MODE_MISMATCH_MESSAGE) {
                    SELF_HOSTED_MODE_MISMATCH_MESSAGE
                } else {
                    "Couldn't reach the self-hosted server. Check the URL and try again."
                }
                Result.failure(IllegalStateException(message, error))
            }
        )
    }

    private fun createSyncRuntime(syncConfig: SyncConfig, authService: AuthService): SyncRuntime {
        val signalingClient = SignalingClient(
            httpClient, syncConfig.serverUrl, syncConfig.deviceId, syncConfig.householdId, authService
        )
        val webRtcManager = WebRtcConnectionManager(
            webRtcClient, signalingClient, syncConfig.deviceId,
            peerConnectionStatus = peerConnectionStatus,
            allowRelay = { cloudRelayEnabled },
            delayFn = webRtcPlatform.delayFn
        )
        val incomingChangeRouter = IncomingChangeRouter(
            graphs.current.facade,
            notifier,
            webRtcManager,
            FileTransferManager(graphs.current.files),
            graphs.current.files,
            delayFn = webRtcPlatform.delayFn,
            deviceId = syncConfig.deviceId,
            idGenerator = idGenerator
        )
        return SyncRuntime(signalingClient, webRtcManager, incomingChangeRouter)
    }

    private fun stopSyncEffects() {
        syncEffect.clear()
        runtimeEffect.clear()
    }

    private fun clearMainEffects(includeBootstrap: Boolean) {
        if (includeBootstrap) {
            bootstrapEffect.clear()
            statusPublishEffect.clear()
        }
        syncEffect.clear()
        runtimeEffect.clear()
        householdSyncEffect.clear()
        householdResetEffect.clear()
        authWatchEffect.clear()
        householdId = null
        sawAccountSession = false
    }

    private fun refreshDerived() {
        if (derivedRevision != settingsRevision) {
            derivedRevision = settingsRevision
            savedSelection = savedDeploymentSelection(settings)
            heldLocalData = hasHeldLocalData(settings)
            notice = welcomeNotice(settings)
            hadAccountSession = hadPersistedAccountSession(settings)
            cloudRelayEnabled = settings.getBoolean(SETTINGS_KEY_CLOUD_RELAY_ENABLED, false)
            derivedOnboarding = onboardingComplete
        } else if (derivedOnboarding != onboardingComplete) {
            derivedOnboarding = onboardingComplete
            hadAccountSession = hadPersistedAccountSession(settings)
        }
    }

    private fun initialExpectHosted(): Boolean =
        onboardingComplete && resolveDeploymentSelection(onboardingComplete, savedDeploymentSelection(settings)) ==
            DeploymentSelection.HOSTED

    private fun placeholderIdentity(expectHosted: Boolean) =
        createPlaceholderStartupIdentity(settings, idGenerator, deviceId, expectHosted)

    private fun placeholderStatus(expectHosted: Boolean) = placeholderIdentity(expectHosted).bootstrapStatus

    private fun defaultServerUrl(): String =
        if (config.selfHostedOnly) config.defaultSelfHostedServerUrl else DEFAULT_SERVER_URL

    private fun newAuthScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default + syncExceptionHandler)

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(config.dispatcher) { block() }

    /**
     * A keyed effect: [update] reruns its block only when the key changes, first cancelling the
     * previous run's coroutines and calling its dispose action.
     */
    private inner class Effect {
        private var key: Any? = Unset
        private var jobs = mutableListOf<Job>()
        private var dispose: (() -> Unit)? = null

        fun update(newKey: Any?, block: EffectScope.() -> Unit) {
            if (key == newKey) return
            clear()
            key = newKey
            EffectScope().block()
        }

        fun clear() {
            jobs.forEach { it.cancel() }
            jobs = mutableListOf()
            dispose?.invoke()
            dispose = null
            key = Unset
        }

        inner class EffectScope {
            fun launch(block: suspend CoroutineScope.() -> Unit) {
                jobs += scope.launch(block = block)
            }

            fun onDispose(action: () -> Unit) {
                dispose = action
            }
        }
    }

    private object Unset
}
