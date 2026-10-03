package finance.shilling.perf

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
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
import kotlinx.datetime.*
import org.koin.core.Koin
import org.koin.dsl.module
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
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
    var status by mutableStateOf("Preparing synthetic database…")
    var ready by mutableStateOf(false)
    lateinit var koin: Koin
    MainScope().launch {
        runCatching {
            val driver = WebWorkerDriver(createDatabaseWorker("sqldelight.worker.js"))
            ensureLocalSchemaReady(driver)
            val db = ShillingDatabase(driver)
            val settings = Settings()
            completeFirstLaunchOnboarding(settings, DeploymentSelection.SELF_HOSTED, SERVER)
            koin = initKoin(module {
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
            val csvOnly = document.querySelector("meta[name=shilling-memory-workload]")?.getAttribute("content") == "csv"
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
            model.loadFile("synthetic-memory-10000.csv", csvBytes())
            withTimeout(60000) { model.state.first { it.rows.size == 10000 && it.importEnabled } }
            mark("READY csv_review_10000")
            delay(15000)
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

private fun csvBytes(): ByteArray = buildString {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    append("Date,Description,Amount\n")
    repeat(10000) { append("${today.minus(it % 90, DateTimeUnit.DAY)},Synthetic CSV transaction $it,-15.75\n") }
}.encodeToByteArray()
