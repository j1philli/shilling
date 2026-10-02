package finance.shilling.app

import finance.shilling.shared.data.auth.AuthErrors

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.CredentialsCopy
import finance.shilling.shared.presentation.CredentialsOutcome
import finance.shilling.shared.presentation.SettingsUiState
import finance.shilling.shared.presentation.SettingsViewModel
import finance.shilling.shared.presentation.HostedDevicesViewModel
import finance.shilling.shared.presentation.HostedDevicesUiState
import finance.shilling.shared.presentation.ThemeMode
import finance.shilling.shared.presentation.fullLabel
import finance.shilling.shared.session.HostedCredentialsMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.DayOfWeek

/** Swift-facing Settings. Week days and results are flattened to Swift-friendly types. */
class SettingsScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<SettingsViewModel>()
    private val devicesViewModel = viewModel<HostedDevicesViewModel>()
    private val spacesViewModel = viewModel<finance.shilling.shared.presentation.HostedSpacesViewModel>()
    @NativeCoroutinesState
    val spacesState: StateFlow<finance.shilling.shared.presentation.HostedSpacesUiState> = spacesViewModel.state
    val transferToday: String get() = spacesViewModel.transferToday
    fun createTransferId(): String = spacesViewModel.newTransferId()
    @NativeCoroutines
    suspend fun transferAction(linkId: String, fromSpace: String, fromAccount: String, toSpace: String, toAccount: String, title: String, amount: String, date: String, delete: Boolean): String =
        spacesViewModel.transferAction(linkId, fromSpace, fromAccount, toSpace, toAccount, title, amount, date, delete)
    fun refreshSpaces() = spacesViewModel.refresh()
    @NativeCoroutines
    suspend fun spaceAction(action: String, spaceId: String?, name: String?, kind: String?, email: String?, role: String?, userId: String?, invitationId: String?, code: String?): String =
        spacesViewModel.execute(finance.shilling.core.auth.HostedSpaceCommand(action, spaceId, name, kind, email, role, userId, invitationId, code))

    @NativeCoroutinesState
    val state: StateFlow<SettingsUiState> = viewModel.state

    @NativeCoroutinesState
    val devicesState: StateFlow<HostedDevicesUiState> = devicesViewModel.state

    /** Show developer tools without the About-tap unlock. */
    val developerToolsAlwaysOn: Boolean = isDebugBuild()

    val themeModes: List<ThemeMode> = viewModel.themeModes
    val currencyOptions: List<String> = viewModel.currencyOptions
    val weekDayLabels: List<String> = DayOfWeek.entries.map { it.fullLabel }

    fun weekStartIndex(state: SettingsUiState): Int = state.prefs.weekStart.ordinal

    fun setThemeMode(mode: ThemeMode) = viewModel.setThemeMode(mode)
    fun setCurrencySymbol(symbol: String) = viewModel.setCurrencySymbol(symbol)
    fun setWeekStart(index: Int) = viewModel.setWeekStart(DayOfWeek.entries[index])
    fun aboutTapped(): Boolean = viewModel.aboutTapped()
    fun retrySync() = viewModel.retrySync()
    fun refreshDevices() = devicesViewModel.refresh()
    fun setCloudRelay(enabled: Boolean) = devicesViewModel.setCloudRelay(enabled)

    @NativeCoroutines
    suspend fun removeDevice(deviceId: String): String = devicesViewModel.removeDevice(deviceId)

    @NativeCoroutines
    suspend fun silverOffers(): List<IosBillingOffer> = billingOffers(gold = false)

    @NativeCoroutines
    suspend fun goldOffers(): List<IosBillingOffer> = billingOffers(gold = true)

    @NativeCoroutines
    suspend fun purchaseSilver(packageId: String): Boolean = purchase(gold = false, packageId = packageId)

    @NativeCoroutines
    suspend fun purchaseGold(packageId: String): Boolean = purchase(gold = true, packageId = packageId)

    @NativeCoroutines
    suspend fun restorePurchases() {
        requireBillingIdentity()
        IosBilling.restore()
        devicesViewModel.refresh()
    }

    @NativeCoroutines
    suspend fun subscriptionManagementUrl(): String? {
        requireBillingIdentity()
        return IosBilling.managementUrl()
    }

    private suspend fun billingOffers(gold: Boolean): List<IosBillingOffer> {
        val config = requireBillingIdentity()
        val offeringId = if (gold) config.goldOfferingId else config.silverOfferingId
        return offeringId?.let { IosBilling.offers(it) }.orEmpty()
    }

    private suspend fun purchase(gold: Boolean, packageId: String): Boolean {
        val config = requireBillingIdentity()
        val offeringId = (if (gold) config.goldOfferingId else config.silverOfferingId)
            ?: error("Subscription offering unavailable")
        val purchased = IosBilling.purchase(offeringId, packageId)
        if (purchased) devicesViewModel.refresh()
        return purchased
    }

    private suspend fun requireBillingIdentity(): finance.shilling.core.auth.HostedBillingConfig {
        val state = devicesViewModel.state.value
        check(state.canPurchase) { "Sign in to manage subscriptions" }
        val config = state.billingConfig ?: error("Subscription setup unavailable")
        val userId = state.userId ?: error("Account unavailable")
        val key = config.iosPublicKey ?: error("iOS subscription key unavailable")
        IosBilling.configure(key, userId)
        return config
    }
    fun saveServerUrl(url: String): String = viewModel.saveServerUrl(url)
    fun saveHouseholdId(id: String): String = viewModel.saveHouseholdId(id)
    fun setAnalyticsConsent(value: Boolean) = viewModel.setAnalyticsConsent(value)
    fun saveAnalyticsConfig(host: String, projectToken: String): Boolean = viewModel.saveAnalyticsConfig(host, projectToken)
    val analyticsHost: String get() = viewModel.analyticsHost
    val analyticsProjectToken: String get() = viewModel.analyticsProjectToken

    @NativeCoroutines
    suspend fun submitCredentials(mode: HostedCredentialsMode, email: String, password: String): CredentialsOutcome {
        val trimmed = email.trim()
        return CredentialsCopy.outcome(viewModel.submitCredentials(mode, trimmed, password), trimmed)
    }

    @NativeCoroutines
    suspend fun refreshAccountStatus(): String = viewModel.refreshAccountStatus().fold(
        onSuccess = { "Account status checked." },
        onFailure = { AuthErrors.message(it, "refresh_account") }
    )

    @NativeCoroutines
    suspend fun setPassword(password: String): String = viewModel.setPassword(password).fold(
        onSuccess = { "Password set." },
        onFailure = { AuthErrors.message(it, "set_password") }
    )

    @NativeCoroutines
    suspend fun sendSignInLink(email: String): String = viewModel.sendSignInLink(email).fold(
        onSuccess = { "Sign-in link sent. Open it on this device." },
        onFailure = { AuthErrors.message(it, "send_sign_in_link") }
    )

    @NativeCoroutines
    suspend fun confirmAccountAction(): Unit = viewModel.confirmAccountAction()

    @NativeCoroutines
    suspend fun addSampleData(): String = viewModel.addSampleData()
}
