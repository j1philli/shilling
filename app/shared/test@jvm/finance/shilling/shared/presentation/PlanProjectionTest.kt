package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanProjectionTest {
    @Test
    fun groupingExpansionAndActionsStayConsistentAcrossBackgroundSnapshots() = fixture {
        val start = LocalDate(2026, 9, 2)
        schedules.upsert(Schedule("bill", "Bill", 10.0, ScheduleType.EXPENSE, "a",
            categoryId = "c", startDate = start, freq = Frequency.WEEKLY))
        schedules.upsert(Schedule("pay", "Pay", 20.0, ScheduleType.INCOME, "a",
            categoryId = "c", startDate = start, freq = Frequency.WEEKLY))
        val model = onMain {
            own(PlanOverviewViewModel(ComputeWindowUseCase(accounts, categories, schedules, postings),
                OccurrenceActions(postings, schedules))).also {
                it.setPeriod(PlanPeriod.MONTH)
                it.jumpTo(start.toEpochDays())
            }
        }
        suspend fun state(predicate: (PlanOverviewUiState) -> Boolean) =
            withTimeout(10_000) { model.state.first(predicate) }
        val dayState = state { it.days.sumOf { d -> d.rows.size } == 10 }
        assertEquals(formatCurrency(100.0), dayState.summary.income)
        assertEquals(formatCurrency(50.0), dayState.summary.expenses)
        assertTrue(dayState.categories.isEmpty())
        assertEquals(0, driver.mainQueries.get(), "Plan's reads must not execute on the UI dispatcher")

        onMain { model.setGrouping(PlanGrouping.BY_CATEGORY) }
        val collapsed = state { it.categories.size == 1 }
        assertEquals(dayState.summary, collapsed.summary)
        assertTrue(collapsed.days.isEmpty())
        assertEquals("2 items", collapsed.categories.single().countLabel)
        assertTrue(collapsed.categories.single().lines.isEmpty())
        onMain { model.toggleCategory("c") }
        val expanded = state { it.categories.singleOrNull()?.expanded == true }
        assertEquals(setOf("bill", "pay"), expanded.categories.single().lines.map { it.key }.toSet())
        assertTrue(expanded.categories.single().lines.all { it.supporting.startsWith("5 ×") })
        assertEquals(collapsed.categories.single().total, expanded.categories.single().total)

        onMain { model.setGrouping(PlanGrouping.BY_DAY) }
        val restored = state { it.days.isNotEmpty() }
        assertEquals(dayState.days, restored.days)
        val bill = restored.days.first().rows.first { it.title == "Bill" }
        assertNotNull(onMain { model.changeAmountPrompt(bill.key) })
        val amountUndo = assertNotNull(onMain { model.changeAmount(bill.key, "25") })
        state { it.summary.expenses == formatCurrency(65.0) }
        onMain { amountUndo.undo() }
        state { it.summary.expenses == formatCurrency(50.0) }
        val skipUndo = assertNotNull(onMain { model.skip(bill.key) })
        state { it.days.sumOf { d -> d.rows.size } == 9 }
        onMain { skipUndo.undo() }
        state { it.days.sumOf { d -> d.rows.size } == 10 }
        assertNotNull(onMain { model.markPosted(bill.key) })
        val posted = state { it.days.any { d -> d.rows.any { r -> r.posted } } }
            .days.flatMap { it.rows }.single { it.posted }
        assertTrue(posted.canUnmark)
        assertNotNull(onMain { model.unmark(posted.key) })
        state { it.days.sumOf { d -> d.rows.size } == 10 && it.days.none { d -> d.rows.any { r -> r.posted } } }
        onMain { model.setGrouping(PlanGrouping.BY_CATEGORY) }
        val reopened = state { it.categories.singleOrNull()?.expanded == true }
        assertEquals(expanded.categories, reopened.categories)
    }

    @Test
    fun listProjectionsReadOffMainAndPreserveJoinsFiltersAndLiveUpdates() = fixture {
        schedules.upsert(Schedule("transfer", "Move savings", 30.0, ScheduleType.TRANSFER, "a",
            counterAccountId = "b", categoryId = "c", startDate = today(), freq = Frequency.WEEKLY))
        schedules.upsert(Schedule("missing", "Missing references", 5.0, ScheduleType.EXPENSE, "missing",
            startDate = today(), freq = Frequency.WEEKLY))
        val scheduleModel = onMain { own(SchedulesViewModel(schedules, accounts, categories)) }
        val accountModel = onMain { own(AccountsViewModel(accounts)) }
        val categoryModel = onMain { own(CategoriesViewModel(categories)) }
        val rows = withTimeout(10_000) { scheduleModel.state.first { it.groups.sumOf { g -> g.rows.size } == 2 } }
            .groups.flatMap { it.rows }
        assertTrue(rows.single { it.id == "transfer" }.supporting.contains("Checking → Savings · Essentials"))
        assertTrue(rows.single { it.id == "missing" }.supporting.contains("No account"))
        withTimeout(10_000) { accountModel.state.first { it.rows.size == 2 } }
        withTimeout(10_000) { categoryModel.state.first { it.rows.size == 1 } }
        assertEquals(0, driver.mainQueries.get())
        onMain { scheduleModel.setFilter(ScheduleType.TRANSFER) }
        val filtered = withTimeout(10_000) { scheduleModel.state.first { it.filter == ScheduleType.TRANSFER } }
        assertEquals(listOf("transfer"), filtered.groups.flatMap { it.rows }.map { it.id })
        assertNull(filtered.groups.single().header)
        accounts.upsert(Account("b", "Renamed savings", 200.0))
        categories.upsert(Category("c", "Renamed category"))
        withTimeout(10_000) { scheduleModel.state.first { it.groups.singleOrNull()?.rows?.singleOrNull()?.supporting
            ?.contains("Checking → Renamed savings · Renamed category") == true } }
    }

    private fun fixture(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "plan-test-ui") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val fixture = Fixture(main)
        try {
            ShillingDatabase.Schema.create(fixture.driver).await()
            fixture.accounts.upsert(Account("a", "Checking", 100.0))
            fixture.accounts.upsert(Account("b", "Savings", 200.0))
            fixture.categories.upsert(Category("c", "Essentials"))
            fixture.block()
        } finally {
            fixture.onMain { fixture.owner.clear() }
            fixture.models.forEach { it.viewModelScope.coroutineContext.job.join() }
            fixture.driver.close()
            Dispatchers.resetMain()
            main.close()
        }
    }

    private class Fixture(val main: CoroutineDispatcher) {
        val driver = ThreadCheckingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        private val db = ShillingDatabase(driver)
        val changes = ChangeNotifier()
        private val accountStore = createAccountStore(db)
        private val categoryStore = createCategoryStore(db)
        private val scheduleStore = createScheduleStore(db)
        val accounts = AccountRepository(changes, accountStore)
        val categories = CategoryRepository(changes, categoryStore)
        val schedules = ScheduleRepository(changes, scheduleStore, createScheduleExceptionStore(db))
        val postings = PostingRepository(object : IdGenerator { override fun newId() = UUID.randomUUID().toString() },
            changes, createPostingStore(db), accountStore, categoryStore, scheduleStore)
        val owner = ViewModelStore()
        val models = mutableListOf<ViewModel>()
        fun <T : ViewModel> own(model: T): T = model.also { owner.put("${models.size}", it); models += it }
        suspend fun <T> onMain(block: suspend () -> T): T = withContext(main) { block() }
    }

    private class ThreadCheckingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        val mainQueries = AtomicInteger()
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
            if (Thread.currentThread().name == "plan-test-ui") mainQueries.incrementAndGet()
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }
}
