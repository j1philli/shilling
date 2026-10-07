package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    @Test
    fun backgroundReviewAndImportPreserveDuplicatesSelectionsAndCategories(): Unit = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "synthetic-ui") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val driver = DelayedCandidateDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val analyticsPreferences = java.util.prefs.Preferences.userRoot().node("shilling/tests/${java.util.UUID.randomUUID()}")
        val analyticsClient = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("Analytics must remain opted out") })
        val analytics = finance.shilling.shared.data.analytics.ProductAnalytics(com.russhwolf.settings.PreferencesSettings(analyticsPreferences),
            object : IdGenerator { override fun newId() = "unused" }, analyticsClient)
        val owner = ViewModelStore()
        try {
            ShillingDatabase.Schema.create(driver).await()
            val db = ShillingDatabase(driver)
            val changes = ChangeNotifier()
            val accounts = createAccountStore(db)
            val categories = createCategoryStore(db)
            val schedules = createScheduleStore(db)
            val accountRepo = AccountRepository(changes, accounts)
            val categoryRepo = CategoryRepository(changes, categories)
            val postings = PostingRepository(object : IdGenerator { override fun newId() = UUID.randomUUID().toString() },
                changes, createPostingStore(db), accounts, categories, schedules)
            accountRepo.upsert(Account("a", "Checking", 100.0))
            accountRepo.upsert(Account("b", "Savings", 100.0))
            categoryRepo.upsert(Category("food", "Food"))
            val day = LocalDate(2026, 9, 29)
            postings.recordAdHoc("Already here", 12.34, ScheduleType.EXPENSE, "a", null, day)
            postings.recordAdHoc("Lunch", 9.50, ScheduleType.EXPENSE, "b", null, day)
            val model = withContext(main) {
                ImportViewModel(accountRepo, categoryRepo, postings, analytics).also { owner.put("import", it) }
            }
            suspend fun awaitState(predicate: (ImportUiState) -> Boolean) =
                withTimeout(10_000) { model.state.first(predicate) }
            awaitState { it.accountId == "a" }
            val hold = CompletableDeferred<Unit>()
            driver.hold.set(hold)
            val observed = java.util.concurrent.CopyOnWriteArrayList<ImportUiState>()
            val collector = launch { model.state.collect { observed += it } }
            withContext(main) {
                model.loadFile("synthetic.csv", "date,description,amount\n2026-09-29,ALREADY HERE,-12.344\n2026-09-29,Lunch,-9.50\n2026-09-29,Refund,5.00\ninvalid,Bad row,nope".encodeToByteArray())
            }
            withTimeout(10_000) { driver.started.await() }
            delay(100) // Give the UI projection time to expose any unchecked parsed rows.
            assertFalse(observed.any { it.rows.isNotEmpty() && it.importEnabled },
                "Review must wait for duplicate decisions before publishing importable rows")
            hold.complete(Unit)
            driver.hold.set(null)
            val reviewed = awaitState { it.rows.size == 4 && it.reviewSummary.contains("1 already imported") }
            collector.cancelAndJoin()
            assertEquals(listOf(false, true, true, false), reviewed.rows.map { it.included })
            withContext(main) {
                model.setDefaultCategory("food")
                model.setIncluded(2, false)
            }
            awaitState { it.defaultCategoryId == "food" && !it.rows[2].included && it.rows[1].categoryId == "food" }
            // A saved navigation entry keeps the ViewModel alive without collectors.
            // Read value directly: subscribing here would prevent idle expiry.
            assertEquals(4, model.state.value.rows.size, "Quick returns keep the populated review during the grace interval")
            withTimeout(7_000) { while (model.state.value.rows.isNotEmpty()) delay(20) }
            assertEquals(ImportUiState(), model.state.value)
            categoryRepo.upsert(Category("food", "Groceries", "#123456"))
            val restored = awaitState { it.rows.size == 4 && it.rows[1].categoryLabel == "Groceries" }
            assertEquals("synthetic.csv · 4 rows", restored.fileLabel)
            assertEquals(listOf(false, true, false, false), restored.rows.map { it.included })
            assertEquals("food", restored.defaultCategoryId)
            assertEquals("#123456", restored.rows[1].categoryColor)
            assertTrue(restored.rows[0].supporting.endsWith("Already imported"))
            assertEquals("Imported 1 transactions into Checking", withContext(main) { model.import() })
            val saved = postings.getBetween(day, LocalDate(2026, 9, 30)).filter { it.accountId == "a" }
            assertEquals(setOf("Already here", "Lunch"), saved.map { it.title }.toSet())
            assertEquals("food", saved.single { it.title == "Lunch" }.categoryId)
            assertEquals(9.5, saved.single { it.title == "Lunch" }.amount)
            awaitState { it.stage == ImportStage.NO_FILE }
        } finally {
            withContext(main) { owner.clear() }
            analyticsClient.close()
            analyticsPreferences.removeNode()
            driver.close()
            Dispatchers.resetMain()
            main.close()
        }
    }

    private class DelayedCandidateDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        val hold = AtomicReference<CompletableDeferred<Unit>?>(null)
        val started = CompletableDeferred<Unit>()
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
            val gate = hold.get()?.takeIf { sql.startsWith("SELECT date, amount, title FROM postings") }
                ?: return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
            return QueryResult.AsyncValue {
                started.complete(Unit)
                gate.await()
                delegate.executeQuery(identifier, sql, mapper, parameters, binders).await()
            }
        }
    }
}
