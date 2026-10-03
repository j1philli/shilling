package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.Settings
import finance.shilling.core.auth.HostedEntitlementsResponse
import finance.shilling.core.auth.HostedBillingConfig
import finance.shilling.core.auth.HostedPlan
import finance.shilling.core.auth.RegisteredDevice
import finance.shilling.shared.data.CloudRelayCallback
import finance.shilling.shared.data.DEFAULT_SERVER_URL
import finance.shilling.shared.data.SETTINGS_KEY_CLOUD_RELAY_ENABLED
import finance.shilling.shared.data.SETTINGS_KEY_SERVER_URL
import finance.shilling.shared.data.auth.AuthService
import finance.shilling.shared.session.SessionPhase
import finance.shilling.shared.data.sync.PeerConnectionStatus
import finance.shilling.shared.data.sync.ServerApi
import finance.shilling.shared.session.SessionState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class DeviceDisplay(
    val deviceId: String,
    val ownerLabel: String,
    val connectionLabel: String,
    val currentDevice: Boolean,
    val canRemove: Boolean
)

data class HostedDevicesUiState(
    val visible: Boolean = false,
    val selfHosted: Boolean = false,
    val relayEnabled: Boolean = false,
    val relayAvailable: Boolean = false,
    val planLabel: String? = null,
    val accountPlan: HostedPlan? = null,
    val accountPlanLabel: String? = null,
    val billingConfig: HostedBillingConfig? = null,
    val userId: String? = null,
    val canPurchase: Boolean = false,
    val bankReadingEnabled: Boolean = false,
    val deviceAllowance: String? = null,
    val devices: List<DeviceDisplay> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/** Registration metadata comes from the hosted control plane; connections come from local WebRTC. */
class HostedDevicesViewModel(
    private val sessionState: SessionState,
    private val settings: Settings,
    private val httpClient: HttpClient,
    private val peerConnectionStatus: PeerConnectionStatus,
    private val cloudRelayCallback: CloudRelayCallback
) : ViewModel() {
    private val mutableState = MutableStateFlow(HostedDevicesUiState())
    val state: StateFlow<HostedDevicesUiState> = mutableState

    private var authService: AuthService? = null
    private var currentUserId: String? = null
    private var currentDeviceId = ""
    private var registered = emptyList<RegisteredDevice>()
    private var entitlements: HostedEntitlementsResponse? = null
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    private data class Identity(
        val userId: String?,
        val deviceId: String,
        val isAuthenticated: Boolean,
        val isAnonymous: Boolean
    )

    init {
        viewModelScope.launch {
            peerConnectionStatus.connectedPeerIds.collect { publishDevices() }
        }
        viewModelScope.launch {
            sessionState.phase.collectLatest { phase ->
                if (phase !is SessionPhase.Ready) {
                    authService = null
                    currentUserId = null
                    currentDeviceId = ""
                    registered = emptyList()
                    entitlements = null
                    mutableState.value = HostedDevicesUiState()
                    return@collectLatest
                }
                authService = phase.authService
                // Token refreshes do not change the displayed identity or restart polling.
                phase.authService.authState.map {
                    Identity(it.userId, it.deviceId, it.isAuthenticated, it.isAnonymous)
                }.distinctUntilChanged().collectLatest { auth ->
                    currentUserId = auth.userId
                    currentDeviceId = auth.deviceId
                    registered = emptyList()
                    entitlements = null
                    mutableState.value = HostedDevicesUiState(
                        visible = true,
                        selfHosted = phase.selfHosted,
                        relayEnabled = settings.getBoolean(SETTINGS_KEY_CLOUD_RELAY_ENABLED, false),
                        relayAvailable = phase.selfHosted,
                        userId = auth.userId,
                        canPurchase = !phase.selfHosted && auth.isAuthenticated && !auth.isAnonymous,
                        loading = !phase.selfHosted && auth.isAuthenticated
                    )
                    if (phase.selfHosted || !auth.isAuthenticated) return@collectLatest
                    // Native tabs retain their models. Keep network work scoped to an
                    // active UI subscription, including cancellation of in-flight requests.
                    mutableState.subscriptionCount.map { it > 0 }.distinctUntilChanged()
                        .collectLatest { observed ->
                            if (!observed) return@collectLatest
                            var billing: HostedBillingConfig? = null
                            var billingServer: String? = null
                            refreshRequests.tryReceive()
                            while (currentCoroutineContext().isActive) {
                                val url = serverUrl()
                                if (url != billingServer) billing = null
                                billing = loadDevices(phase.authService, url, billing)
                                billingServer = url
                                // One loop owns refreshes; repeated taps cannot launch
                                // overlapping loads or a queue of redundant requests.
                                withTimeoutOrNull(15_000) { refreshRequests.receive() }
                            }
                        }
                }
            }
        }
    }

    fun setCloudRelay(enabled: Boolean) {
        if (enabled && !state.value.relayAvailable) return
        cloudRelayCallback.onChange(enabled)
        mutableState.value = state.value.copy(relayEnabled = enabled)
    }

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    suspend fun removeDevice(deviceId: String): String {
        val service = authService ?: return "Sign in to manage devices"
        return runCatching {
            ServerApi(httpClient, serverUrl(), service).removeDevice(deviceId)
            refresh()
            "Device removed"
        }.getOrElse {
            if (it is CancellationException) throw it
            it.message ?: "Could not remove device"
        }
    }

    private suspend fun loadDevices(
        service: AuthService,
        url: String,
        cachedBilling: HostedBillingConfig?
    ): HostedBillingConfig? {
        val api = ServerApi(httpClient, url, service)
        return try {
            val (plan, details, billing) = coroutineScope {
                val plan = async { api.fetchEntitlements() }
                val details = async { api.fetchDeviceDetails() }
                val billing = async { cachedBilling ?: api.fetchBillingConfig() }
                Triple(plan.await(), details.await(), billing.await())
            }
            currentCoroutineContext().ensureActive()
            entitlements = plan
            registered = details
            mutableState.value = state.value.copy(
                relayAvailable = plan.turnEnabled,
                planLabel = plan.householdPlan.name.lowercase().replaceFirstChar { it.uppercase() },
                accountPlan = plan.accountPlan,
                accountPlanLabel = plan.accountPlan.name.lowercase().replaceFirstChar { it.uppercase() },
                billingConfig = billing,
                bankReadingEnabled = plan.bankReadingEnabled,
                deviceAllowance = "${plan.registeredDevices} / ${plan.deviceLimit?.toString() ?: "unlimited"}",
                loading = false,
                error = null
            )
            publishDevices()
            billing
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            mutableState.value = state.value.copy(loading = false, error = error.message ?: "Could not load devices")
            cachedBilling
        }
    }

    private fun publishDevices() {
        val connected = peerConnectionStatus.connectedPeerIds.value
        mutableState.value = state.value.copy(devices = registered.sortedWith(
            compareBy<RegisteredDevice> {
                if (it.deviceId == currentDeviceId) 0 else if (currentUserId != null && it.ownerUserId == currentUserId) 1 else 2
            }.thenBy { it.deviceId }
        ).map { device ->
            val isCurrent = device.deviceId == currentDeviceId
            DeviceDisplay(
                deviceId = device.deviceId,
                ownerLabel = when {
                    currentUserId != null && device.ownerUserId == currentUserId -> "Your account"
                    device.ownerUserId == null -> "Legacy registration · account unknown"
                    else -> "Another member's account"
                },
                connectionLabel = when {
                    isCurrent -> "This device"
                    device.deviceId in connected -> "Connected to this device"
                    else -> "Not connected to this device"
                },
                currentDevice = isCurrent,
                canRemove = entitlements?.canManageSpace == true && !isCurrent
            )
        })
    }

    private fun serverUrl(): String = settings.getStringOrNull(SETTINGS_KEY_SERVER_URL) ?: DEFAULT_SERVER_URL
}
