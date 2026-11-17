package finance.shilling.perf

import androidx.lifecycle.ViewModelStore
import finance.shilling.shared.data.*
import finance.shilling.shared.data.analytics.ProductAnalytics
import finance.shilling.shared.data.store.*
import finance.shilling.shared.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.LocalDate

/** Same regression on Android SQLite and Kotlin/Native, in the isolated perf apps only. */
internal suspend fun checkMobileImport(
    accounts: AccountRepository,
    categories: CategoryRepository,
    postings: PostingRepository,
    analytics: ProductAnalytics,
    report: (String) -> Unit
) = coroutineScope {
    val account = "import-regression-a"
    val other = "import-regression-b"
    val category = "import-regression-category"
    val day = LocalDate(2026, 1, 15)
    val end = LocalDate(2026, 1, 16)
    val owner = ViewModelStore()
    var observer: Job? = null
    try {
        accounts.upsert(Account(account, "Import regression A", 0.0))
        accounts.upsert(Account(other, "Import regression B", 0.0))
        categories.upsert(Category(category, "Original category", "#4477AA"))
        postings.record(Posting("import-regression-duplicate", null, ScheduleType.EXPENSE,
            account, day, 12.34, title = "Synthetic import 0"))
        val model = withContext(Dispatchers.Main) {
            ImportViewModel(accounts, categories, postings, analytics).also { owner.put("import", it) }
        }
        suspend fun awaitState(predicate: (ImportUiState) -> Boolean): ImportUiState =
            withTimeout(30_000) { model.state.first(predicate) }
        observer = launch { model.state.collect {} }
        model.setAccount(account)
        awaitState { it.accountId == account }
        model.loadFile("large.csv", buildString {
            append("date,description,amount\n")
            repeat(10_000) { append("2026-01-15,Synthetic import $it,-12.34\n") }
            append("invalid,Bad row,nope\n")
        }.encodeToByteArray())
        val large = awaitState { it.rows.size == 10_001 && it.reviewSummary.contains("1 already imported") }
        check(!large.rows[0].included && !large.rows.last().valid)
        check(large.rows.count { it.included } == 9_999)
        model.setDefaultCategory(category)
        model.setIncluded(1, false)
        model.setRowCategory(2, null)
        awaitState { it.rows.size == 10_001 && !it.rows[1].included &&
            it.rows[2].categoryId == null && it.rows[3].categoryId == category }
        categories.upsert(Category(category, "Renamed category", "#8844AA"))
        awaitState { it.rows.size == 10_001 && it.rows[3].categoryLabel == "Renamed category" &&
            it.rows[3].categoryColor == "#8844AA" }
        report("large-review-edits")

        // Keep the ViewModel alive as navigation does, but stop all collectors.
        observer.cancelAndJoin()
        observer = null
        delay(6_000)
        check(model.state.value.rows.isEmpty()) { "Hidden import retained projected rows" }
        observer = launch { model.state.collect {} }
        val resumed = awaitState { it.rows.size == 10_001 && it.rows[3].categoryId == category }
        check(!resumed.rows[0].included && !resumed.rows[1].included && resumed.rows[2].categoryId == null)
        report("hidden-review-resumed")

        model.setAccount(other)
        awaitState { it.accountId == other && it.rows.size == 10_001 && it.rows[0].included &&
            !it.reviewSummary.contains("already imported") }
        model.setAccount(account)
        awaitState { it.accountId == account && it.rows.size == 10_001 && !it.rows[0].included }
        report("account-duplicates-refreshed")

        // A replacement file must clear row overrides while retaining the default category.
        model.loadFile("small.csv", ("date,description,amount\n" +
            "2026-01-15,Synthetic import 0,-12.34\n" +
            "2026-01-15,New expense,-9.50\n" +
            "2026-01-15,Refund,5.00\n" +
            "invalid,Bad row,nope\n").encodeToByteArray())
        val small = awaitState { it.rows.size == 4 && it.rows[1].title == "New expense" }
        check(small.rows.map { it.included } == listOf(false, true, true, false))
        check(small.rows[2].categoryId == category)
        model.setRowCategory(2, null)
        awaitState { it.rows.size == 4 && it.rows[2].categoryId == null }
        check(model.import() == "Imported 2 transactions into Import regression A")
        awaitState { it.stage == ImportStage.NO_FILE && it.rows.isEmpty() }
        val imported = postings.getBetween(day, end).filter { it.accountId == account }
        check(imported.size == 3)
        check(imported.single { it.title == "New expense" }.let {
            it.amount == 9.50 && it.type == ScheduleType.EXPENSE && it.categoryId == category
        })
        check(imported.single { it.title == "Refund" }.let {
            it.amount == 5.0 && it.type == ScheduleType.INCOME && it.categoryId == null
        })
        report("replacement-import-verified")
    } finally {
        observer?.cancelAndJoin()
        withContext(Dispatchers.Main) { owner.clear() }
        postings.getBetween(day, end).filter { it.accountId == account || it.accountId == other }
            .forEach { postings.delete(it.id) }
        accounts.delete(account)
        accounts.delete(other)
        categories.delete(category)
    }
}
