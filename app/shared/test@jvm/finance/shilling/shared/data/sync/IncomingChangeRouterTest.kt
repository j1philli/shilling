package finance.shilling.shared.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.AccountStore
import finance.shilling.shared.data.store.ApplyIncomingChangeResult
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.SyncStoreFacade
import finance.shilling.shared.data.store.StoreSyncDeps
import finance.shilling.shared.data.store.createAccountStore
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncomingChangeRouterTest {
    private class FixedIdGenerator(
        private val nextId: String = "generated-id"
    ) : IdGenerator {
        override fun newId(): String = nextId
    }

    private class InMemoryFileStore : ReceiptFileStore {
        private val files = mutableMapOf<String, ByteArray>()

        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
            files[receiptId] = bytes
        }

        override suspend fun read(receiptId: String): ByteArray? = files[receiptId]

        override suspend fun hasFile(receiptId: String): Boolean = receiptId in files

        override suspend fun delete(receiptId: String) {
            files.remove(receiptId)
        }

        override suspend fun clearAll() {
            files.clear()
        }

        override suspend fun openExternally(receiptId: String, originalName: String) {}
    }

    private class FakePeerSyncManager : PeerSyncManager {
        private val _incomingChanges = MutableSharedFlow<ChangeMessage>(extraBufferCapacity = 64)
        override val incomingChanges: SharedFlow<ChangeMessage> = _incomingChanges

        private val _incomingFileMessages = MutableSharedFlow<Pair<String, FileTransferMessage>>(extraBufferCapacity = 64)
        override val incomingFileMessages: SharedFlow<Pair<String, FileTransferMessage>> = _incomingFileMessages

        private val _peerConnected = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val peerConnected: SharedFlow<String> = _peerConnected

        private val sentFileBroadcasts = mutableListOf<FileTransferMessage>()
        private val sentChangeBroadcasts = mutableListOf<ChangeMessage>()

        override fun start(scope: CoroutineScope) {}

        override suspend fun broadcast(change: ChangeMessage) {
            synchronized(sentChangeBroadcasts) {
                sentChangeBroadcasts += change
            }
        }

        override suspend fun sendToPeer(peerId: String, change: ChangeMessage) {}

        override suspend fun sendFileMessage(peerId: String, message: FileTransferMessage) {}

        override suspend fun broadcastFileMessage(message: FileTransferMessage) {
            synchronized(sentFileBroadcasts) {
                sentFileBroadcasts += message
            }
        }

        override fun stop() {}

        suspend fun emitIncomingChange(change: ChangeMessage) {
            _incomingChanges.emit(change)
        }

        suspend fun emitIncomingFileMessage(peerId: String, message: FileTransferMessage) {
            _incomingFileMessages.emit(peerId to message)
        }

        fun fileRequestCount(receiptId: String): Int = synchronized(sentFileBroadcasts) {
            sentFileBroadcasts.count { it is FileTransferMessage.FileRequest && it.receiptId == receiptId }
        }

        fun changeBroadcastCount(): Int = synchronized(sentChangeBroadcasts) {
            sentChangeBroadcasts.size
        }
    }

    @Test
    fun fileNotAvailableForExistingReceiptIsRetried() = runBlockingTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        val db = ShillingDatabase(driver)

        val receiptId = "r-missing-file"
        db.receiptQueries.upsert(
            id = receiptId,
            posting_id = null,
            file_path = "missing.jpg",
            original_name = "missing.jpg",
            added_at = 1L,
            receipt_date = null,
            amount = null,
            notes = null
        )

        val notifier = ChangeNotifier()
        val peerSync = FakePeerSyncManager()
        val fileStore = InMemoryFileStore()
        val router = IncomingChangeRouter(
            storeFacade = SyncStoreFacade(db),
            notifier = notifier,
            webRtcManager = peerSync,
            fileTransferManager = FileTransferManager(fileStore),
            fileStore = fileStore,
            startupScanDelayMs = 0L,
            fileRetryIntervalMs = 100L
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            router.start(scope)

            waitUntil { peerSync.fileRequestCount(receiptId) >= 1 }
            val beforeNotAvailable = peerSync.fileRequestCount(receiptId)

            peerSync.emitIncomingFileMessage("peer-1", FileTransferMessage.FileNotAvailable(receiptId))

            waitUntil { peerSync.fileRequestCount(receiptId) > beforeNotAvailable }
            val afterNotAvailable = peerSync.fileRequestCount(receiptId)

            assertTrue(
                afterNotAvailable > beforeNotAvailable,
                "Expected retries to continue after FileNotAvailable for existing receipt"
            )
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    @Test
    fun incomingAccountChangeUpdatesStoreWatchers() = runBlockingTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        val db = ShillingDatabase(driver)

        val notifier = ChangeNotifier()
        val peerSync = FakePeerSyncManager()
        val accountStore: AccountStore = createAccountStore(
            db = db,
            sync = StoreSyncDeps(db, peerSync, "device-a", FixedIdGenerator())
        )
        val accountRepository = AccountRepository(
            notifier = notifier,
            store = accountStore,
            sync = null
        )
        val router = IncomingChangeRouter(
            storeFacade = SyncStoreFacade(db),
            notifier = notifier,
            webRtcManager = peerSync
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            router.start(scope)

            val initial = accountRepository.watchAll().first()
            assertTrue(initial.isEmpty(), "Expected empty initial account list")

            val emitted = kotlinx.coroutines.CompletableDeferred<List<Account>>()
            val watchJob = scope.launch {
                accountRepository.watchAll().collect { accounts ->
                    if (accounts.any { it.id == "acct-1" }) {
                        emitted.complete(accounts)
                        cancel()
                    }
                }
            }

            peerSync.emitIncomingChange(
                ChangeMessage(
                    id = "change-1",
                    entityType = EntityType.ACCOUNT,
                    op = ChangeOp.UPSERT,
                    entityId = "acct-1",
                    timestamp = 1L,
                    deviceId = "peer-b",
                    payload = ChangePayload.AccountPayload(
                        Account(
                            id = "acct-1",
                            name = "Checking",
                            balance = 42.0
                        )
                    )
                )
            )

            val accounts = withTimeout(2_000L) { emitted.await() }
            assertEquals(1, accounts.size)
            assertEquals("acct-1", accounts.single().id)
            assertEquals(42.0, accounts.single().balance)
            watchJob.cancel()
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    @Test
    fun fullStateSnapshotReadsStoreSourceOfTruthWithoutCachedAllKey() = runBlockingTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        val db = ShillingDatabase(driver)

        val notifier = ChangeNotifier()
        val peerSync = FakePeerSyncManager()
        val accountStore: AccountStore = createAccountStore(
            db = db,
            sync = StoreSyncDeps(db, peerSync, "device-a", FixedIdGenerator())
        )
        val accountRepository = AccountRepository(
            notifier = notifier,
            store = accountStore,
            sync = null
        )
        val facade = SyncStoreFacade(
            db = db,
            accountStore = accountStore
        )

        try {
            accountRepository.upsert(
                Account(
                    id = "acct-1",
                    name = "Checking",
                    balance = 42.0
                )
            )

            val snapshot = facade.buildFullStateSnapshot(deviceId = "device-a")

            assertEquals(1, snapshot.accountCount)
            assertTrue(snapshot.changes.any { it.entityType == EntityType.ACCOUNT && it.entityId == "acct-1" })
        } finally {
            driver.close()
        }
    }

    @Test
    fun applyIncomingChangeThroughSyncEnabledStoreDoesNotRebroadcast() = runBlockingTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ShillingDatabase.Schema.create(driver).await()
        val db = ShillingDatabase(driver)

        val peerSync = FakePeerSyncManager()
        val accountStore: AccountStore = createAccountStore(
            db = db,
            sync = StoreSyncDeps(db, peerSync, "device-a", FixedIdGenerator())
        )
        val facade = SyncStoreFacade(
            db = db,
            accountStore = accountStore
        )

        try {
            val result = facade.applyIncomingChange(
                ChangeMessage(
                    id = "remote-1",
                    entityType = EntityType.ACCOUNT,
                    op = ChangeOp.UPSERT,
                    entityId = "acct-1",
                    timestamp = 100L,
                    deviceId = "peer-b",
                    payload = ChangePayload.AccountPayload(
                        Account(id = "acct-1", name = "Checking", balance = 42.0)
                    )
                )
            )

            assertEquals(ApplyIncomingChangeResult.Applied, result)
            assertEquals(
                0,
                peerSync.changeBroadcastCount(),
                "Incoming apply must suppress Store5 updater rebroadcast"
            )
        } finally {
            driver.close()
        }
    }
}

private suspend fun waitUntil(
    timeoutMs: Long = 3_000L,
    intervalMs: Long = 25L,
    condition: () -> Boolean
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(intervalMs)
    }
    assertTrue(condition(), "Condition was not met within $timeoutMs ms")
}

private fun runBlockingTest(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
