package finance.shilling.shared.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.auth.DeviceIdentity
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
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
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
            single<finance.shilling.shared.data.store.ReceiptFileStoreFactory> { finance.shilling.shared.data.store.ReceiptFileStoreFactory { _, _ -> InMemoryReceiptFileStore() } }
        }
        val app = koinApplication { modules(platformModule, dataModule) }

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
            // Stores and repositories must share one StoreSyncDeps so the sync runtime can
            // attach its peer manager in a single place.
            assertSame(koin.get<StoreSyncDeps>(), koin.get<StoreSyncDeps>())
            assertEquals(koin.get<DeviceIdentity>().deviceId, koin.get<StoreSyncDeps>().state.deviceId)
        } finally {
            app.close()
        }
    }

    @Test
    fun initKoinStartsOneGlobalGraph() {
        val settings = Settings()
        try {
            val first = initKoin(module { single { settings } })
            // A second entry point (e.g. a recreated Android Activity) gets the running graph.
            val second = initKoin(module { single { Settings() } })
            assertSame(first, second)
            assertSame(first, KoinPlatform.getKoin())
            assertSame(settings, second.get<Settings>())
        } finally {
            stopKoin()
        }
    }

    private class InMemoryReceiptFileStore : ReceiptFileStore {
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {}

        override suspend fun read(receiptId: String): ByteArray? = null
        override suspend fun openReader(receiptId: String) = read(receiptId)?.asReceiptReader()

        override suspend fun hasFile(receiptId: String): Boolean = false

        override suspend fun delete(receiptId: String) {}

        override suspend fun clearAll() {}

        override suspend fun openExternally(receiptId: String, originalName: String) {}
    }
}
