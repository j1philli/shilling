package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditorLookupTest {
    @Test
    fun scheduleDefaultsUseTheSelectedStartDate() {
        val start = kotlinx.datetime.LocalDate(2024, 2, 29)
        val fields = ScheduleFields(startEpochDay = start.toEpochDays())
        assertEquals("29", fields.monthDayText)
        assertEquals(5, fields.nth)
        assertEquals(start.dayOfWeek.ordinal, fields.nthWeekdayIndex)
    }

    @Test
    fun targetedBackgroundReadsPreserveFieldsTransferEditingAndRemoteDeletion(): Unit = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "editor-test-ui") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val queriesOnMain = AtomicInteger()
        val sqlite = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val driver = object : SqlDriver by sqlite {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                if (Thread.currentThread().name == "editor-test-ui") queriesOnMain.incrementAndGet()
                return sqlite.executeQuery(identifier, sql, mapper, parameters, binders)
            }
        }
        val analyticsPreferences = java.util.prefs.Preferences.userRoot().node("shilling/tests/${java.util.UUID.randomUUID()}")
        val analyticsClient = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("Analytics must remain opted out") })
        val analytics = finance.shilling.shared.data.analytics.ProductAnalytics(com.russhwolf.settings.PreferencesSettings(analyticsPreferences),
            object : IdGenerator { override fun newId() = "unused" }, analyticsClient)
        val owner = ViewModelStore()
        val models = mutableListOf<ViewModel>()
        fun <T : ViewModel> own(model: T): T = model.also { owner.put("${models.size}", it); models += it }
        val ids = object : IdGenerator { override fun newId() = "new-id" }
        try {
            ShillingDatabase.Schema.create(driver).await()
            val db = ShillingDatabase(driver)
            val changes = ChangeNotifier()
            val accountStore = createAccountStore(db)
            val categoryStore = createCategoryStore(db)
            val scheduleStore = createScheduleStore(db)
            val accounts = AccountRepository(changes, accountStore)
            val categories = CategoryRepository(changes, categoryStore)
            val schedules = ScheduleRepository(changes, scheduleStore, createScheduleExceptionStore(db))
            val postings = PostingRepository(ids, changes, createPostingStore(db), accountStore, categoryStore, scheduleStore)
            val receipts = ReceiptRepository(changes, createReceiptStore(db))
            accounts.upsert(Account("a", "Checking", 100.0))
            accounts.upsert(Account("b", "Savings", 250.0))
            categories.upsert(Category("c", "Essentials", "#123456"))
            schedules.upsert(Schedule("s", "Bill", 45.0, ScheduleType.EXPENSE, "b",
                categoryId = "c", startDate = today(), freq = Frequency.WEEKLY))
            val debit = Posting("move_dr", null, ScheduleType.EXPENSE, "a", today(), 25.0, "pair", "Moved", "c")
            postings.record(debit)
            postings.record(debit.copy(id = "move_cr", type = ScheduleType.INCOME, accountId = "b"))
            val category = withContext(main) { own(CategoryEditorViewModel("c", categories, ids)) }
            val account = withContext(main) { own(AccountEditorViewModel("b", accounts, ids, analytics)) }
            val schedule = withContext(main) { own(ScheduleEditorViewModel("s", null, schedules, accounts, categories, ids, analytics)) }
            val transaction = withContext(main) { own(TransactionEditorViewModel("move_cr", postings, accounts, categories,
                receipts, Store5ReceiptFileStore(SqlReceiptFileStorage(db), { _, _, _ -> }), ids, analytics)) }
            withTimeout(10_000) {
                assertEquals("Essentials", category.state.first { it.load == EditorLoad.READY }.name)
                assertEquals(formatAmountInput(250.0), account.state.first { it.load == EditorLoad.READY }.balanceText)
                val s = schedule.state.first { it.load == EditorLoad.READY }
                assertEquals("b", s.fields.accountId)
                assertEquals("Bill", s.fields.title)
                val t = transaction.state.first { it.load == EditorLoad.READY }
                assertEquals(ScheduleType.TRANSFER, t.fields.type)
                assertEquals("a", t.fields.accountId)
                assertEquals("b", t.fields.toAccountId)
            }
            assertEquals(0, queriesOnMain.get(), "Editor subscriptions must read off Main")
            withContext(main) { transaction.setTitle(""); schedule.setTitle("") }
            assertNull(withContext(main) { transaction.save() })
            assertNull(withContext(main) { schedule.save() })
            withContext(main) {
                transaction.setTitle("Edited transfer")
                schedule.setTitle("Edited bill")
                category.setName("Unsaved category")
            }
            // Save can follow the last keystroke before the projected StateFlow emits.
            assertEquals("Transaction updated", withContext(main) { transaction.save() })
            assertEquals("Schedule updated", withContext(main) { schedule.save() })
            assertEquals("Edited transfer", postings.getById("move_dr")?.title)
            assertEquals("Edited transfer", postings.getById("move_cr")?.title)
            assertEquals("Edited bill", schedules.getAll().first { it.id == "s" }.title)
            categories.upsert(Category("unrelated", "Other"))
            assertEquals("Unsaved category", category.state.value.name)
            postings.delete("move_cr")
            schedules.delete("s")
            categories.delete("c")
            accounts.delete("b")
            withTimeout(10_000) {
                transaction.state.first { it.load == EditorLoad.MISSING }
                schedule.state.first { it.load == EditorLoad.MISSING }
                category.state.first { it.load == EditorLoad.MISSING }
                account.state.first { it.load == EditorLoad.MISSING }
            }
            assertNull(withContext(main) { category.save() })
            assertNull(withContext(main) { account.save() })
            assertNull(withContext(main) { schedule.save() })
            assertNull(withContext(main) { transaction.save() })
        } finally {
            withContext(main) { owner.clear() }
            models.forEach { it.viewModelScope.coroutineContext.job.join() }
            analyticsClient.close()
            analyticsPreferences.removeNode()
            driver.close()
            Dispatchers.resetMain()
            main.close()
        }
    }
}
