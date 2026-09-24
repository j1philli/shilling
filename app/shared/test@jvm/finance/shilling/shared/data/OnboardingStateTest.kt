package finance.shilling.shared.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.store.StoreSyncDeps
import finance.shilling.shared.data.store.createAccountStore
import finance.shilling.shared.data.store.createCategoryStore
import finance.shilling.shared.data.store.createPostingStore
import finance.shilling.shared.data.store.createReceiptStore
import finance.shilling.shared.data.store.createScheduleExceptionStore
import finance.shilling.shared.data.store.createScheduleStore
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingStateTest {
    @Test
    fun clearHostedAuthSessionStateLeavesNonSessionSettingsAlone() {
        val settings = Settings().also {
            it.putString(SETTINGS_KEY_SERVER_URL, "https://example.test")
            it.putString(SETTINGS_KEY_ONBOARDING_COMPLETED, "true")
            it.putString(SETTINGS_KEY_DEPLOYMENT_SELECTION, DeploymentSelection.HOSTED.name)
            it.putString(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID, "local-household")
            it.putString(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID, "hosted-household")
            it.putString(SETTINGS_KEY_AUTH_MODE, "SUPABASE")
            it.putString(SETTINGS_KEY_SUPABASE_URL, "https://supabase.test")
            it.putString(SETTINGS_KEY_SUPABASE_ANON_KEY, "anon-key")
            it.putString(SETTINGS_KEY_AUTH_ACCESS_TOKEN, "access-token")
            it.putString(SETTINGS_KEY_AUTH_REFRESH_TOKEN, "refresh-token")
            it.putString(SETTINGS_KEY_AUTH_USER_ID, "user-1")
            it.putString(SETTINGS_KEY_AUTH_TIER, "FREE")
            it.putString(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION, "true")
            it.putString(SETTINGS_KEY_AUTH_PENDING_EMAIL, "user@example.com")
        }

        clearHostedAuthSessionState(settings)

        assertEquals("https://example.test", settings.getStringOrNull(SETTINGS_KEY_SERVER_URL))
        assertEquals("true", settings.getStringOrNull(SETTINGS_KEY_ONBOARDING_COMPLETED))
        assertEquals(DeploymentSelection.HOSTED.name, settings.getStringOrNull(SETTINGS_KEY_DEPLOYMENT_SELECTION))
        assertEquals("local-household", settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID))
        assertEquals("SUPABASE", settings.getStringOrNull(SETTINGS_KEY_AUTH_MODE))
        assertEquals("https://supabase.test", settings.getStringOrNull(SETTINGS_KEY_SUPABASE_URL))
        assertEquals("anon-key", settings.getStringOrNull(SETTINGS_KEY_SUPABASE_ANON_KEY))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_AUTH_ACCESS_TOKEN))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_AUTH_REFRESH_TOKEN))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_AUTH_USER_ID))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_AUTH_TIER))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL_CONFIRMATION))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_AUTH_PENDING_EMAIL))
    }

    @Test
    fun softReturnToWelcomeKeepsLocalIdentityAndMarksHeldData() {
        val settings = Settings().also {
            it.putString(SETTINGS_KEY_ONBOARDING_COMPLETED, "true")
            it.putString(SETTINGS_KEY_DEPLOYMENT_SELECTION, DeploymentSelection.HOSTED.name)
            it.putString(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID, "local-household")
            it.putString(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID, "hosted-household")
            it.putString(SETTINGS_KEY_AUTH_USER_ID, "user-1")
            it.putString(SETTINGS_KEY_AUTH_TIER, "FREE")
            it.putString(SETTINGS_KEY_AUTH_ACCESS_TOKEN, "access-token")
        }

        softReturnToWelcome(settings, notice = "session_expired")

        assertTrue(shouldShowFirstLaunchOnboarding(settings))
        assertTrue(hasHeldLocalData(settings))
        assertEquals("user-1", pendingRestoreUserId(settings))
        assertEquals("session_expired", welcomeNotice(settings))
        assertEquals("local-household", settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID))
        assertEquals("hosted-household", settings.getStringOrNull(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_AUTH_ACCESS_TOKEN))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_AUTH_USER_ID))
        assertTrue(matchesPendingRestore(settings, "user-1"))
        assertFalse(matchesPendingRestore(settings, "other-user"))
    }

    @Test
    fun completeFirstLaunchOnboardingClearsHoldState() {
        val settings = Settings().also {
            it.putString(SETTINGS_KEY_HELD_LOCAL_DATA, "true")
            it.putString(SETTINGS_KEY_PENDING_RESTORE_USER_ID, "user-1")
            it.putString(SETTINGS_KEY_WELCOME_NOTICE, "session_expired")
        }

        completeFirstLaunchOnboarding(
            settings = settings,
            selection = DeploymentSelection.HOSTED,
            serverUrl = "http://localhost:8081"
        )

        assertFalse(shouldShowFirstLaunchOnboarding(settings))
        assertFalse(hasHeldLocalData(settings))
        assertNull(pendingRestoreUserId(settings))
        assertNull(welcomeNotice(settings))
        assertEquals(DeploymentSelection.HOSTED.name, settings.getStringOrNull(SETTINGS_KEY_DEPLOYMENT_SELECTION))
    }

    @Test
    fun wipeLocalAppStateClearsLocalDataAndSettings() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version};", 0)

        val db = ShillingDatabase(driver)
        val settings = Settings().also {
            it.putString(SETTINGS_KEY_SERVER_URL, "https://example.test")
            it.putString(SETTINGS_KEY_ONBOARDING_COMPLETED, "true")
            it.putString(SETTINGS_KEY_DEPLOYMENT_SELECTION, DeploymentSelection.SELF_HOSTED.name)
            it.putString(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID, "local-household")
            it.putString(SETTINGS_KEY_HOSTED_HOUSEHOLD_ID, "hosted-household")
            it.putString(SETTINGS_KEY_HELD_LOCAL_DATA, "true")
            it.putString(SETTINGS_KEY_PENDING_RESTORE_USER_ID, "user-1")
        }
        val idGenerator = object : IdGenerator {
            private var nextId = 0
            override fun newId(): String {
                nextId += 1
                return "id-$nextId"
            }
        }
        val notifier = ChangeNotifier()
        val syncDeps = StoreSyncDeps(db, null, "device-1", idGenerator)
        val accountStore = createAccountStore(db, syncDeps)
        val categoryStore = createCategoryStore(db, syncDeps)
        val scheduleStore = createScheduleStore(db, syncDeps)
        val scheduleExceptionStore = createScheduleExceptionStore(db, syncDeps)
        val postingStore = createPostingStore(db, syncDeps)
        val receiptStore = createReceiptStore(db, syncDeps)
        val accountRepository = AccountRepository(notifier, accountStore, syncDeps)
        val categoryRepository = CategoryRepository(notifier, categoryStore, syncDeps)
        val scheduleRepository = ScheduleRepository(notifier, scheduleStore, scheduleExceptionStore, syncDeps)
        val postingRepository = PostingRepository(
            idGenerator,
            notifier,
            postingStore,
            accountStore,
            categoryStore,
            scheduleStore,
            syncDeps
        )
        val receiptRepository = ReceiptRepository(notifier, receiptStore, postingStore, scheduleStore, syncDeps)

        accountRepository.upsert(Account(id = "a1", name = "Checking", balance = 10.0))
        assertEquals(1, db.accountQueries.selectAll().awaitAsList().size)

        wipeLocalAppState(
            db = db,
            settings = settings,
            fileStore = object : ReceiptFileStore {
                override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {}
                override suspend fun read(receiptId: String): ByteArray? = null
                override suspend fun hasFile(receiptId: String): Boolean = false
                override suspend fun delete(receiptId: String) {}
                override suspend fun clearAll() {}
                override suspend fun openExternally(receiptId: String, originalName: String) {}
            },
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            scheduleRepository = scheduleRepository,
            postingRepository = postingRepository,
            receiptRepository = receiptRepository
        )

        assertEquals(0, db.accountQueries.selectAll().awaitAsList().size)
        assertNull(settings.getStringOrNull(SETTINGS_KEY_ONBOARDING_COMPLETED))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_HELD_LOCAL_DATA))
        assertNull(settings.getStringOrNull(SETTINGS_KEY_PENDING_RESTORE_USER_ID))
    }
}
