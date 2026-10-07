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
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.LocalDate
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Isolated, offline synthetic workloads using the production Store5 repositories and driver. */
class PerformanceActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var output: TextView
    private lateinit var results: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).also { it.text = "Running synthetic Shilling workloads…"; setContentView(it) }
        results = File(filesDir, "results.jsonl").also { it.writeText("") }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { runWorkloads() }
                output.text = "Synthetic workloads completed. Results: results.jsonl"
                emit(JSONObject().put("status", "complete"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                output.text = "Benchmark failed: ${failure.message}"
                emit(JSONObject().put("status", "failed").put("error", failure.toString()))
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun emit(result: JSONObject) {
        val line = result.toString()
        results.appendText("$line\n")
        Log.i("ShillingPerf", line)
    }

    private suspend fun <T> measured(name: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val nanos = System.nanoTime() - start
        emit(JSONObject().put("workload", name).put("milliseconds", nanos / 1_000_000.0)
            .put("memory", memoryProbe()))
        return result
    }

    private suspend fun runWorkloads() {
        Logger.setMinSeverity(Severity.Error)
        val schema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version = ShillingDatabase.Schema.version
            override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Value(Unit)
        }
        // Only this benchmark application's disposable database is reset.
        deleteDatabase("synthetic.db")
        val driver = AndroidSqliteDriver(schema, applicationContext, "synthetic.db")
        try {
            ensureLocalSchemaReady(driver)
            val db = ShillingDatabase(driver)
            val ids = object : IdGenerator { override fun newId() = UUID.randomUUID().toString() }
            val notifier = ChangeNotifier()
            val sync = StoreSyncDeps(db, null, "synthetic-device", ids)
            val accounts = createAccountStore(db, sync)
            val categories = createCategoryStore(db, sync)
            val schedules = createScheduleStore(db, sync)
            val exceptions = createScheduleExceptionStore(db, sync)
            val postings = createPostingStore(db, sync)
            val accountRepo = AccountRepository(notifier, accounts, sync)
            val categoryRepo = CategoryRepository(notifier, categories, sync)
            val scheduleRepo = ScheduleRepository(notifier, schedules, exceptions, sync)
            val postingRepo = PostingRepository(ids, notifier, postings, accounts, categories, schedules, sync)
            val preferences = com.russhwolf.settings.SharedPreferencesSettings(
                getSharedPreferences("import-regression", MODE_PRIVATE))
            preferences.putBoolean("posthog_consent", false)
            val client = createSyncHttpClient()
            try {
                checkMobileImport(accountRepo, categoryRepo, postingRepo,
                    finance.shilling.shared.data.analytics.ProductAnalytics(preferences, ids, client)) { check ->
                    emit(JSONObject().put("importCheck", check).put("passed", true))
                }
            } finally { client.close() }
            accountRepo.upsert(Account("synthetic-account", "Synthetic account", 10_000.0))
            categoryRepo.upsert(Category("synthetic-category", "Synthetic category"))
            val start = LocalDate(2026, 1, 1)
            val end = LocalDate(2027, 1, 1)
            val mapping = CsvColumnMapping(0, 1, 2)
            for (count in listOf(1_000, 10_000)) {
                val csv = buildString {
                    append("date,description,amount\n")
                    repeat(count) { append("2026-01-15,Synthetic $it,-12.34\n") }
                }
                val parsed = measured("csv_parse_$count") { CsvImporter.parseAll(csv, mapping) }
                check(parsed.size == count && parsed.all { it.isValid })
                val items = parsed.map { Triple(it.parsedDescription!!, it.parsedAmount!!, it.parsedDate!!) }
                measured("store5_import_$count") { postingRepo.bulkImport(items, "synthetic-account", "synthetic-category") }
            }
            check(postingRepo.getBetween(start, end).size == 11_000)
            measured("seed_1000_schedules") {
                repeat(1_000) { scheduleRepo.upsert(Schedule(
                    id = "synthetic-schedule-$it", title = "Synthetic schedule $it", amount = 10.0,
                    type = ScheduleType.EXPENSE, accountId = "synthetic-account",
                    categoryId = "synthetic-category", startDate = start, freq = Frequency.WEEKLY
                )) }
            }
            val window = ComputeWindowUseCase(accountRepo, categoryRepo, scheduleRepo, postingRepo)
            repeat(3) { run ->
                measured("recent20_run$run") { check(postingRepo.loadRecentPostings(20).size == 20) }
                measured("history11000_run$run") { check(postingRepo.watchBetween(start, end).first().size == 11_000) }
                measured("weekly1000_run$run") { check(window.watchWindow(start, LocalDate(2026, 1, 8)).first().isNotEmpty()) }
            }
            val files = Store5ReceiptFileStore(SyntheticFiles(File(filesDir, "receipts")), { _, _, _ -> }, Dispatchers.IO)
            files.clearAll()
            for (size in listOf(1, 10, 50)) {
                emit(JSONObject().put("workload", "receipt_start_${size}MiB").put("memory", memoryProbe()))
                val bytes = ByteArray(size * 1024 * 1024) { (it % 251).toByte() }
                measured("receipt_write_${size}MiB") { files.store("receipt", "synthetic.bin", bytes) }
                measured("receipt_read_${size}MiB") { check(files.read("receipt")!!.contentEquals(bytes)) }
                val sender = FileTransferManager(files)
                val messages = measured("receipt_prepare_${size}MiB") { sender.prepareTransfer("receipt", "synthetic.bin")!! }
                val receiver = FileTransferManager(files)
                measured("receipt_receive_${size}MiB") {
                    messages.collect { message ->
                        when (message) {
                            is FileTransferMessage.FileHeader -> receiver.handleHeader(message)
                            is FileTransferMessage.FileChunk -> receiver.handleChunk(message)
                            is FileTransferMessage.FileComplete -> check(receiver.finalizeTransfer("receipt", "synthetic.bin"))
                            else -> error("Unexpected transfer message")
                        }
                    }
                }
                check(files.read("receipt")!!.contentEquals(bytes))
                files.clearAll()
                emit(JSONObject().put("workload", "receipt_clear_${size}MiB").put("memory", memoryProbe()))
            }
        } finally { driver.close() }
    }
}

internal class SyntheticFiles private constructor(
    private val storage: DirectoryReceiptFileStorage,
    private val onStored: (String) -> Unit
) : StagedReceiptFileStorage by storage {
    constructor(directory: File, onStored: (String) -> Unit = {}) : this(DirectoryReceiptFileStorage(directory), onStored)
    override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
        storage.store(receiptId, fileName, bytes)
        onStored(receiptId)
    }
    override suspend fun commitStage(token: String, receiptId: String) {
        storage.commitStage(token, receiptId)
        onStored(receiptId)
    }
}
