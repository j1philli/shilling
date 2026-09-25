package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import finance.shilling.core.auth.AuthMode
import finance.shilling.shared.data.DEFAULT_SERVER_URL
import finance.shilling.shared.data.DEFAULT_SELF_HOSTED_SERVER_URL
import finance.shilling.shared.data.DeploymentSelection
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.completeFirstLaunchOnboarding
import finance.shilling.shared.data.createAppModule
import finance.shilling.shared.data.clearWelcomeHoldState
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
import finance.shilling.shared.data.wipeLocalAppState
import finance.shilling.shared.data.auth.AuthRuntime
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.data.auth.FeatureGate
import finance.shilling.shared.data.auth.HostedBootstrapLoop
import finance.shilling.shared.data.auth.HostedBootstrapRetryCallback
import finance.shilling.shared.data.auth.HostedBootstrapState
import finance.shilling.shared.data.auth.HostedSessionRequirement
import finance.shilling.shared.data.auth.NoOpAuthService
import finance.shilling.shared.data.auth.createPlaceholderStartupIdentity
import finance.shilling.shared.data.auth.hostedBootstrapRetryDelay
import finance.shilling.shared.data.auth.resolveEffectiveHostedBootstrapStatus
import finance.shilling.shared.data.auth.resolveStartupIdentity
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.store.StoreSyncDeps
import finance.shilling.shared.data.store.SyncState
import finance.shilling.shared.data.store.createAccountStore
import finance.shilling.shared.data.store.createCategoryStore
import finance.shilling.shared.data.store.createPostingStore
import finance.shilling.shared.data.store.createReceiptStore
import finance.shilling.shared.data.store.createScheduleExceptionStore
import finance.shilling.shared.data.store.createScheduleStore
import finance.shilling.shared.data.sync.FileTransferManager
import finance.shilling.shared.data.sync.IncomingChangeRouter
import finance.shilling.shared.data.sync.PeerSyncManager
import finance.shilling.shared.data.sync.ServerApi
import finance.shilling.shared.data.sync.SignalingClient
import finance.shilling.shared.data.sync.SyncConfig
import finance.shilling.shared.db.ShillingDatabase
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.client.HttpClient
import io.ktor.client.webrtc.WebRtc
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.koin.compose.KoinApplication

private const val MANUAL_RETRY_MIN_LOADING_MS = 2_000L
private const val SELF_HOSTED_MODE_MISMATCH_MESSAGE =
    "This server requires managed auth. Use the hosted flow instead."

data class AppBootstrapServices(
    val db: ShillingDatabase,
    val settings: Settings,
    val idGenerator: IdGenerator,
    val receiptFileStore: ReceiptFileStore,
    val httpClient: HttpClient,
    val deviceIdentity: DeviceIdentity,
    val notifier: ChangeNotifier
)

data class PlatformSyncRuntime(
    val signalingClient: SignalingClient,
    val peerSyncManager: PeerSyncManager,
    val incomingChangeRouter: IncomingChangeRouter,
    val fileTransferManager: FileTransferManager
)

typealias PlatformSyncRuntimeFactory = @Composable (
    syncConfig: SyncConfig,
    authService: AuthService,
    iceServers: State<List<WebRtc.IceServer>>,
    services: AppBootstrapServices
) -> PlatformSyncRuntime

data class AppBootstrapScaffoldConfig(
    val onboardingTopPadding: Dp = 0.dp,
    val navRailTopPadding: Dp = 0.dp,
    val selfHostedOnly: Boolean = false,
    val defaultSelfHostedServerUrl: String = DEFAULT_SELF_HOSTED_SERVER_URL,
    val cameraButton: ReceiptPickerButton? = null,
    val photoButton: ReceiptPickerButton? = null,
    val externalNavRequest: StateFlow<ShelfDestination?> = MutableStateFlow(null),
    val pendingReceiptFile: PlatformFile? = null,
    val onPendingReceiptConsumed: () -> Unit = {},
    val preScaffoldContent: @Composable () -> Unit = {},
    val startupPendingContent: @Composable () -> Unit = { DefaultLoadingSurface() }
)

@Composable
fun ShillingAppBootstrap(
    services: AppBootstrapServices,
    syncRuntimeFactory: PlatformSyncRuntimeFactory,
    scaffoldConfig: AppBootstrapScaffoldConfig = AppBootstrapScaffoldConfig(),
    logTag: String = "AppBootstrap"
) {
    val log = remember(logTag) { Logger.withTag(logTag) }
    val syncExceptionHandler = remember(logTag) {
        CoroutineExceptionHandler { _, throwable ->
            log.e {
                "Sync uncaught exception: ${throwable::class.simpleName ?: "?"}: ${throwable.message ?: "?"}"
            }
        }
    }
    var onboardingComplete by remember {
        mutableStateOf(!shouldShowFirstLaunchOnboarding(services.settings))
    }
    var settingsRevision by remember { mutableStateOf(0) }
    var serverUrl by remember {
        mutableStateOf(
            services.settings.getStringOrNull(SETTINGS_KEY_SERVER_URL)
                ?: if (scaffoldConfig.selfHostedOnly) scaffoldConfig.defaultSelfHostedServerUrl else DEFAULT_SERVER_URL
        )
    }
    val savedSelection = remember(settingsRevision) { savedDeploymentSelection(services.settings) }
    val heldLocalData = remember(settingsRevision) { hasHeldLocalData(services.settings) }
    val notice = remember(settingsRevision) { welcomeNotice(services.settings) }
    val hadAccountSession = remember(settingsRevision, onboardingComplete) {
        hadPersistedAccountSession(services.settings)
    }
    val deploymentSelection = resolveDeploymentSelection(
        onboardingComplete = onboardingComplete,
        savedDeploymentSelection = savedSelection
    )
    val hostedSessionRequirement = resolveHostedSessionRequirement(
        deploymentSelection = deploymentSelection,
        hadPersistedAccountSession = hadAccountSession
    )
    val expectHostedBootstrap = onboardingComplete && deploymentSelection == DeploymentSelection.HOSTED
    var startupIdentity by remember(
        serverUrl,
        services.deviceIdentity.deviceId,
        onboardingComplete,
        expectHostedBootstrap,
        settingsRevision
    ) {
        mutableStateOf(
            if (!onboardingComplete) {
                null
            } else {
                createPlaceholderStartupIdentity(
                    settings = services.settings,
                    idGenerator = services.idGenerator,
                    deviceId = services.deviceIdentity.deviceId,
                    expectHosted = expectHostedBootstrap
                )
            }
        )
    }
    var authRuntime by remember(serverUrl, services.deviceIdentity.deviceId) {
        mutableStateOf<AuthRuntime?>(null)
    }
    var welcomeAuthService by remember { mutableStateOf<AuthService?>(null) }
    var authScopeGeneration by remember { mutableStateOf(0) }
    var startupRetryToken by remember { mutableStateOf(0) }
    var manualRetryStartedAt by remember { mutableStateOf<TimeMark?>(null) }
    var iceServerList by remember {
        mutableStateOf(listOf(WebRtc.IceServer("stun:stun.l.google.com:19302")))
    }
    val hostedBootstrapStatusFlow = remember {
        MutableStateFlow(
            createPlaceholderStartupIdentity(
                settings = services.settings,
                idGenerator = services.idGenerator,
                deviceId = services.deviceIdentity.deviceId,
                expectHosted = expectHostedBootstrap
            ).bootstrapStatus
        )
    }
    val hostedBootstrapState = remember { HostedBootstrapState(hostedBootstrapStatusFlow) }
    val hostedBootstrapRetryCallback = remember {
        HostedBootstrapRetryCallback {
            hostedBootstrapStatusFlow.value = hostedBootstrapStatusFlow.value.copy(isChecking = true)
            manualRetryStartedAt = TimeSource.Monotonic.markNow()
            startupRetryToken += 1
        }
    }
    val uiScope = rememberCoroutineScope()
    val onResetOnboardingUi: suspend () -> Unit = remember {
        {
            authScopeGeneration += 1
            authRuntime = null
            welcomeAuthService = null
            startupIdentity = null
            onboardingComplete = false
            serverUrl = if (scaffoldConfig.selfHostedOnly) scaffoldConfig.defaultSelfHostedServerUrl else DEFAULT_SERVER_URL
            manualRetryStartedAt = null
            settingsRevision += 1
            hostedBootstrapStatusFlow.value = createPlaceholderStartupIdentity(
                settings = services.settings,
                idGenerator = services.idGenerator,
                deviceId = services.deviceIdentity.deviceId,
                expectHosted = false
            ).bootstrapStatus
        }
    }
    val onSoftReturnToWelcomeUi: suspend () -> Unit = remember {
        {
            softReturnToWelcome(services.settings, notice = "signed_out")
            authScopeGeneration += 1
            authRuntime = null
            welcomeAuthService = null
            startupIdentity = null
            onboardingComplete = false
            manualRetryStartedAt = null
            settingsRevision += 1
            hostedBootstrapStatusFlow.value = createPlaceholderStartupIdentity(
                settings = services.settings,
                idGenerator = services.idGenerator,
                deviceId = services.deviceIdentity.deviceId,
                expectHosted = false
            ).bootstrapStatus
        }
    }
    val hostedBootstrapLoop = remember { HostedBootstrapLoop(::hostedBootstrapRetryDelay) }
    val authScope = remember(serverUrl, authScopeGeneration) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + syncExceptionHandler)
    }

    DisposableEffect(authScope) {
        onDispose { authScope.cancel() }
    }

    suspend fun wipeHeldLocalData() {
        val syncDeps = StoreSyncDeps(
            services.db,
            null,
            services.deviceIdentity.deviceId,
            services.idGenerator
        )
        val accountStore = createAccountStore(services.db, syncDeps)
        val categoryStore = createCategoryStore(services.db, syncDeps)
        val scheduleStore = createScheduleStore(services.db, syncDeps)
        val scheduleExceptionStore = createScheduleExceptionStore(services.db, syncDeps)
        val postingStore = createPostingStore(services.db, syncDeps)
        val receiptStore = createReceiptStore(services.db, syncDeps)
        wipeLocalAppState(
            db = services.db,
            settings = services.settings,
            fileStore = services.receiptFileStore,
            accountRepository = AccountRepository(services.notifier, accountStore, syncDeps),
            categoryRepository = CategoryRepository(services.notifier, categoryStore, syncDeps),
            scheduleRepository = ScheduleRepository(
                services.notifier,
                scheduleStore,
                scheduleExceptionStore,
                syncDeps
            ),
            postingRepository = PostingRepository(
                services.idGenerator,
                services.notifier,
                postingStore,
                accountStore,
                categoryStore,
                scheduleStore,
                syncDeps
            ),
            receiptRepository = ReceiptRepository(
                services.notifier,
                receiptStore,
                postingStore,
                scheduleStore,
                syncDeps
            )
        )
    }

    suspend fun ensureWelcomeAuthService(): AuthService {
        welcomeAuthService?.let { return it }
        val resolution = resolveStartupIdentity(
            httpClient = services.httpClient,
            serverUrl = serverUrl,
            settings = services.settings,
            idGenerator = services.idGenerator,
            deviceId = services.deviceIdentity.deviceId,
            scope = authScope,
            deploymentSelection = DeploymentSelection.HOSTED,
            sessionRequirement = HostedSessionRequirement.ACCOUNT_REQUIRED,
            currentAuthRuntime = authRuntime
        )
        authRuntime = resolution.authRuntime
        welcomeAuthService = resolution.authRuntime.authService
        hostedBootstrapStatusFlow.value = resolution.identity.bootstrapStatus
        return resolution.authRuntime.authService
    }

    LaunchedEffect(onboardingComplete, heldLocalData, serverUrl, authScope) {
        if (onboardingComplete || scaffoldConfig.selfHostedOnly) return@LaunchedEffect
        runCatching { ensureWelcomeAuthService() }
            .onFailure { error ->
                log.w { "Welcome auth bootstrap not ready yet: ${error.message}" }
            }
    }

    if (!onboardingComplete) {
        val welcomeAuth = welcomeAuthService
        ShillingTheme {
            FirstLaunchOnboardingView(
                initialSelfHostedUrl = services.settings.getStringOrNull(SETTINGS_KEY_SERVER_URL)
                    ?: scaffoldConfig.defaultSelfHostedServerUrl,
                selfHostedOnly = scaffoldConfig.selfHostedOnly,
                hasHeldLocalData = heldLocalData,
                welcomeNotice = notice,
                authService = welcomeAuth,
                authReady = welcomeAuth != null && welcomeAuth !is NoOpAuthService,
                authDisabledReason = if (welcomeAuth == null || welcomeAuth is NoOpAuthService) {
                    "Managed sign-in is unavailable until the server config is reachable."
                } else {
                    null
                },
                onGetStarted = { wipeHeldData ->
                    if (wipeHeldData) {
                        wipeHeldLocalData()
                    } else {
                        clearWelcomeHoldState(services.settings)
                    }
                    completeFirstLaunchOnboarding(
                        settings = services.settings,
                        selection = DeploymentSelection.HOSTED,
                        serverUrl = serverUrl
                    )
                    onboardingComplete = true
                    settingsRevision += 1
                    startupRetryToken += 1
                },
                onAuthenticated = { wipeHeldData ->
                    if (wipeHeldData) {
                        wipeHeldLocalData()
                    } else {
                        clearWelcomeHoldState(services.settings)
                    }
                    completeFirstLaunchOnboarding(
                        settings = services.settings,
                        selection = DeploymentSelection.HOSTED,
                        serverUrl = serverUrl
                    )
                    onboardingComplete = true
                    settingsRevision += 1
                    startupRetryToken += 1
                },
                onSubmitCredentials = { mode, email, password ->
                    runCatching {
                        val service = ensureWelcomeAuthService()
                        if (service is NoOpAuthService) {
                            error("Managed sign-in is unavailable until the server config is reachable.")
                        }
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
                        if (hasHeldLocalData(services.settings) &&
                            !matchesPendingRestore(services.settings, userId)
                        ) {
                            throw NonMatchingAccountException()
                        }
                        submit
                    }
                },
                onContinueSelfHosted = { selectedUrl ->
                    validateSelfHostedServer(services.httpClient, selectedUrl).onSuccess {
                        if (heldLocalData) {
                            wipeHeldLocalData()
                        }
                        completeFirstLaunchOnboarding(
                            settings = services.settings,
                            selection = DeploymentSelection.SELF_HOSTED,
                            serverUrl = selectedUrl
                        )
                        serverUrl = selectedUrl
                        onboardingComplete = true
                        settingsRevision += 1
                        startupRetryToken += 1
                    }
                },
                onCancelDestructiveAuth = {
                    runCatching { welcomeAuthService?.signOut() }
                },
                topPadding = scaffoldConfig.onboardingTopPadding
            )
        }
        return
    }

    suspend fun publishResolution(resolution: finance.shilling.shared.data.auth.StartupStateResolution) {
        manualRetryStartedAt?.let { startedAt ->
            val remaining = MANUAL_RETRY_MIN_LOADING_MS - startedAt.elapsedNow().inWholeMilliseconds
            if (remaining > 0L) {
                delay(remaining)
            }
            manualRetryStartedAt = null
        }
        authRuntime = resolution.authRuntime
        startupIdentity = resolution.identity
        hostedBootstrapStatusFlow.value = resolution.identity.bootstrapStatus
    }

    LaunchedEffect(startupIdentity?.bootstrapStatus) {
        startupIdentity?.let { hostedBootstrapStatusFlow.value = it.bootstrapStatus }
    }

    LaunchedEffect(
        onboardingComplete,
        hostedSessionRequirement,
        serverUrl,
        services.deviceIdentity.deviceId,
        authScope,
        startupRetryToken,
        settingsRevision
    ) {
        if (!onboardingComplete) return@LaunchedEffect

        hostedBootstrapLoop.run(
            initialAuthRuntime = authRuntime,
            resolve = { currentAuthRuntime ->
                hostedBootstrapStatusFlow.value = hostedBootstrapStatusFlow.value.copy(isChecking = true)
                resolveStartupIdentity(
                    httpClient = services.httpClient,
                    serverUrl = serverUrl,
                    settings = services.settings,
                    idGenerator = services.idGenerator,
                    deviceId = services.deviceIdentity.deviceId,
                    scope = authScope,
                    deploymentSelection = deploymentSelection,
                    sessionRequirement = hostedSessionRequirement ?: HostedSessionRequirement.GUEST_ALLOWED,
                    currentAuthRuntime = currentAuthRuntime
                )
            },
            onResolution = { resolution ->
                val authState = resolution.authRuntime.authService.authState.value
                val routing = resolveOnboardingRouting(
                    onboardingComplete = true,
                    savedDeploymentSelection = savedSelection,
                    hadPersistedAccountSession = hadAccountSession,
                    selfHosted = resolution.identity.serverConfig.authMode == AuthMode.NONE,
                    authState = authState
                )
                if (routing.shouldSoftReturnToWelcome) {
                    softReturnToWelcome(services.settings, notice = "session_expired")
                    authRuntime = null
                    welcomeAuthService = null
                    startupIdentity = null
                    onboardingComplete = false
                    settingsRevision += 1
                    return@run
                }
                publishResolution(resolution)
            }
        )
    }

    val startup = startupIdentity
    if (startup == null) {
        ShillingTheme {
            scaffoldConfig.startupPendingContent()
        }
        return
    }

    val authService = startup.authService
    val authState by authService.authState.collectAsState()
    val liveBootstrapStatus by hostedBootstrapStatusFlow.collectAsState()
    val effectiveBootstrapStatus = remember(startup.bootstrapStatus, liveBootstrapStatus) {
        resolveEffectiveHostedBootstrapStatus(
            authoritativeStatus = startup.bootstrapStatus,
            liveStatus = liveBootstrapStatus
        )
    }
    val selfHosted = startup.serverConfig.authMode == AuthMode.NONE
    var sawAccountSession by remember { mutableStateOf(false) }

    LaunchedEffect(authState.isAuthenticated, authState.isAnonymous, authState.userId, selfHosted, onboardingComplete) {
        if (selfHosted || !onboardingComplete) return@LaunchedEffect
        if (authState.isAuthenticated && !authState.isAnonymous) {
            sawAccountSession = true
            return@LaunchedEffect
        }
        if (sawAccountSession && (!authState.isAuthenticated || authState.isAnonymous)) {
            softReturnToWelcome(services.settings, notice = "session_expired")
            sawAccountSession = false
            onboardingComplete = false
            startupIdentity = null
            authRuntime = null
            welcomeAuthService = null
            settingsRevision += 1
        }
    }

    var householdId by remember(serverUrl, services.deviceIdentity.deviceId) {
        mutableStateOf(startup.activeHouseholdId)
    }
    LaunchedEffect(startup.activeHouseholdId) {
        householdId = startup.activeHouseholdId
    }

    val wsUrl = remember(serverUrl) {
        serverUrl.replace("http://", "ws://").replace("https://", "wss://")
    }
    val syncConfig = remember(wsUrl, householdId, services.deviceIdentity.deviceId) {
        SyncConfig(wsUrl, householdId, services.deviceIdentity.deviceId)
    }
    val serverApi = remember(services.httpClient, serverUrl, authService) {
        ServerApi(services.httpClient, serverUrl, authService)
    }

    val iceServers = rememberUpdatedState(iceServerList)
    val syncDeps = remember(syncConfig.deviceId) {
        StoreSyncDeps(
            services.db,
            null,
            syncConfig.deviceId,
            services.idGenerator
        )
    }

    val koinModule = remember(
        syncDeps,
        hostedBootstrapState,
        hostedBootstrapRetryCallback,
        onResetOnboardingUi,
        onSoftReturnToWelcomeUi
    ) {
        createAppModule(
            db = services.db,
            idGenerator = services.idGenerator,
            fileStore = services.receiptFileStore,
            settings = services.settings,
            deviceIdentity = services.deviceIdentity,
            notifier = services.notifier,
            syncDeps = syncDeps,
            hostedBootstrapState = hostedBootstrapState,
            onRetryHostedBootstrap = hostedBootstrapRetryCallback,
            onResetOnboardingUi = onResetOnboardingUi,
            onRestartHostedLoginUi = onSoftReturnToWelcomeUi,
            onServerUrlChanged = { newUrl ->
                services.settings.putString(SETTINGS_KEY_SERVER_URL, newUrl)
                serverUrl = newUrl
            },
            onHouseholdIdChanged = { newHouseholdId ->
                householdId = newHouseholdId
            }
        )
    }
    val featureGate = remember(authService, selfHosted) { FeatureGate(authService, selfHosted) }

    KoinApplication(application = { modules(koinModule) }) {
        // Sync runtime must run inside Koin so platforms can inject SyncStoreFacade / stores.
        val runtime = syncRuntimeFactory(syncConfig, authService, iceServers, services)
        val serverApiRef = rememberUpdatedState(serverApi)

        LaunchedEffect(runtime.peerSyncManager, syncConfig.deviceId) {
            syncDeps.state = SyncState(
                peerSyncManager = runtime.peerSyncManager,
                deviceId = syncConfig.deviceId
            )
        }

        DisposableEffect(
            effectiveBootstrapStatus.syncReady,
            serverUrl,
            householdId,
            syncConfig.deviceId,
            runtime.signalingClient,
            runtime.peerSyncManager,
            runtime.incomingChangeRouter,
        ) {
            if (!effectiveBootstrapStatus.syncReady) {
                log.w {
                    "Hosted bootstrap is not ready; signaling remains disabled until auth and household metadata are available"
                }
                onDispose { }
            } else {
                val activeRuntime = runtime
                val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + syncExceptionHandler)
                log.i {
                    "Starting sync services (device=${syncConfig.deviceId}, server=$serverUrl, ws=$wsUrl, household=$householdId)"
                }
                activeRuntime.signalingClient.connect(syncScope)
                activeRuntime.peerSyncManager.start(syncScope)
                activeRuntime.incomingChangeRouter.start(syncScope)
                syncScope.launch {
                    try {
                        val resp = serverApiRef.value.fetchIceServers()
                        val fetched = resp.iceServers.flatMap { cfg -> cfg.urls.map { WebRtc.IceServer(it) } }
                        if (fetched.isNotEmpty()) {
                            iceServerList = fetched
                            log.i { "ICE servers updated: ${fetched.size} entries" }
                        }
                    } catch (e: Exception) {
                        log.w { "ICE server fetch failed: ${e.message}" }
                    }
                }

                onDispose {
                    activeRuntime.peerSyncManager.stop()
                    activeRuntime.signalingClient.disconnect()
                    syncScope.cancel()
                }
            }
        }

        ShillingTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                scaffoldConfig.preScaffoldContent()
                ShillingScaffold(
                    authService = authService,
                    featureGate = featureGate,
                    selfHosted = selfHosted,
                    onRetryHostedBootstrap = hostedBootstrapRetryCallback.onRetry,
                    navRailTopPadding = scaffoldConfig.navRailTopPadding,
                    cameraButton = scaffoldConfig.cameraButton,
                    photoButton = scaffoldConfig.photoButton,
                    externalNavRequest = scaffoldConfig.externalNavRequest,
                    pendingReceiptFile = scaffoldConfig.pendingReceiptFile,
                    onPendingReceiptConsumed = scaffoldConfig.onPendingReceiptConsumed
                )
            }
        }
    }
}

@Composable
private fun DefaultLoadingSurface() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading...", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

private suspend fun validateSelfHostedServer(
    httpClient: HttpClient,
    serverUrl: String
): Result<Unit> {
    val trimmedUrl = serverUrl.trim()
    if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
        return Result.failure(
            IllegalArgumentException("Enter a full URL including http:// or https://.")
        )
    }

    return runCatching {
        val config = ServerApi(httpClient, trimmedUrl).fetchConfig()
        check(config.authMode == AuthMode.NONE) { SELF_HOSTED_MODE_MISMATCH_MESSAGE }
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { error ->
            val message = when (error.message) {
                SELF_HOSTED_MODE_MISMATCH_MESSAGE -> SELF_HOSTED_MODE_MISMATCH_MESSAGE
                else -> "Couldn't reach the self-hosted server. Check the URL and try again."
            }
            Result.failure(IllegalStateException(message, error))
        }
    )
}
