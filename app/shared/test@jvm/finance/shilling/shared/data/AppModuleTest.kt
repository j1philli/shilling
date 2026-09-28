package finance.shilling.shared.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.auth.DeviceIdentity
import finance.shilling.shared.data.auth.HostedBootstrapPhase
import finance.shilling.shared.data.auth.HostedBootstrapRetryCallback
import finance.shilling.shared.data.auth.HostedBootstrapState
import finance.shilling.shared.data.auth.HostedBootstrapStatus
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.store.StoreSyncDeps
import finance.shilling.shared.data.store.SyncStoreFacade
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class AppModuleTest {
    @Test
    fun sharedModuleResolvesCoreBindings() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        driver.execute(null, "PRAGMA user_version = ${ShillingDatabase.Schema.version};", 0)

        val db = ShillingDatabase(driver)
        val settings = Settings()
        val idGenerator = object : IdGenerator {
            private var nextId = 0

            override fun newId(): String {
                nextId += 1
                return "id-$nextId"
            }
        }
        val platformModule = module {
            single { db }
            single { settings }
            single<IdGenerator> { idGenerator }
            single<ReceiptFileStore> { InMemoryReceiptFileStore() }
        }
        var updatedServerUrl: String? = null
        var updatedHouseholdId: String? = null
        var hostedBootstrapRetryCount = 0
        val hostedBootstrapState = HostedBootstrapState(
            MutableStateFlow(
                HostedBootstrapStatus(
                    phase = HostedBootstrapPhase.LOCAL_ONLY,
                    syncReady = true
                )
            )
        )
        val hostedBootstrapRetryCallback = HostedBootstrapRetryCallback {
            hostedBootstrapRetryCount += 1
        }
        var resetOnboardingUiCount = 0
        var restartHostedLoginUiCount = 0
        val onResetOnboardingUi: suspend () -> Unit = {
            resetOnboardingUiCount += 1
        }
        val onRestartHostedLoginUi: suspend () -> Unit = {
            restartHostedLoginUiCount += 1
        }

        val app = koinApplication {
            modules(
                platformModule,
                dataModule,
                bootstrapSessionModule(
                    hostedBootstrapState = hostedBootstrapState,
                    onRetryHostedBootstrap = hostedBootstrapRetryCallback,
                    onResetOnboardingUi = onResetOnboardingUi,
                    onRestartHostedLoginUi = onRestartHostedLoginUi,
                    onServerUrlChanged = { updatedServerUrl = it },
                    onHouseholdIdChanged = { updatedHouseholdId = it }
                )
            )
        }

        try {
            val koin = app.koin
            assertNotNull(koin.get<AccountRepository>())
            assertNotNull(koin.get<CategoryRepository>())
            assertNotNull(koin.get<ScheduleRepository>())
            assertNotNull(koin.get<PostingRepository>())
            assertNotNull(koin.get<ReceiptRepository>())
            assertNotNull(koin.get<ComputeWindowUseCase>())
            assertNotNull(koin.get<ComputeBudgetUseCase>())
            assertNotNull(koin.get<SyncStoreFacade>())
            assertNotNull(koin.get<LocalDataWiper>())
            assertNotNull(koin.get<HostedBootstrapState>())
            // Stores and repositories must share one StoreSyncDeps so the sync runtime can
            // attach its peer manager in a single place.
            assertSame(koin.get<StoreSyncDeps>(), koin.get<StoreSyncDeps>())
            assertEquals(koin.get<DeviceIdentity>().deviceId, koin.get<StoreSyncDeps>().state.deviceId)

            app.koin.get<ServerUrlCallback>().onChange("http://example.com")
            app.koin.get<HouseholdIdCallback>().onChange("household-1")
            app.koin.get<HostedBootstrapRetryCallback>().onRetry()
            app.koin.get<ResetOnboardingCallback>().onReset()
            app.koin.get<RestartHostedLoginCallback>().onRestart()

            assertEquals("http://example.com", updatedServerUrl)
            assertEquals("household-1", updatedHouseholdId)
            assertEquals(1, hostedBootstrapRetryCount)
            assertEquals(1, resetOnboardingUiCount)
            assertEquals(1, restartHostedLoginUiCount)
        } finally {
            app.close()
        }
    }

    private class InMemoryReceiptFileStore : ReceiptFileStore {
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {}

        override suspend fun read(receiptId: String): ByteArray? = null

        override suspend fun hasFile(receiptId: String): Boolean = false

        override suspend fun delete(receiptId: String) {}

        override suspend fun clearAll() {}

        override suspend fun openExternally(receiptId: String, originalName: String) {}
    }
}
