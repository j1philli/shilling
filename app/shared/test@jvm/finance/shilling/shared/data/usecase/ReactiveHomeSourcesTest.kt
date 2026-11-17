package finance.shilling.shared.data.usecase

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ReactiveHomeSourcesTest {
    @Test
    fun postingWritesSkipScheduleReadsWhileSchedulesExceptionsMetadataAndReceiptCascadesStayLive(): Unit = runBlocking {
        val sqlite = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val scheduleReads = AtomicInteger()
        val exceptionReads = AtomicInteger()
        val driver = object : SqlDriver by sqlite {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                if (sql.contains("FROM schedules", ignoreCase = true)) scheduleReads.incrementAndGet()
                if (sql.contains("FROM schedule_exceptions", ignoreCase = true)) exceptionReads.incrementAndGet()
                return sqlite.executeQuery(identifier, sql, mapper, parameters, binders)
            }
        }
        val jobs = mutableListOf<Job>()
        try {
            ShillingDatabase.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
            val db = ShillingDatabase(driver)
            val changes = ChangeNotifier()
            val accountStore = createAccountStore(db)
            val categoryStore = createCategoryStore(db)
            val scheduleStore = createScheduleStore(db)
            val accounts = AccountRepository(changes, accountStore)
            val categories = CategoryRepository(changes, categoryStore)
            val schedules = ScheduleRepository(changes, scheduleStore, createScheduleExceptionStore(db))
            val postings = PostingRepository(object : IdGenerator { override fun newId() = "unused" },
                changes, createPostingStore(db), accountStore, categoryStore, scheduleStore)
            val receipts = ReceiptRepository(changes, createReceiptStore(db))
            val start = LocalDate(2026, 10, 1)
            val end = LocalDate(2026, 10, 8)
            accounts.upsert(Account("a", "Checking", 100.0))
            categories.upsert(Category("c", "Food", "#112233"))
            val schedule = Schedule("s", "Bill", 10.0, ScheduleType.EXPENSE, "a",
                categoryId = "c", startDate = start, freq = Frequency.ONCE)
            schedules.upsert(schedule)
            val window = Channel<List<ScheduledTxWithAccount>>(Channel.UNLIMITED)
            val budget = Channel<BudgetSummary>(Channel.UNLIMITED)
            val counts = Channel<ReceiptCounts>(Channel.UNLIMITED)
            jobs += launch { ComputeWindowUseCase(accounts, categories, schedules, postings)
                .watchWindow(start, end).collect { window.send(it) } }
            jobs += launch { ComputeBudgetUseCase(categories, schedules)
                .watchBudget(start).collect { budget.send(it) } }
            jobs += launch { receipts.watchCounts().collect { counts.send(it) } }
            suspend fun <T> Channel<T>.until(predicate: (T) -> Boolean): T = withTimeout(5_000) {
                var value = receive()
                while (!predicate(value)) value = receive()
                value
            }
            assertEquals(10.0, budget.until { it.lines.singleOrNull()?.totalAmount == 10.0 }.lines.single().totalAmount)
            assertEquals("Food", window.until { it.singleOrNull()?.category?.name == "Food" }.single().category?.name)
            assertEquals(ReceiptCounts(0, 0), counts.until { it.total == 0 })
            scheduleReads.set(0)
            exceptionReads.set(0)
            val posting = Posting("p", null, ScheduleType.EXPENSE, "a", LocalDate(2026, 10, 2),
                5.0, title = "Lunch", categoryId = "c")
            postings.record(posting)
            window.until { entries -> entries.any { it.postingId == "p" } }
            assertEquals(0, scheduleReads.get(), "Posting updates must reuse the schedule snapshot")
            assertEquals(0, exceptionReads.get(), "Posting updates must reuse the exception snapshot")

            schedules.upsert(schedule.copy(amount = 25.0))
            assertEquals(25.0, budget.until { it.lines.singleOrNull()?.totalAmount == 25.0 }.lines.single().totalAmount)
            window.until { entries -> entries.any { !it.posted && it.tx.amount == 25.0 } }
            categories.upsert(Category("c", "Groceries", "#112233"))
            budget.until { it.lines.singleOrNull()?.category?.name == "Groceries" }
            window.until { entries -> entries.any { it.category?.name == "Groceries" } }
            schedules.upsertException(ScheduleException("s", start, skip = true))
            assertTrue(budget.until { it.lines.isEmpty() }.lines.isEmpty())
            window.until { entries -> entries.none { it.tx.scheduleId == "s" } }

            receipts.save(Receipt("r", "p", "synthetic", "Synthetic.pdf", 1L))
            assertEquals(ReceiptCounts(1, 0), counts.until { it.total == 1 })
            postings.delete("p")
            assertEquals(ReceiptCounts(1, 1), counts.until { it.unattached == 1 })
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            driver.close()
        }
    }
}
