package finance.shilling.perf

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import com.russhwolf.settings.Settings
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import finance.shilling.shared.data.*
import finance.shilling.shared.data.analytics.ProductAnalyticsEnvironment
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.presentation.*
import finance.shilling.shared.session.*
import finance.shilling.shared.ui.*
import io.ktor.client.webrtc.JsWebRtc
import io.ktor.client.webrtc.WebRtcClient
import kotlinx.browser.document
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.*
import org.koin.core.Koin
import org.koin.dsl.module
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private const val SERVER = "http://127.0.0.1:18091"
private fun mark(name: String) {
    println("SHILLING_MEMORY $name")
    document.title = "Shilling Memory: $name"
}

/** Dedicated synthetic app. Uses production Store5 graphs, screens and navigation. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    Logger.setMinSeverity(Severity.Info) // Match the production release entry point.
    val workload = document.querySelector("meta[name=shilling-memory-workload]")?.getAttribute("content")
    if (workload == "startup" || workload == "startup-compose-first") {
        profileStartup(composeFirst = workload == "startup-compose-first")
        return
    }
    var status by mutableStateOf("Preparing synthetic database…")
    var ready by mutableStateOf(false)
    lateinit var koin: Koin
    MainScope().launch {
        runCatching {
            val (settings, graph) = createFixture()
            koin = graph
            if (!settings.getBoolean("memory_fixture_seeded_v1", false)) {
                mark("seed_begin")
                seed(koin)
                settings.putBoolean("memory_fixture_seeded_v1", true)
                mark("seed_complete_restart")
                status = "10,000 transactions, 1,000 schedules, 40 categories, 20 accounts, 250 receipt records seeded through Store5. Restart to profile."
            } else {
                koin.get<AppSession>().start()
                koin.get<AppSession>().phase.first { it is SessionPhase.Ready }
                ready = true
            }
        }.onFailure { status = "Fixture failed: $it"; println("SHILLING_MEMORY ERROR ${it.stackTraceToString()}") }
    }
    ComposeViewport(document.body!!) {
        if (!ready) ShillingTheme { Text(status) }
        else ShillingAppBootstrap(AppBootstrapScaffoldConfig(
            navRailTopPadding = 52.dp,
            navRailWidth = 98.dp,
            navControllerHook = { controller -> Workloads(controller, koin) }
        ))
    }
}

/** Ordinary fixture setup; the optional pauses belong only to the startup diagnostic. */
private suspend fun createFixture(checkpoint: suspend (String) -> Unit = {}): Pair<Settings, Koin> {
    val driver = WebWorkerDriver(createDatabaseWorker("sqldelight.worker.js"))
    ensureLocalSchemaReady(driver)
    val db = ShillingDatabase(driver)
    checkpoint("startup_database")
    val settings = Settings()
    completeFirstLaunchOnboarding(settings, DeploymentSelection.SELF_HOSTED, SERVER)
    val koin = initKoin(module {
        single { db }; single { settings }
        single<IdGenerator> { object : IdGenerator { override fun newId() = Uuid.random().toString() } }
        single { ProductAnalyticsEnvironment(developmentBuild = true) }
        single { AppSessionConfig(selfHostedOnly = true, defaultSelfHostedServerUrl = SERVER) }
        single<ReceiptFileStoreFactory> { ReceiptFileStoreFactory { id, _ ->
            Store5ReceiptFileStore(SqlReceiptFileStorage(db, id), { _, _, _ -> })
        } }
        single { createSyncHttpClient() }
        single { WebRtcPlatform(createClient = { ice -> WebRtcClient(JsWebRtc) {
            defaultConnectionConfig = { iceServers = ice() }
        } }) }
    }, sessionModule)
    checkpoint("startup_koin")
    return settings to koin
}

/**
 * Fixture-only ablation: one Home model is collected before its first render and
 * reused by HomeView. No navigation graph or second Home model is created here.
 * Reversing Compose/database order reveals shared startup and collection effects.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun profileStartup(composeFirst: Boolean) {
    var renderedHome by mutableStateOf<HomeViewModel?>(null)
    fun compose() = ComposeViewport(document.body!!) {
        ShillingTheme {
            val model = renderedHome
            if (model == null) Text("Shilling startup allocation diagnostic")
            else HomeView(onDestination = {}, viewModel = model)
        }
    }
    MainScope().launch {
        suspend fun checkpoint(phase: String) {
            delay(15000)
            mark("READY $phase")
            delay(15000)
        }
        try {
            checkpoint("startup_entry")
            if (composeFirst) {
                compose()
                checkpoint("startup_compose")
            }
            val (settings, koin) = createFixture(::checkpoint)
            check(settings.getBoolean("memory_fixture_seeded_v1", false)) {
                "The startup diagnostic requires the ordinary fixture's seeded database"
            }
            koin.get<AppSession>().start()
            withTimeout(60000) { koin.get<AppSession>().phase.first { it is SessionPhase.Ready } }
            checkpoint("startup_session")
            if (!composeFirst) {
                compose()
                checkpoint("startup_compose")
            }
            val model = koin.get<HomeViewModel>()
            // Keep exactly this model subscribed across calculation and rendering.
            val subscription = launch { model.state.collect() }
            withTimeout(60000) { model.state.first {
                it.accountCount == 20 && it.categoryCount == 40 && it.scheduleCount == 1000 &&
                    it.receiptCount == 250 && it.recent.size == 3 && it.budget.lines.size == 1000 &&
                    it.upcoming.isNotEmpty()
            } }
            checkpoint("startup_home_projection")
            renderedHome = model
            checkpoint("startup_home_rendered")
            // Hand the subscription to the visible screen; do not collect a duplicate graph.
            subscription.cancelAndJoin()
            delay(60000)
            checkpoint("startup_idle")
            mark("COMPLETE")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { mark("ERROR ${failure.stackTraceToString()}") }
    }
}

private suspend fun seed(koin: Koin) {
    val accounts = koin.get<AccountRepository>()
    val categories = koin.get<CategoryRepository>()
    val schedules = koin.get<ScheduleRepository>()
    val postings = koin.get<PostingRepository>()
    val receipts = koin.get<ReceiptRepository>()
    // Only this dedicated benchmark bundle/database is ever initialized here.
    receipts.clearAll(); postings.clearAll(); schedules.clearAll(); categories.clearAll(); accounts.clearAll()
    repeat(20) { accounts.upsert(Account("mem-account-$it", "Synthetic account $it", 10000.0)) }
    repeat(40) { categories.upsert(Category("mem-category-$it", "Synthetic category $it")) }
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    repeat(1000) { schedules.upsert(Schedule(
        id = "mem-schedule-$it", title = "Synthetic schedule $it", amount = 10.0 + it % 100,
        type = ScheduleType.EXPENSE, accountId = "mem-account-${it % 20}", categoryId = "mem-category-${it % 40}",
        startDate = today.minus(it % 28, DateTimeUnit.DAY), freq = Frequency.WEEKLY
    )) }
    repeat(20) { account -> postings.bulkImport(List(500) { index ->
        Triple("Synthetic transaction ${account * 500 + index}", -12.34, today.minus(index % 90, DateTimeUnit.DAY))
    }, "mem-account-$account", "mem-category-${account % 40}") }
    repeat(250) { receipts.save(Receipt(
        id = "mem-receipt-$it", filePath = "mem-receipt-$it", originalName = "Synthetic receipt $it.png",
        addedAt = Clock.System.now().toEpochMilliseconds()
    )) }
}

@Composable
private fun Workloads(controller: NavHostController, koin: Koin) {
    val entry by controller.currentBackStackEntryAsState()
    var importModel by remember { mutableStateOf<ImportViewModel?>(null) }
    if (entry?.let(AppPaths::pathOf) == "/import") {
        val model = koinViewModel<ImportViewModel>(viewModelStoreOwner = entry!!)
        SideEffect { importModel = model }
        DisposableEffect(model) { onDispose { importModel = null } }
    }
    LaunchedEffect(controller) {
        suspend fun screen(name: String, path: String, section: PlanSection? = null, period: PlanPeriod? = null) {
            mark("BEGIN $name")
            section?.let { koin.get<PlanRequests>().request(PlanRequest(it, period)) }
            AppPaths.navigate(controller, path)
            delay(6000)
            mark("READY $name")
            delay(9000)
        }
        try {
            delay(20000) // allow the profiler to attach before navigation starts
            val workload = document.querySelector("meta[name=shilling-memory-workload]")?.getAttribute("content")
            if (workload == "csv-session") {
                screen("session_home", "/home")
                val lifetime = ReviewLifetime()
                repeat(4) { index ->
                    // A separate suspend frame drops this cycle's strong model reference
                    // before the next cycle and the final idle measurements.
                    reviewSessionCycle(controller, { importModel }, lifetime, index + 1, import = index == 3)
                    delay(6000)
                    lifetime.report("settled_${index + 1}")
                    mark("READY session_closed_${index + 1}")
                    delay(6000)
                }
                repeat(3) { minute ->
                    delay(60000)
                    lifetime.report("idle_${minute + 1}")
                    mark("READY session_idle_${minute + 1}")
                }
                check(lifetime.opened == 4 && lifetime.closed == 4)
                delay(10000)
                mark("COMPLETE")
                return@LaunchedEffect
            }
            if (workload == "idle") {
                screen("home_cold", "/home")
                delay(60000)
                mark("READY final_idle")
                delay(10000)
                mark("COMPLETE")
                return@LaunchedEffect
            }
            val csvOnly = workload == "csv" || workload == "csv-review"
            if (csvOnly) screen("home_before_csv", "/home")
            repeat(if (csvOnly) 0 else 2) { pass ->
                screen("home_$pass", "/home")
                screen("plan_week_$pass", "/plan", PlanSection.OVERVIEW, PlanPeriod.WEEK)
                screen("plan_month_$pass", "/plan", PlanSection.OVERVIEW, PlanPeriod.MONTH)
                screen("schedules_$pass", "/plan", PlanSection.SCHEDULES)
                screen("categories_$pass", "/plan", PlanSection.CATEGORIES)
                screen("accounts_$pass", "/plan", PlanSection.ACCOUNTS)
                screen("activity_$pass", "/activity")
                screen("receipts_$pass", "/receipts")
                screen("settings_$pass", "/settings")
            }
            for ((name, path) in if (csvOnly) emptyList() else listOf("transaction_editor" to "/transaction/new", "schedule_editor" to "/schedule/mem-schedule-0", "category_editor" to "/category/mem-category-0", "account_editor" to "/account/mem-account-0")) {
                screen(name, path)
                controller.popBackStack()
            }
            mark("BEGIN csv_load_10000")
            AppPaths.navigate(controller, "/import")
            val model = withTimeout(10000) { snapshotFlow { importModel }.first { it != null }!! }
            val loadStarted = TimeSource.Monotonic.markNow()
            model.loadFile("synthetic-memory-10000.csv", csvBytes())
            withTimeout(60000) { model.state.first { it.rows.size == 10000 && it.importEnabled } }
            val loadMs = loadStarted.elapsedNow().inWholeMicroseconds / 1000.0
            mark("READY csv_review_10000")
            delay(15000)
            if (workload == "csv-review") {
                mark("BEGIN csv_row_edits_60")
                val toggleMs = mutableListOf<Double>()
                val categoryMs = mutableListOf<Double>()
                suspend fun edit(times: MutableList<Double>, action: () -> Unit, ready: (ImportUiState) -> Boolean) {
                    val started = TimeSource.Monotonic.markNow()
                    action()
                    withTimeout(10000) { model.state.first(ready) }
                    times += started.elapsedNow().inWholeMicroseconds / 1000.0
                    delay(50)
                }
                repeat(5) {
                    for (index in listOf(0, 5000, 9999)) {
                        edit(toggleMs, { model.setIncluded(index, false) }) { !it.rows[index].included }
                        edit(toggleMs, { model.setIncluded(index, true) }) { it.rows[index].included }
                        edit(categoryMs, { model.setRowCategory(index, "mem-category-1") }) { it.rows[index].categoryId == "mem-category-1" }
                        edit(categoryMs, { model.setRowCategory(index, null) }) { it.rows[index].categoryId == null }
                    }
                }
                println("SHILLING_IMPORT_REVIEW {\"loadMs\":$loadMs,\"toggleMs\":[${toggleMs.joinToString()}],\"categoryMs\":[${categoryMs.joinToString()}]}")
                mark("READY csv_row_edits_60")
                delay(15000)
            }
            mark("BEGIN csv_import_10000")
            check(model.import() != null)
            mark("READY csv_imported_10000")
            delay(10000)
            controller.popBackStack()
            screen("home_after_import", "/home")
            screen("activity_after_import", "/activity")
            screen("home_settle", "/home")
            delay(45000)
            mark("READY final_idle")
            delay(10000)
            mark("COMPLETE")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { mark("ERROR $failure") }
    }
}

/** Counts scope cancellation, not garbage collection; never stores a view model. */
private class ReviewLifetime {
    var opened = 0
    var closed = 0
    fun report(event: String, retainedRows: Int? = null) {
        println("SHILLING_REVIEW_LIFETIME {\"event\":\"$event\",\"opened\":$opened,\"closed\":$closed,\"retainedRows\":$retainedRows}")
    }
}

private suspend fun reviewSessionCycle(
    controller: NavHostController,
    currentModel: () -> ImportViewModel?,
    lifetime: ReviewLifetime,
    cycle: Int,
    import: Boolean
) {
    mark("BEGIN session_cycle_$cycle")
    AppPaths.navigate(controller, "/activity")
    delay(1000)
    AppPaths.navigate(controller, "/import")
    val model = withTimeout(10000) { snapshotFlow { currentModel() }.first { it != null }!! }
    check(model.state.value.rows.isEmpty()) { "A new import must not restore a cancelled file" }
    val job = checkNotNull(model.viewModelScope.coroutineContext[Job])
    lifetime.opened++
    // Capture counters and the cycle number only, not the model or its state.
    job.invokeOnCompletion { lifetime.closed++; lifetime.report("closed_$cycle") }
    model.loadFile("synthetic-session-$cycle.csv", csvBytes())
    withTimeout(60000) { model.state.first { it.rows.size == 10000 && it.importEnabled } }
    model.setIncluded(0, false)
    model.setRowCategory(5000, "mem-category-1")
    withTimeout(10000) { model.state.first { !it.rows[0].included && it.rows[5000].categoryId == "mem-category-1" } }
    mark("READY session_review_$cycle")
    delay(6000)

    // Exercise the real tab-save path with an unfinished review. Reading state.value
    // does not subscribe or keep its upstream active while the screen is hidden.
    AppPaths.navigate(controller, "/home")
    delay(12000)
    check(!job.isCancelled) { "Tab switching must preserve the unfinished import" }
    lifetime.report("hidden_$cycle", model.state.value.rows.size)
    mark("READY session_hidden_$cycle")
    delay(6000)
    AppPaths.navigate(controller, "/activity")
    withTimeout(10000) { snapshotFlow { currentModel() }.first { it === model } }
    withTimeout(60000) { model.state.first {
        it.rows.size == 10000 && !it.rows[0].included && it.rows[5000].categoryId == "mem-category-1"
    } }
    model.setIncluded(0, true)
    model.setRowCategory(5000, null)
    withTimeout(10000) { model.state.first { it.rows[0].included && it.rows[5000].categoryId == null } }
    mark("READY session_restored_$cycle")
    delay(6000)
    if (import) check(model.import() != null)
    check(controller.popBackStack())
    withTimeout(10000) { while (!job.isCancelled) delay(20) }
    AppPaths.navigate(controller, "/home")
}

private fun csvBytes(): ByteArray = buildString {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    append("Date,Description,Amount\n")
    repeat(10000) { append("${today.minus(it % 90, DateTimeUnit.DAY)},Synthetic CSV transaction $it,-15.75\n") }
}.encodeToByteArray()
