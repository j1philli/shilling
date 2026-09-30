package finance.shilling.perf

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.sqldelight.db.*
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.usecase.ComputeWindowUseCase
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.ui.ActivityView
import finance.shilling.shared.ui.PlanView
import finance.shilling.shared.presentation.*
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import finance.shilling.shared.ui.ShillingTheme
import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.json.JSONObject
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import java.io.File
import java.util.UUID
import kotlin.time.Clock

/** Renders real production screens over an isolated Store5 fixture. */
class UiPerformanceActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val metricsThread = HandlerThread("synthetic-frame-metrics")
    private val frameTimes = mutableListOf<Double>()
    private var driver: SqlDriver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "history"
        setContentView(TextView(this).also { it.text = "Seeding synthetic UI fixture…" })
        Logger.setMinSeverity(Severity.Error)
        scope.launch {
            try {
                val seedStarted = SystemClock.elapsedRealtime()
                val reseed = intent.getBooleanExtra("reseed", false)
                val definitions = withContext(Dispatchers.IO) {
                    val schema = object : SqlSchema<QueryResult.Value<Unit>> {
                        override val version = 1L
                        override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
                        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Value(Unit)
                    }
                    val shouldSeed = reseed || !getDatabasePath("ui-synthetic.db").exists()
                    if (shouldSeed) deleteDatabase("ui-synthetic.db")
                    val dbDriver = AndroidSqliteDriver(schema, applicationContext, "ui-synthetic.db")
                    driver = dbDriver
                    ensureLocalSchemaReady(dbDriver)
                    val db = ShillingDatabase(dbDriver)
                    val ids = object : IdGenerator { override fun newId() = UUID.randomUUID().toString() }
                    val notifier = ChangeNotifier()
                    val accounts = createAccountStore(db)
                    val categories = createCategoryStore(db)
                    val schedules = createScheduleStore(db)
                    val postings = createPostingStore(db)
                    val receipts = createReceiptStore(db)
                    val accountRepo = AccountRepository(notifier, accounts)
                    val categoryRepo = CategoryRepository(notifier, categories)
                    val scheduleRepo = ScheduleRepository(notifier, schedules, createScheduleExceptionStore(db))
                    val postingRepo = PostingRepository(ids, notifier, postings, accounts, categories, schedules)
                    val receiptRepo = ReceiptRepository(notifier, receipts)
                    val files = Store5ReceiptFileStore(SyntheticFiles(File(filesDir, "ui-receipts")), { _, _, _ -> }, Dispatchers.IO)
                    if (shouldSeed) {
                        accountRepo.upsert(Account("ui-account", "Synthetic checking", 10000.0))
                        categoryRepo.upsert(Category("ui-category", "Synthetic category"))
                        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                        postingRepo.bulkImport(List(10_000) { Triple("Synthetic transaction $it", -12.34, today.minus(it % 28, DateTimeUnit.DAY)) }, "ui-account", "ui-category")
                        repeat(1_000) { scheduleRepo.upsert(Schedule(
                            id = "ui-schedule-$it", title = "Synthetic schedule $it", amount = 10.0,
                            type = ScheduleType.EXPENSE, accountId = "ui-account", categoryId = "ui-category",
                            startDate = today, freq = Frequency.WEEKLY
                        )) }
                    }
                    Log.i("ShillingUi", JSONObject().put("screen", screen).put("status", "fixtureReady")
                        .put("seeded", shouldSeed).put("setupMs", SystemClock.elapsedRealtime() - seedStarted).toString())
                    module {
                        single { accountRepo }; single { categoryRepo }; single { scheduleRepo }
                        single { postingRepo }; single { receiptRepo }; single<ReceiptFileStore> { files }
                        single<IdGenerator> { ids }
                        single { ComputeWindowUseCase(notifier, accountRepo, categoryRepo, scheduleRepo, postingRepo) }
                        single { ActivityViewModel(postingRepo) }
                        single { PlanOverviewViewModel(get(), OccurrenceActions(postingRepo, scheduleRepo)) }
                        single { PlanRequests() }
                    }
                }
                metricsThread.start()
                val renderStarted = SystemClock.elapsedRealtime()
                var firstFrames = 0
                window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
                    val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
                    synchronized(frameTimes) { frameTimes.add(duration) }
                    if (++firstFrames <= 5) Log.i("ShillingUi", JSONObject().put("screen", screen)
                        .put("status", "initialFrame").put("index", firstFrames)
                        .put("sinceRenderMs", SystemClock.elapsedRealtime() - renderStarted)
                        .put("durationMs", duration).toString())
                }, Handler(metricsThread.looper))
                setContent {
                    KoinApplication(application = { modules(definitions) }) {
                        ShillingTheme {
                            val ready = {
                                Log.i("ShillingUi", JSONObject().put("screen", screen)
                                    .put("status", "contentReady")
                                    .put("sinceRenderMs", SystemClock.elapsedRealtime() - renderStarted).toString())
                                Unit
                            }
                            if (screen == "weekly" || screen == "plan") {
                                val model = koinInject<PlanOverviewViewModel>()
                                LaunchedEffect(model) { model.state.first { it.days.isNotEmpty() }; ready() }
                                PlanView({}, { _, _ -> }, {}, {}, overviewViewModel = model)
                            } else {
                                val model = koinInject<ActivityViewModel>()
                                LaunchedEffect(model) { model.state.first { it.sections.isNotEmpty() }; ready() }
                                ActivityView({}, {}, viewModel = model)
                            }
                        }
                    }
                }
                Log.i("ShillingUi", JSONObject().put("screen", screen).put("status", "rendering").toString())
                while (isActive) {
                    delay(2_000)
                    val samples = synchronized(frameTimes) { frameTimes.toList() }.sorted()
                    if (samples.isNotEmpty()) Log.i("ShillingUi", JSONObject().put("screen", screen)
                        .put("frames", samples.size).put("over16ms", samples.count { it > 16.67 })
                        .put("p50ms", samples[samples.size / 2]).put("p95ms", samples[((samples.size - 1) * .95).toInt()])
                        .put("maxMs", samples.last()).toString())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { Log.e("ShillingUi", "UI fixture failed", failure) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        metricsThread.quitSafely()
        driver?.close()
        super.onDestroy()
    }
}
