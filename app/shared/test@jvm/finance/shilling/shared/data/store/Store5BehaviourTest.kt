package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.sync.ChangeMessage
import finance.shilling.shared.data.sync.FileTransferMessage
import finance.shilling.shared.data.sync.PeerSyncManager
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Empirical Store5 behaviour checks against 5.1.0-beta01.
 *
 * These assert known footguns so we can track whether they remain after fixes.
 */
@OptIn(ExperimentalStoreApi::class)
class Store5BehaviourTest {
    private fun openDb(): ShillingDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        runBlocking { ShillingDatabase.Schema.create(driver).await() }
        return ShillingDatabase(driver)
    }

    @Test
    fun getAllSeesByIdWriteImmediately() = runBlocking {
        val db = openDb()
        val notifier = ChangeNotifier()
        val store = createAccountStore(db)
        val repo = AccountRepository(notifier, store)

        val checking = Account(id = "a1", name = "Checking", balance = 100.0)
        repo.upsert(checking)

        // Warm All-key path (cache disabled / SoT-backed).
        val before = repo.getAll()
        assertEquals(100.0, before["a1"]?.balance)

        // Write through ById — SoT read must see the new balance immediately.
        repo.upsert(checking.copy(balance = 250.0))

        val afterImmediate = repo.getAll()
        assertEquals(
            250.0,
            afterImmediate["a1"]?.balance,
            "Expected fresh AccountKey.All SoT read after ById write; got ${afterImmediate["a1"]?.balance}"
        )
    }

    @Test
    fun bookkeepingStaysEmptyWhenBroadcastHasNoPeer() = runBlocking {
        val db = openDb()
        val notifier = ChangeNotifier()
        val sync = StoreSyncDeps(
            db = db,
            peerSyncManager = null,
            deviceId = "device-1",
            idGenerator = object : IdGenerator {
                override fun newId(): String = "change-1"
            }
        )
        val store = createAccountStore(db, sync)
        val repo = AccountRepository(notifier, store, sync)

        repo.upsert(Account(id = "a1", name = "Checking", balance = 10.0))

        // toBookkeepingKey uses lowercase "account"
        val rows = db.bookkeepingQueries.selectByType("account").awaitAsList()
        assertTrue(
            rows.isEmpty(),
            "Bookkeeper should stay empty while updater returns Success with null peer; found $rows"
        )
    }

    @Test
    fun bookkeepingRecordsRowWhenBroadcastThrows() = runBlocking {
        val db = openDb()
        val notifier = ChangeNotifier()
        val sync = StoreSyncDeps(
            db = db,
            peerSyncManager = ThrowingPeerSyncManager(),
            deviceId = "device-1",
            idGenerator = object : IdGenerator {
                override fun newId(): String = "change-1"
            }
        )
        val store = createAccountStore(db, sync)
        val repo = AccountRepository(notifier, store, sync)

        repo.upsert(Account(id = "a1", name = "Checking", balance = 10.0))

        val rows = db.bookkeepingQueries.selectByType("account").awaitAsList()
        assertTrue(
            rows.isNotEmpty(),
            "Bookkeeper should record a failed sync when broadcast throws; found $rows"
        )
        assertEquals("a1", rows.single().entity_id)
    }

    @Test
    fun sourceOfTruthReadSeesByIdWriteImmediately() = runBlocking {
        val db = openDb()
        val store = createAccountStore(db)

        store.write(
            StoreWriteRequest.of(
                AccountKey.ById("a1"),
                listOf(Account(id = "a1", name = "Checking", balance = 100.0))
            )
        )
        // Warm All path via cached read helper
        store.readCached(AccountKey.All)

        store.write(
            StoreWriteRequest.of(
                AccountKey.ById("a1"),
                listOf(Account(id = "a1", name = "Checking", balance = 999.0))
            )
        )

        val fromSot = store.readLocalSourceOfTruth(AccountKey.All)
        assertEquals(999.0, fromSot.first { it.id == "a1" }.balance)
    }

    private class ThrowingPeerSyncManager : PeerSyncManager {
        override val incomingChanges: SharedFlow<ChangeMessage> =
            MutableSharedFlow(extraBufferCapacity = 1)
        override val incomingFileMessages: SharedFlow<Pair<String, FileTransferMessage>> =
            MutableSharedFlow(extraBufferCapacity = 1)
        override val peerConnected: SharedFlow<String> =
            MutableSharedFlow(extraBufferCapacity = 1)

        override fun start(scope: CoroutineScope) {}

        override suspend fun broadcast(change: ChangeMessage) {
            throw IllegalStateException("broadcast failed")
        }

        override suspend fun sendToPeer(peerId: String, change: ChangeMessage) {}

        override suspend fun sendFileMessage(peerId: String, message: FileTransferMessage) {}

        override suspend fun broadcastFileMessage(message: FileTransferMessage) {}

        override fun stop() {}
    }
}
