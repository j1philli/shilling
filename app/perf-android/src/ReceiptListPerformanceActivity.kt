package finance.shilling.perf

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import app.cash.sqldelight.db.*
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.datetime.LocalDate
import org.json.JSONObject
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import java.util.concurrent.atomic.AtomicLong

/** Offline receipt-list data latency, using production Store5 and Android SQLite. */
@OptIn(ExperimentalStoreApi::class)
class ReceiptListPerformanceActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val output = TextView(this).also { it.text = "Measuring synthetic receipt lists…"; setContentView(it) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { runWorkload() }
                output.text = "Receipt-list measurements complete"
                emit(JSONObject().put("status", "complete"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { emit(JSONObject().put("status", "failed").put("error", failure.toString())) }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun emit(value: JSONObject) { Log.i("ShillingList", value.toString()) }

    private suspend fun runWorkload() = coroutineScope {
        Logger.setMinSeverity(Severity.Error)
        val schema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version = 1L
            override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Value(Unit)
        }
        deleteDatabase("receipt-list-synthetic.db")
        val driver = CountingDriver(AndroidSqliteDriver(schema, applicationContext, "receipt-list-synthetic.db"))
        try {
            ensureLocalSchemaReady(driver)
            val db = ShillingDatabase(driver)
            val notifier = ChangeNotifier()
            val accounts = createAccountStore(db)
            val postings = createPostingStore(db)
            val schedules = createScheduleStore(db)
            val receipts = createReceiptStore(db)
            val repo = ReceiptRepository(notifier, receipts)
            val receiptCount = intent.getIntExtra("receiptCount", 250)
            check(receiptCount in listOf(250, 1_000, 10_000))
            val variant = intent.getStringExtra("variant") ?: "joined"
            check(variant in listOf("joined", "combined"))
            // Benchmark reference: the pre-change repository projection, through the
            // same production Store5 sources. Select only in this isolated fixture.
            val watch: () -> Flow<List<ReceiptWithPosting>> = if (variant == "combined") {
                { combine(cached(receipts, ReceiptKey.All), cached(postings, PostingKey.All), cached(schedules, ScheduleKey.All)) { rs, ps, ss ->
                    val byPosting = ps.associateBy { it.id }
                    val bySchedule = ss.associateBy { it.id }
                    rs.map { r ->
                        val p = r.postingId?.let(byPosting::get)
                        ReceiptWithPosting(r, p?.title ?: p?.scheduleId?.let(bySchedule::get)?.title, p?.date?.toString())
                    }
                } }
            } else repo::watchAll
            val day = LocalDate(2026, 1, 15)
            val scheduleRows = List(1_000) { Schedule("s$it", "Schedule $it", 1.0, ScheduleType.EXPENSE,
                "a", startDate = day, freq = Frequency.MONTHLY_BY_DAY) }
            val postingRows = List(10_000) { Posting("p$it", "s${it % 1_000}", ScheduleType.EXPENSE,
                "a", day, 1.0, title = if (it % 2 == 0) null else "Posting $it") }
            val receiptRows = List(receiptCount) { Receipt("r$it", "p$it", "synthetic-$it", "synthetic.bin", it.toLong()) }
            accounts.write(StoreWriteRequest.of<AccountKey, List<Account>, Unit>(AccountKey.All, listOf(Account("a", "Synthetic", 0.0))))
            schedules.write(StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(ScheduleKey.All, scheduleRows))
            postings.write(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.All, postingRows))
            receipts.write(StoreWriteRequest.of<ReceiptKey, List<Receipt>, Unit>(ReceiptKey.All, receiptRows))
            emit(JSONObject().put("status", "ready").put("postings", 10_000).put("schedules", 1_000).put("receipts", receiptCount))

            repeat(5) { run ->
                driver.reset()
                val started = System.nanoTime()
                val rows = withTimeout(10_000) { watch().first() }
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                check(rows.size == receiptCount && rows.first().receipt.id == "r${receiptCount - 1}")
                check(rows.first { it.receipt.id == "r0" }.postingTitle == "Schedule 0")
                emit(driver.measurement("initial", run, elapsed))
                delay(100)
            }

            val updates = Channel<List<ReceiptWithPosting>>(Channel.UNLIMITED)
            val watcher = launch { watch().collect { updates.send(it) } }
            try {
                withTimeout(10_000) { updates.receive() }
                suspend fun measuredEdit(name: String, run: Int, matches: (List<ReceiptWithPosting>) -> Boolean, write: suspend () -> Unit) {
                    while (updates.tryReceive().isSuccess) { }
                    driver.reset()
                    val started = System.nanoTime()
                    write()
                    withTimeout(10_000) { while (!matches(updates.receive())) { } }
                    val elapsed = (System.nanoTime() - started) / 1_000_000.0
                    delay(50) // Include any extra projection queries after the first correct emission.
                    emit(driver.measurement(name, run, elapsed))
                }
                repeat(5) { run ->
                    val title = "Edited posting $run"
                    measuredEdit("posting_edit", run, { rows -> rows.first { it.receipt.id == "r1" }.postingTitle == title }) {
                        postings.write(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.ById("p1"), listOf(postingRows[1].copy(title = title))))
                    }
                    val scheduleTitle = "Edited schedule $run"
                    measuredEdit("schedule_edit", run, { rows -> rows.first { it.receipt.id == "r0" }.postingTitle == scheduleTitle }) {
                        schedules.write(StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(ScheduleKey.ById("s0"), listOf(scheduleRows[0].copy(title = scheduleTitle))))
                    }
                    val notes = "Edited note $run"
                    measuredEdit("receipt_edit", run, { rows -> rows.first { it.receipt.id == "r1" }.receipt.notes == notes }) {
                        repo.updateMetadata("r1", notes, null, null)
                    }
                }
            } finally { watcher.cancelAndJoin(); updates.close() }
        } finally { driver.close() }
    }

    private fun <Key : Any, Item> cached(store: MutableStore<Key, List<Item>>, key: Key): Flow<List<Item>> =
        store.stream<List<Item>>(StoreReadRequest.cached(key, refresh = false))
            .mapNotNull { (it as? StoreReadResponse.Data)?.value }

    private class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        private val queries = AtomicLong()
        private val rows = AtomicLong()
        private val queryNanos = AtomicLong()
        fun reset() { queries.set(0); rows.set(0); queryNanos.set(0) }
        fun measurement(workload: String, run: Int, ms: Double) = JSONObject()
            .put("workload", workload).put("run", run).put("milliseconds", ms)
            .put("selectQueries", queries.get()).put("rowsRead", rows.get())
            .put("sqlQueryMs", queryNanos.get() / 1_000_000.0)
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
            queries.incrementAndGet()
            val start = System.nanoTime()
            return try { delegate.executeQuery(identifier, sql, { cursor ->
                mapper(object : SqlCursor by cursor {
                    override fun next(): QueryResult<Boolean> = cursor.next().also { if (it.value) rows.incrementAndGet() }
                })
            }, parameters, binders) } finally { queryNanos.addAndGet(System.nanoTime() - start) }
        }
    }
}
