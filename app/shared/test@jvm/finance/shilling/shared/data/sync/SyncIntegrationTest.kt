package finance.shilling.shared.data.sync

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import finance.shilling.shared.data.store.ScheduleRepository
import finance.shilling.shared.data.store.SyncStoreFacade
import finance.shilling.shared.data.store.StoreSyncDeps
import finance.shilling.shared.data.store.createAccountStore
import finance.shilling.shared.data.store.createCategoryStore
import finance.shilling.shared.data.store.createPostingStore
import finance.shilling.shared.data.store.createReceiptStore
import finance.shilling.shared.data.store.createScheduleExceptionStore
import finance.shilling.shared.data.store.createScheduleStore
import finance.shilling.shared.data.store.toDomain
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncIntegrationTest {
    @Test
    fun allEntityTypesSyncAcrossPeers() = runBlocking {
        val sender = DeviceHarness("sender")
        val receiver = DeviceHarness("receiver")
        try {
            sender.start()
            receiver.start()
            sender.linkTo(receiver)
            receiver.linkTo(sender)

            val account = Account(id = "acct-1", name = "Checking", balance = 1250.0)
            val category = Category(id = "cat-1", name = "Groceries", color = "#00AA55")
            val schedule = Schedule(
                id = "sched-1",
                title = "Weekly groceries",
                amount = 85.25,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                categoryId = category.id,
                startDate = LocalDate(2026, 3, 8),
                freq = Frequency.WEEKLY,
                interval = 1,
                notes = "Sunday run"
            )
            val exception = ScheduleException(
                scheduleId = schedule.id,
                date = LocalDate(2026, 3, 15),
                overrideAmount = 91.0
            )
            val posting = Posting(
                id = "post-1",
                scheduleId = schedule.id,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                date = LocalDate(2026, 3, 8),
                amount = 85.25,
                title = "Weekly groceries",
                categoryId = category.id
            )
            val receipt = Receipt(
                id = "receipt-1",
                postingId = posting.id,
                filePath = "receipts/receipt-1.jpg",
                originalName = "receipt-1.jpg",
                addedAt = 111L,
                receiptDate = 222L,
                amount = 85.25,
                notes = "initial"
            )
            val receiptBytes = ByteArray(12_000) { (it % 251).toByte() }

            sender.accountRepository.upsert(account)
            sender.categoryRepository.upsert(category)
            sender.scheduleRepository.upsert(schedule)
            sender.scheduleRepository.upsertException(exception)
            sender.postingRepository.record(posting)
            sender.storeFile(receipt.id, receipt.originalName, receiptBytes)
            sender.receiptRepository.save(receipt)

            waitUntil {
                receiver.db.accountQueries.selectById(account.id).awaitAsOneOrNull() != null &&
                    receiver.db.categoryQueries.selectAll().awaitAsList().any { it.id == category.id } &&
                    receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull() != null &&
                    receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id)
                        .awaitAsList()
                        .any { it.schedule_id == schedule.id } &&
                    receiver.findPosting(posting.id) != null &&
                    receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull() != null &&
                    receiver.hasFile(receipt.id)
            }

            assertEquals(account, receiver.db.accountQueries.selectById(account.id).awaitAsOneOrNull()!!.toDomain())
            assertEquals(category, receiver.db.categoryQueries.selectAll().awaitAsList().single().toDomain())
            assertEquals(schedule, receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull()!!.toDomain())
            assertEquals(
                exception,
                receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id).awaitAsList().single().toDomain()
            )
            assertEquals(posting, receiver.findPosting(posting.id)!!.toDomain())
            assertEquals(receipt, receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull()!!.toDomain())
            assertContentEquals(receiptBytes, receiver.readFile(receipt.id))
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun receiptAttachAndMetadataUpdateSyncAcrossPeers() = runBlocking {
        val sender = DeviceHarness("sender")
        val receiver = DeviceHarness("receiver")
        try {
            sender.start()
            receiver.start()
            sender.linkTo(receiver)
            receiver.linkTo(sender)

            val account = Account(id = "acct-1", name = "Checking", balance = 10.0)
            val posting = Posting(
                id = "post-1",
                scheduleId = null,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                date = LocalDate(2026, 3, 9),
                amount = 14.5,
                title = "Coffee"
            )
            val receipt = Receipt(
                id = "receipt-1",
                postingId = null,
                filePath = "receipts/receipt-1.jpg",
                originalName = "receipt-1.jpg",
                addedAt = 100L
            )
            val receiptBytes = ByteArray(4_096) { (it % 127).toByte() }

            sender.accountRepository.upsert(account)
            sender.postingRepository.record(posting)
            sender.storeFile(receipt.id, receipt.originalName, receiptBytes)
            sender.receiptRepository.save(receipt)

            waitUntil {
                receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull() != null &&
                    receiver.hasFile(receipt.id)
            }

            sender.receiptRepository.attach(receipt.id, posting.id)
            sender.receiptRepository.updateMetadata(
                receiptId = receipt.id,
                notes = "latte + croissant",
                receiptDate = 333L,
                amount = 14.5
            )

            waitUntil {
                receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull()?.let { row ->
                    row.posting_id == posting.id &&
                        row.notes == "latte + croissant" &&
                        row.receipt_date == 333L &&
                        row.amount == 14.5
                } == true
            }

            val synced = receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull()!!.toDomain()
            assertEquals(posting.id, synced.postingId)
            assertEquals("latte + croissant", synced.notes)
            assertEquals(333L, synced.receiptDate)
            assertEquals(14.5, synced.amount)
            assertContentEquals(receiptBytes, receiver.readFile(receipt.id))
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun receiptDetachDeleteAndExceptionRemovalSyncAcrossPeers() = runBlocking {
        val sender = DeviceHarness("sender")
        val receiver = DeviceHarness("receiver")
        try {
            sender.start()
            receiver.start()
            sender.linkTo(receiver)
            receiver.linkTo(sender)

            val account = Account(id = "acct-1", name = "Checking", balance = 100.0)
            val schedule = Schedule(
                id = "sched-1",
                title = "Utilities",
                amount = 120.0,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                startDate = LocalDate(2026, 3, 1),
                freq = Frequency.MONTHLY_BY_DAY,
                byMonthDay = 1
            )
            val exception = ScheduleException(
                scheduleId = schedule.id,
                date = LocalDate(2026, 4, 1),
                overrideAmount = 130.0
            )
            val posting = Posting(
                id = "post-1",
                scheduleId = null,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                date = LocalDate(2026, 3, 9),
                amount = 19.99,
                title = "Utilities bill"
            )
            val receipt = Receipt(
                id = "receipt-1",
                postingId = posting.id,
                filePath = "receipts/receipt-1.jpg",
                originalName = "receipt-1.jpg",
                addedAt = 100L
            )
            val receiptBytes = ByteArray(2_048) { (it % 89).toByte() }

            sender.accountRepository.upsert(account)
            sender.scheduleRepository.upsert(schedule)
            sender.scheduleRepository.upsertException(exception)
            sender.postingRepository.record(posting)
            sender.storeFile(receipt.id, receipt.originalName, receiptBytes)
            sender.receiptRepository.save(receipt)

            waitUntil {
                receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id).awaitAsList().isNotEmpty() &&
                    receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull() != null &&
                    receiver.hasFile(receipt.id)
            }

            sender.scheduleRepository.removeException(schedule.id, exception.date)
            sender.receiptRepository.detach(receipt.id)

            waitUntil {
                receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id).awaitAsList().isEmpty() &&
                    receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull()?.posting_id == null
            }

            sender.receiptRepository.delete(receipt.id)

            waitUntil {
                receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull() == null &&
                    !receiver.hasFile(receipt.id)
            }

            assertTrue(receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id).awaitAsList().isEmpty())
            assertNull(receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull())
            assertTrue(!receiver.hasFile(receipt.id))
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun postingAdHocAndBulkImportSyncAcrossPeers() = runBlocking {
        val sender = DeviceHarness("sender")
        val receiver = DeviceHarness("receiver")
        try {
            sender.start()
            receiver.start()
            sender.linkTo(receiver)
            receiver.linkTo(sender)

            val account = Account(id = "acct-1", name = "Checking", balance = 100.0)
            sender.accountRepository.upsert(account)

            sender.postingRepository.recordAdHoc(
                title = "Coffee",
                amount = 6.5,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                categoryId = null,
                date = LocalDate(2026, 3, 9)
            )
            sender.postingRepository.bulkImport(
                items = listOf(
                    Triple("Paycheck", 2000.0, LocalDate(2026, 3, 10)),
                    Triple("Lunch", -14.25, LocalDate(2026, 3, 11))
                ),
                accountId = account.id,
                categoryId = null
            )

            waitUntil {
                receiver.db.postingQueries.selectAll().awaitAsList().size == 3
            }

            val postings = receiver.db.postingQueries.selectAll().awaitAsList().map { it.toDomain() }
            assertTrue(postings.any {
                it.title == "Coffee" &&
                    it.amount == 6.5 &&
                    it.type == ScheduleType.EXPENSE
            })
            assertTrue(postings.any {
                it.title == "Paycheck" &&
                    it.amount == 2000.0 &&
                    it.type == ScheduleType.INCOME
            })
            assertTrue(postings.any {
                it.title == "Lunch" &&
                    it.amount == 14.25 &&
                    it.type == ScheduleType.EXPENSE
            })
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun accountAndCategoryDeleteSyncAcrossPeers() = runBlocking {
        val sender = DeviceHarness("sender")
        val receiver = DeviceHarness("receiver")
        try {
            sender.start()
            receiver.start()
            sender.linkTo(receiver)
            receiver.linkTo(sender)

            val account = Account(id = "acct-1", name = "Checking", balance = 100.0)
            val category = Category(id = "cat-1", name = "Bills", color = "#445566")
            val schedule = Schedule(
                id = "sched-1",
                title = "Bills",
                amount = 75.0,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                categoryId = category.id,
                startDate = LocalDate(2026, 3, 1),
                freq = Frequency.MONTHLY_BY_DAY,
                byMonthDay = 1
            )
            val posting = Posting(
                id = "post-1",
                scheduleId = null,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                date = LocalDate(2026, 3, 9),
                amount = 75.0,
                title = "Bills",
                categoryId = category.id
            )

            sender.accountRepository.upsert(account)
            sender.categoryRepository.upsert(category)
            sender.scheduleRepository.upsert(schedule)
            sender.postingRepository.record(posting)

            waitUntil {
                receiver.db.accountQueries.selectById(account.id).awaitAsOneOrNull() != null &&
                    receiver.db.categoryQueries.selectAll().awaitAsList().any { it.id == category.id } &&
                    receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull() != null &&
                    receiver.findPosting(posting.id) != null
            }

            sender.categoryRepository.delete(category.id)
            sender.accountRepository.delete(account.id)

            waitUntil {
                receiver.db.accountQueries.selectById(account.id).awaitAsOneOrNull() == null &&
                    receiver.db.categoryQueries.selectAll().awaitAsList().none { it.id == category.id } &&
                    receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull()?.let { row ->
                        row.account_id == null && row.category_id == null
                    } == true &&
                    receiver.findPosting(posting.id) == null
            }

            val syncedSchedule = receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull()!!.toDomain()
            assertNull(receiver.db.accountQueries.selectById(account.id).awaitAsOneOrNull())
            assertTrue(receiver.db.categoryQueries.selectAll().awaitAsList().none { it.id == category.id })
            assertNull(receiver.findPosting(posting.id))
            assertEquals("", syncedSchedule.accountId)
            assertNull(syncedSchedule.categoryId)
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun scheduleDeleteRemovesDependentRowsAcrossPeers() = runBlocking {
        val sender = DeviceHarness("sender")
        val receiver = DeviceHarness("receiver")
        try {
            sender.start()
            receiver.start()
            sender.linkTo(receiver)
            receiver.linkTo(sender)

            val account = Account(id = "acct-1", name = "Checking", balance = 100.0)
            val schedule = Schedule(
                id = "sched-1",
                title = "Gym",
                amount = 49.99,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                startDate = LocalDate(2026, 3, 1),
                freq = Frequency.MONTHLY_BY_DAY,
                byMonthDay = 1
            )
            val exception = ScheduleException(
                scheduleId = schedule.id,
                date = LocalDate(2026, 4, 1),
                skip = true
            )
            val posting = Posting(
                id = "post-1",
                scheduleId = schedule.id,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                date = LocalDate(2026, 3, 1),
                amount = 49.99
            )

            sender.accountRepository.upsert(account)
            sender.scheduleRepository.upsert(schedule)
            sender.scheduleRepository.upsertException(exception)
            sender.postingRepository.record(posting)

            waitUntil(timeoutMs = 10_000L) {
                receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull() != null &&
                    receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id).awaitAsList().isNotEmpty() &&
                    receiver.findPosting(posting.id) != null
            }

            sender.scheduleRepository.delete(schedule.id)

            waitUntil(timeoutMs = 10_000L) {
                receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull() == null &&
                    receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id).awaitAsList().isEmpty() &&
                    receiver.findPosting(posting.id) == null
            }

            assertNull(receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull())
            assertTrue(receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id).awaitAsList().isEmpty())
            assertNull(receiver.findPosting(posting.id))
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun peerConnectedBackfillsFullStateAndReceiptFileBytes() = runBlocking {
        val sender = DeviceHarness("sender")
        val receiver = DeviceHarness("receiver")
        try {
            val account = Account(id = "acct-1", name = "Savings", balance = 500.0)
            val category = Category(id = "cat-1", name = "Travel", color = "#1144FF")
            val schedule = Schedule(
                id = "sched-1",
                title = "Flight fund",
                amount = 200.0,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                categoryId = category.id,
                startDate = LocalDate(2026, 3, 1),
                freq = Frequency.MONTHLY_BY_DAY,
                byMonthDay = 1
            )
            val exception = ScheduleException(
                scheduleId = schedule.id,
                date = LocalDate(2026, 4, 1),
                overrideAmount = 250.0
            )
            val posting = Posting(
                id = "post-1",
                scheduleId = schedule.id,
                type = ScheduleType.EXPENSE,
                accountId = account.id,
                date = LocalDate(2026, 3, 1),
                amount = 200.0,
                categoryId = category.id
            )
            val receipt = Receipt(
                id = "receipt-1",
                postingId = posting.id,
                filePath = "receipts/receipt-1.jpg",
                originalName = "receipt-1.jpg",
                addedAt = 444L
            )
            val receiptBytes = ByteArray(8_192) { (it % 193).toByte() }

            sender.db.accountQueries.upsert(account.id, account.name, account.balance)
            sender.db.categoryQueries.upsert(category.id, category.name, category.color)
            sender.db.scheduleQueries.upsert(
                id = schedule.id,
                title = schedule.title,
                amount = schedule.amount,
                type = schedule.type.name,
                account_id = schedule.accountId,
                counter_account_id = schedule.counterAccountId,
                category_id = schedule.categoryId,
                start_date = schedule.startDate.toEpochDays().toLong(),
                end_date = schedule.endDate?.toEpochDays()?.toLong(),
                freq = schedule.freq.name,
                interval_ = schedule.interval.toLong(),
                by_day_mask = schedule.byDayMask?.toLong(),
                by_month_day = schedule.byMonthDay?.toLong(),
                nth_weekday = schedule.nthWeekday?.toLong(),
                last_day_flag = if (schedule.lastDayFlag) 1L else 0L,
                auto_pay = if (schedule.autoPay) 1L else 0L,
                notes = schedule.notes
            )
            sender.db.scheduleExceptionQueries.upsert(
                schedule_id = exception.scheduleId,
                date = exception.date.toEpochDays().toLong(),
                skip = if (exception.skip) 1L else 0L,
                override_amount = exception.overrideAmount,
                override_account_id = exception.overrideAccountId,
                override_counter_account_id = exception.overrideCounterAccountId
            )
            sender.db.postingQueries.upsert(
                id = posting.id,
                schedule_id = posting.scheduleId,
                type = posting.type.name,
                account_id = posting.accountId,
                date = posting.date.toEpochDays().toLong(),
                amount = posting.amount,
                pair_id = posting.pairId,
                title = posting.title,
                category_id = posting.categoryId
            )
            sender.db.receiptQueries.upsert(
                id = receipt.id,
                posting_id = receipt.postingId,
                file_path = receipt.filePath,
                original_name = receipt.originalName,
                added_at = receipt.addedAt,
                receipt_date = receipt.receiptDate,
                amount = receipt.amount,
                notes = receipt.notes
            )
            sender.storeFile(receipt.id, receipt.originalName, receiptBytes)

            sender.start()
            receiver.start()
            sender.linkTo(receiver)
            receiver.linkTo(sender)

            delay(100)
            sender.emitPeerConnected(receiver.deviceId)

            waitUntil(timeoutMs = 10_000L) {
                receiver.db.accountQueries.selectById(account.id).awaitAsOneOrNull() != null &&
                    receiver.db.categoryQueries.selectAll().awaitAsList().any { it.id == category.id } &&
                    receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull() != null &&
                    receiver.db.scheduleExceptionQueries.selectByScheduleId(schedule.id).awaitAsList().isNotEmpty() &&
                    receiver.findPosting(posting.id) != null &&
                    receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull() != null &&
                    receiver.hasFile(receipt.id)
            }

            assertEquals(account, receiver.db.accountQueries.selectById(account.id).awaitAsOneOrNull()!!.toDomain())
            assertEquals(category, receiver.db.categoryQueries.selectAll().awaitAsList().single().toDomain())
            assertEquals(schedule, receiver.db.scheduleQueries.selectById(schedule.id).awaitAsOneOrNull()!!.toDomain())
            assertEquals(posting, receiver.findPosting(posting.id)!!.toDomain())
            assertEquals(receipt, receiver.db.receiptQueries.selectById(receipt.id).awaitAsOneOrNull()!!.toDomain())
            assertContentEquals(receiptBytes, receiver.readFile(receipt.id))
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun reconnectBackfillDoesNotOverwriteNewerConflictingEntity() = runBlocking {
        val web = DeviceHarness("web")
        val desktop = DeviceHarness("desktop")
        try {
            web.start()
            desktop.start()
            web.linkTo(desktop)
            desktop.linkTo(web)

            val initial = Account(id = "acct-1", name = "Checking", balance = 100.0)
            desktop.accountRepository.upsert(initial)

            waitUntil {
                web.db.accountQueries.selectById(initial.id).awaitAsOneOrNull()?.balance == 100.0
            }

            web.unlinkFrom(desktop)
            desktop.unlinkFrom(web)

            web.accountRepository.upsert(initial.copy(balance = 200.0))
            delay(5)
            desktop.accountRepository.upsert(initial.copy(balance = 300.0))

            web.pauseTargetedChangesTo(desktop.deviceId)
            web.linkTo(desktop)
            desktop.linkTo(web)

            web.emitPeerConnected(desktop.deviceId)
            waitUntil {
                web.queuedTargetedChangeCount(desktop.deviceId) == 1
            }

            desktop.emitPeerConnected(web.deviceId)
            waitUntil {
                web.db.accountQueries.selectById(initial.id).awaitAsOneOrNull()?.balance == 300.0
            }

            web.resumeTargetedChangesTo(desktop.deviceId)

            waitUntil {
                desktop.db.accountQueries.selectById(initial.id).awaitAsOneOrNull()?.balance == 300.0
            }

            assertEquals(300.0, web.db.accountQueries.selectById(initial.id).awaitAsOneOrNull()!!.balance)
            assertEquals(300.0, desktop.db.accountQueries.selectById(initial.id).awaitAsOneOrNull()!!.balance)
        } finally {
            web.close()
            desktop.close()
        }
    }

    private class DeviceHarness(val deviceId: String) {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: ShillingDatabase
        private val fileStore = InMemoryFileStore()
        private val peerSync = LinkedPeerSyncManager(deviceId)
        private val idGenerator = TestIdGenerator(deviceId)
        private val notifier = ChangeNotifier()
        private val syncDeps by lazy { StoreSyncDeps(db, peerSync, deviceId, idGenerator) }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val accountStore by lazy { createAccountStore(db, syncDeps) }
        private val categoryStore by lazy { createCategoryStore(db, syncDeps) }
        private val scheduleStore by lazy { createScheduleStore(db, syncDeps) }
        private val postingStore by lazy { createPostingStore(db, syncDeps) }
        private val receiptStore by lazy { createReceiptStore(db, syncDeps) }
        private val scheduleExceptionStore by lazy { createScheduleExceptionStore(db, syncDeps) }

        val accountRepository: AccountRepository
        val categoryRepository: CategoryRepository
        val scheduleRepository: ScheduleRepository
        val postingRepository: PostingRepository
        val receiptRepository: ReceiptRepository

        init {
            runBlocking {
                ShillingDatabase.Schema.create(driver).await()
            }
            db = ShillingDatabase(driver)
            accountRepository = AccountRepository(
                notifier = notifier,
                store = accountStore,
                sync = syncDeps
            )
            categoryRepository = CategoryRepository(
                notifier = notifier,
                store = categoryStore,
                sync = syncDeps
            )
            scheduleRepository = ScheduleRepository(
                notifier = notifier,
                store = scheduleStore,
                exceptionStore = scheduleExceptionStore,
                sync = syncDeps
            )
            postingRepository = PostingRepository(
                idGenerator = idGenerator,
                notifier = notifier,
                store = postingStore,
                accountStore = accountStore,
                categoryStore = categoryStore,
                scheduleStore = scheduleStore,
                sync = syncDeps
            )
            receiptRepository = ReceiptRepository(
                notifier = notifier,
                store = receiptStore,
                postingStore = postingStore,
                scheduleStore = scheduleStore,
                sync = syncDeps
            )
        }

        fun start() {
            peerSync.start(scope)
            IncomingChangeRouter(
                storeFacade = SyncStoreFacade(db),
                notifier = notifier,
                webRtcManager = peerSync,
                fileTransferManager = FileTransferManager(fileStore),
                fileStore = fileStore,
                startupScanDelayMs = 60_000L,
                fileRetryIntervalMs = 60_000L,
                deviceId = deviceId,
                idGenerator = idGenerator
            ).start(scope)
        }

        fun linkTo(other: DeviceHarness) {
            peerSync.link(other.peerSync)
        }

        fun unlinkFrom(other: DeviceHarness) {
            peerSync.unlink(other.deviceId)
        }

        suspend fun emitPeerConnected(peerId: String) {
            peerSync.emitPeerConnected(peerId)
        }

        fun pauseTargetedChangesTo(peerId: String) {
            peerSync.pauseTargetedChangesTo(peerId)
        }

        suspend fun resumeTargetedChangesTo(peerId: String) {
            peerSync.resumeTargetedChangesTo(peerId)
        }

        fun queuedTargetedChangeCount(peerId: String): Int =
            peerSync.queuedTargetedChangeCount(peerId)

        suspend fun storeFile(receiptId: String, fileName: String, bytes: ByteArray) {
            fileStore.store(receiptId, fileName, bytes)
        }

        suspend fun hasFile(receiptId: String): Boolean = fileStore.hasFile(receiptId)

        suspend fun readFile(receiptId: String): ByteArray? = fileStore.read(receiptId)

        suspend fun findPosting(postingId: String) =
            db.postingQueries.selectAll().awaitAsList().firstOrNull { it.id == postingId }

        fun close() {
            scope.cancel()
            driver.close()
        }
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

    private class TestIdGenerator(private val prefix: String) : IdGenerator {
        private var next = 0

        override fun newId(): String {
            next += 1
            return "$prefix-id-$next"
        }
    }

    private class LinkedPeerSyncManager(private val deviceId: String) : PeerSyncManager {
        private val remotes = linkedMapOf<String, LinkedPeerSyncManager>()
        private val pausedTargetedPeers = mutableSetOf<String>()
        private val queuedTargetedChanges = linkedMapOf<String, MutableList<ChangeMessage>>()
        private val _incomingChanges = MutableSharedFlow<ChangeMessage>(extraBufferCapacity = 128)
        private val _incomingFileMessages = MutableSharedFlow<Pair<String, FileTransferMessage>>(extraBufferCapacity = 128)
        private val _peerConnected = MutableSharedFlow<String>(extraBufferCapacity = 32)

        override val incomingChanges: SharedFlow<ChangeMessage> = _incomingChanges
        override val incomingFileMessages: SharedFlow<Pair<String, FileTransferMessage>> = _incomingFileMessages
        override val peerConnected: SharedFlow<String> = _peerConnected

        fun link(remote: LinkedPeerSyncManager) {
            remotes[remote.deviceId] = remote
        }

        fun unlink(remoteDeviceId: String) {
            remotes.remove(remoteDeviceId)
        }

        fun pauseTargetedChangesTo(peerId: String) {
            pausedTargetedPeers.add(peerId)
        }

        suspend fun resumeTargetedChangesTo(peerId: String) {
            pausedTargetedPeers.remove(peerId)
            val queued = queuedTargetedChanges.remove(peerId).orEmpty()
            queued.forEach { change ->
                remotes[peerId]?._incomingChanges?.emit(change)
            }
        }

        fun queuedTargetedChangeCount(peerId: String): Int =
            queuedTargetedChanges[peerId]?.size ?: 0

        suspend fun emitPeerConnected(peerId: String) {
            _peerConnected.emit(peerId)
        }

        override fun start(scope: CoroutineScope) {}

        override suspend fun broadcast(change: ChangeMessage) {
            remotes.values.forEach { it._incomingChanges.emit(change) }
        }

        override suspend fun sendToPeer(peerId: String, change: ChangeMessage) {
            if (peerId in pausedTargetedPeers) {
                queuedTargetedChanges.getOrPut(peerId) { mutableListOf() }.add(change)
                return
            }
            remotes[peerId]?._incomingChanges?.emit(change)
        }

        override suspend fun sendFileMessage(peerId: String, message: FileTransferMessage) {
            remotes[peerId]?._incomingFileMessages?.emit(deviceId to message)
        }

        override suspend fun broadcastFileMessage(message: FileTransferMessage) {
            remotes.values.forEach { it._incomingFileMessages.emit(deviceId to message) }
        }

        override fun stop() {}
    }
}

private suspend fun waitUntil(
    timeoutMs: Long = 5_000L,
    intervalMs: Long = 25L,
    condition: suspend () -> Boolean
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(intervalMs)
    }
    assertTrue(condition(), "Condition was not met within $timeoutMs ms")
}
