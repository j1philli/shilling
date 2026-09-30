package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    @Test
    fun backgroundReviewAndImportPreserveDuplicatesSelectionsAndCategories(): Unit = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "synthetic-ui") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
            categoryRepo.upsert(Category("food", "Food"))
            val day = LocalDate(2026, 9, 29)
            postings.recordAdHoc("Already here", 12.34, ScheduleType.EXPENSE, "a", null, day)
            val model = withContext(main) {
                ImportViewModel(accountRepo, categoryRepo, postings).also { owner.put("import", it) }
            }
            suspend fun awaitState(predicate: (ImportUiState) -> Boolean) =
                withTimeout(10_000) { model.state.first(predicate) }
            awaitState { it.accountId == "a" }
            withContext(main) {
                model.loadFile("synthetic.csv", "date,description,amount\n2026-09-29,Already here,-12.34\n2026-09-29,Lunch,-9.50\n2026-09-29,Refund,5.00\ninvalid,Bad row,nope".encodeToByteArray())
            }
            val reviewed = awaitState { it.rows.size == 4 && it.reviewSummary.contains("1 already imported") }
            assertEquals(listOf(false, true, true, false), reviewed.rows.map { it.included })
            withContext(main) {
                model.setDefaultCategory("food")
                model.setIncluded(2, false)
            }
            awaitState { it.defaultCategoryId == "food" && !it.rows[2].included && it.rows[1].categoryId == "food" }
            assertEquals("Imported 1 transactions into Checking", withContext(main) { model.import() })
            val saved = postings.getBetween(day, LocalDate(2026, 9, 30))
            assertEquals(setOf("Already here", "Lunch"), saved.map { it.title }.toSet())
            assertEquals("food", saved.single { it.title == "Lunch" }.categoryId)
            assertEquals(9.5, saved.single { it.title == "Lunch" }.amount)
            awaitState { it.stage == ImportStage.NO_FILE }
        } finally {
            withContext(main) { owner.clear() }
            driver.close()
            Dispatchers.resetMain()
            main.close()
        }
    }
}
