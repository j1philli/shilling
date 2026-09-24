package finance.shilling.shared.data

import com.russhwolf.settings.Settings
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.store.clearAllSyncMetadata
import finance.shilling.shared.db.ShillingDatabase

enum class DeploymentSelection {
    HOSTED,
    SELF_HOSTED
}

fun shouldShowFirstLaunchOnboarding(settings: Settings): Boolean {
    return settings.getStringOrNull(SETTINGS_KEY_ONBOARDING_COMPLETED) != "true"
}

fun savedDeploymentSelection(settings: Settings): DeploymentSelection? =
    settings.getStringOrNull(SETTINGS_KEY_DEPLOYMENT_SELECTION)
        ?.let { raw -> runCatching { DeploymentSelection.valueOf(raw) }.getOrNull() }

fun completeFirstLaunchOnboarding(
    settings: Settings,
    selection: DeploymentSelection,
    serverUrl: String
) {
    settings.putString(SETTINGS_KEY_SERVER_URL, serverUrl)
    settings.putString(SETTINGS_KEY_DEPLOYMENT_SELECTION, selection.name)
    settings.remove(SETTINGS_KEY_HOSTED_ENTRY_MODE)
    settings.putString(SETTINGS_KEY_ONBOARDING_COMPLETED, "true")
    clearWelcomeHoldState(settings)
}

fun hasHeldLocalData(settings: Settings): Boolean =
    settings.getStringOrNull(SETTINGS_KEY_HELD_LOCAL_DATA) == "true"

fun pendingRestoreUserId(settings: Settings): String? =
    settings.getStringOrNull(SETTINGS_KEY_PENDING_RESTORE_USER_ID)?.takeIf { it.isNotBlank() }

fun welcomeNotice(settings: Settings): String? =
    settings.getStringOrNull(SETTINGS_KEY_WELCOME_NOTICE)?.takeIf { it.isNotBlank() }

fun clearWelcomeHoldState(settings: Settings) {
    settings.remove(SETTINGS_KEY_HELD_LOCAL_DATA)
    settings.remove(SETTINGS_KEY_PENDING_RESTORE_USER_ID)
    settings.remove(SETTINGS_KEY_WELCOME_NOTICE)
}

/**
 * Soft-return to Welcome after a lost managed session. Keeps local budget data and
 * remembers the prior account id so a matching re-login can resume without wiping.
 */
fun softReturnToWelcome(settings: Settings, notice: String = "session_expired") {
    val priorUserId = settings.getStringOrNull(SETTINGS_KEY_AUTH_USER_ID)
    if (!priorUserId.isNullOrBlank()) {
        settings.putString(SETTINGS_KEY_PENDING_RESTORE_USER_ID, priorUserId)
    }
    settings.putString(SETTINGS_KEY_HELD_LOCAL_DATA, "true")
    settings.putString(SETTINGS_KEY_WELCOME_NOTICE, notice)
    listOf(
        SETTINGS_KEY_ONBOARDING_COMPLETED,
        SETTINGS_KEY_HOSTED_ENTRY_MODE,
        SETTINGS_KEY_AUTH_ACCESS_TOKEN,
        SETTINGS_KEY_AUTH_REFRESH_TOKEN,
        SETTINGS_KEY_AUTH_USER_ID,
        SETTINGS_KEY_AUTH_TIER,
        SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION,
        SETTINGS_KEY_AUTH_PENDING_EMAIL
    ).forEach(settings::remove)
}

fun hadPersistedAccountSession(settings: Settings): Boolean {
    val userId = settings.getStringOrNull(SETTINGS_KEY_AUTH_USER_ID)
    val tier = settings.getStringOrNull(SETTINGS_KEY_AUTH_TIER)
    return !userId.isNullOrBlank() && !tier.isNullOrBlank() && tier != "ANONYMOUS"
}

fun matchesPendingRestore(settings: Settings, userId: String?): Boolean {
    val pending = pendingRestoreUserId(settings) ?: return false
    return !userId.isNullOrBlank() && userId == pending
}

fun resetOnboardingState(settings: Settings) {
    listOf(
        SETTINGS_KEY_SERVER_URL,
        SETTINGS_KEY_ONBOARDING_COMPLETED,
        SETTINGS_KEY_DEPLOYMENT_SELECTION,
        SETTINGS_KEY_HOSTED_ENTRY_MODE,
        SETTINGS_KEY_LOCAL_HOUSEHOLD_ID,
        SETTINGS_KEY_HOSTED_HOUSEHOLD_ID,
        SETTINGS_KEY_AUTH_MODE,
        SETTINGS_KEY_SUPABASE_URL,
        SETTINGS_KEY_SUPABASE_ANON_KEY,
        SETTINGS_KEY_AUTH_ACCESS_TOKEN,
        SETTINGS_KEY_AUTH_REFRESH_TOKEN,
        SETTINGS_KEY_AUTH_USER_ID,
        SETTINGS_KEY_AUTH_TIER,
        SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION,
        SETTINGS_KEY_AUTH_PENDING_EMAIL,
        SETTINGS_KEY_HELD_LOCAL_DATA,
        SETTINGS_KEY_PENDING_RESTORE_USER_ID,
        SETTINGS_KEY_WELCOME_NOTICE
    ).forEach(settings::remove)
}

fun clearHostedAuthSessionState(settings: Settings) {
    listOf(
        SETTINGS_KEY_HOSTED_HOUSEHOLD_ID,
        SETTINGS_KEY_AUTH_ACCESS_TOKEN,
        SETTINGS_KEY_AUTH_REFRESH_TOKEN,
        SETTINGS_KEY_AUTH_USER_ID,
        SETTINGS_KEY_AUTH_TIER,
        SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION,
        SETTINGS_KEY_AUTH_PENDING_EMAIL
    ).forEach(settings::remove)
}

/**
 * Wipes all local app state. All entity deletes go through Store5 repositories so that
 * in-memory caches stay coherent with the SQLDelight source of truth. The sync-metadata
 * tables (change_log, bookkeeping) are cleared through the store-layer helper that owns
 * them. The binary receipt file store (a platform service, not a Store5 entity) is
 * cleared directly via its interface.
 */
suspend fun wipeLocalAppState(
    db: ShillingDatabase,
    settings: Settings,
    fileStore: ReceiptFileStore,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    scheduleRepository: ScheduleRepository,
    postingRepository: PostingRepository,
    receiptRepository: ReceiptRepository
) {
    fileStore.clearAll()
    // Order respects FK dependencies: receipts → postings → schedules (cascades exceptions
    // + schedule-linked postings) → categories → accounts (cascades any remaining postings).
    receiptRepository.clearAll()
    postingRepository.clearAll()
    scheduleRepository.clearAll()
    categoryRepository.clearAll()
    accountRepository.clearAll()
    db.clearAllSyncMetadata()
    resetOnboardingState(settings)
}
