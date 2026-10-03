@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)

package finance.shilling.app

import app.cash.sqldelight.db.*
import app.cash.sqldelight.Query
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.auth.*
import finance.shilling.shared.data.sync.createSyncHttpClient
import finance.shilling.shared.session.*
import finance.shilling.shared.db.ShillingDatabase
import finance.shilling.shared.presentation.today
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.*
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import platform.Foundation.*

internal fun isDebugBuild(): Boolean = false

/** Synthetic Store5 graph in the perf sandbox; hosted mode adds local control-plane metadata. */
object NativeUiFixture {
    private lateinit var metrics: UiQueryDriver

    @NativeCoroutines
    suspend fun seed(): Unit = withContext(Dispatchers.Default) {
        check(NSBundle.mainBundle.bundleIdentifier == "finance.shilling.perf")
        Logger.setMinSeverity(Severity.Error)
        metrics = UiQueryDriver(provideNativeDriver())
        ensureLocalSchemaReady(metrics)
        val settings = Settings()
        settings.putBoolean("posthog_consent", false)
        settings.putBoolean("posthog_development_consent", false)
        val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
        val hostedToken = args.indexOf("--ui-hosted-token").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: "synthetic-token"
        val hostedUrl = args.indexOf("--ui-hosted-server").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        if (hostedUrl != null) {
            require(hostedUrl.startsWith("http://")) { "Use the local synthetic hosted fixture" }
            settings.putString(SETTINGS_KEY_SERVER_URL, hostedUrl)
        }
        val graph = initKoin(module {
            single { ShillingDatabase(metrics) }
            single { settings }
            single<IdGenerator> { IosIdGenerator() }
            single<ReceiptFileStoreFactory> { ReceiptFileStoreFactory { space, legacy -> IosReceiptFileStore(if (legacy) null else space) } }
            single { finance.shilling.shared.presentation.SpaceSelectionCallback { error("Fixture space selection disabled") } }
            single { createSyncHttpClient() }
            single { CloudRelayCallback { settings.putBoolean(SETTINGS_KEY_CLOUD_RELAY_ENABLED, it) } }
            single<SessionState> {
                val auth = if (hostedUrl == null) NoOpAuthService("synthetic-device") else
                    object : AuthService by NoOpAuthService("synthetic-device") {
                        override val authState = MutableStateFlow(AuthState(true, "synthetic-user", null,
                            UserTier.ANONYMOUS, hostedToken, true, "synthetic-device"))
                        override suspend fun refreshTokenIfNeeded(): String = hostedToken
                    }
                object : SessionState {
                    override val phase = MutableStateFlow<SessionPhase>(SessionPhase.Ready(auth,
                        FeatureGate(auth, hostedUrl == null), hostedUrl == null))
                }
            }
            single { HostedBootstrapState(MutableStateFlow(HostedBootstrapStatus(HostedBootstrapPhase.READY))) }
            single { HostedBootstrapRetryCallback { } }
            single { ResetOnboardingCallback { error("Fixture reset disabled") } }
            single { RestartHostedLoginCallback { error("Fixture login disabled") } }
            single { ServerUrlCallback { error("Fixture network disabled") } }
            single { HouseholdIdCallback { error("Fixture network disabled") } }
        })
        val day = today()
        val seedVersion = "native-ui-spaces-2-$day"
        if (settings.getStringOrNull("native-ui-seed") != seedVersion) {
            graph.get<LocalDataWiper>().wipe()
            val accounts = graph.get<AccountRepository>()
            val categories = graph.get<CategoryRepository>()
            repeat(10) { accounts.upsert(Account("ui-account-$it", "Synthetic account $it", 1000.0)) }
            repeat(40) { categories.upsert(Category("ui-category-$it", "Synthetic category $it", "#4477AA")) }
            val schedules = graph.get<ScheduleStore>()
            List(1_000) { i -> Schedule("ui-schedule-$i", "Synthetic schedule $i", 12.34,
                ScheduleType.EXPENSE, "ui-account-${i % 10}", categoryId = "ui-category-${i % 40}",
                startDate = day, freq = Frequency.WEEKLY)
            }.chunked(200).forEach { batch ->
                schedules.write(StoreWriteRequest.of<ScheduleKey, List<Schedule>, Unit>(ScheduleKey.All, batch))
            }
            val postings = graph.get<PostingStore>()
            List(10_000) { i -> Posting("ui-posting-$i", null,
                if (i % 4 == 1) ScheduleType.INCOME else ScheduleType.EXPENSE,
                "ui-account-${i % 10}", day.minus((i / 2) % 28, DateTimeUnit.DAY), 12.34,
                pairId = if (i % 4 < 2) "ui-pair-${i / 2}" else null,
                title = "Synthetic transaction $i", categoryId = "ui-category-${i % 40}")
            }.chunked(200).forEach { batch ->
                postings.write(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(PostingKey.All, batch))
            }
            val receipts = graph.get<ReceiptStore>()
            receipts.write(StoreWriteRequest.of<ReceiptKey, List<Receipt>, Unit>(ReceiptKey.All,
                List(250) { i -> Receipt("ui-receipt-$i", if (i % 2 == 0) "ui-posting-$i" else null,
                    "synthetic", "Synthetic receipt $i.pdf", 1_790_000_000_000L + i, amount = 12.34) }))
            graph.get<ChangeNotifier>().notifyChanged()
            settings.putString("native-ui-seed", seedVersion)
        }
    }

    @NativeCoroutines
    suspend fun changePosting(index: Int): Unit = withContext(Dispatchers.Default) {
        KoinPlatform.getKoin().get<PostingRepository>().record(
            Posting("ui-probe", null, ScheduleType.EXPENSE, "ui-account-0", today(),
                index.toDouble() + 1, title = "Synthetic update $index", categoryId = "ui-category-0")
        )
    }

    fun resetQueries() = metrics.reset()
    fun queryMetrics(): String = metrics.snapshot()

    @NativeCoroutines
    suspend fun installReceipt(path: String, receiptId: String, name: String): Unit = withContext(Dispatchers.IO) {
        val source = NSFileHandle.fileHandleForReadingAtPath(path) ?: error("Missing synthetic receipt")
        val size = source.seekToEndOfFile().toLong()
        source.seekToFileOffset(0u)
        val files = KoinPlatform.getKoin().get<ReceiptFileStore>()
        val writer = files.openWriter(receiptId, size) ?: error("Missing staged writer")
        try {
            var offset = 0L
            while (offset < size) {
                val bytes = source.readDataOfLength(minOf(16_384L, size - offset).toULong()).toByteArray()
                check(bytes.isNotEmpty())
                writer.writeRange(offset, ReceiptFileBlock(bytes))
                offset += bytes.size
            }
            writer.commit(name)
            KoinPlatform.getKoin().get<ReceiptRepository>().save(
                Receipt(receiptId, null, name, name, 1_790_000_000_000L))
        } catch (failure: Throwable) {
            writer.abort()
            throw failure
        } finally { source.closeFile() }
    }

    @NativeCoroutines
    suspend fun previewReceipt(receiptId: String, name: String): String? =
        KoinPlatform.getKoin().get<ReceiptFileStore>().prepareIosReceiptPreview(receiptId, name)

    @NativeCoroutines
    suspend fun removeReceipt(receiptId: String): Unit {
        val graph = KoinPlatform.getKoin()
        graph.get<ReceiptRepository>().delete(receiptId)
        graph.get<ReceiptFileStore>().delete(receiptId)
    }
}

/** Counts query invocations and their calling thread, without recording SQL values or data. */
private class UiQueryDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    private val lock = NSLock()
    private var total = 0
    private var onMain = 0
    private val tables = mutableMapOf<String, Int>()
    private val rows = mutableMapOf<String, Int>()
    private val listeners = mutableSetOf<Pair<String, Query.Listener>>()
    private val tablePattern = Regex("(?i)\\bFROM\\s+([a-z_]+)")

    override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
        val table = tablePattern.find(sql)?.groupValues?.get(1)?.lowercase() ?: "other"
        lock.lock()
        try {
            total++
            if (NSThread.isMainThread) onMain++
            tables[table] = (tables[table] ?: 0) + 1
        } finally { lock.unlock() }
        return delegate.executeQuery(identifier, sql, { cursor ->
            var count = 0
            val measured = object : SqlCursor by cursor {
                override fun next(): QueryResult<Boolean> = cursor.next().also {
                    // The native fixture's SQL driver has synchronous cursors.
                    if (it.value) count++
                }
            }
            try { mapper(measured) } finally {
                lock.lock()
                try { rows[table] = (rows[table] ?: 0) + count } finally { lock.unlock() }
            }
        }, parameters, binders)
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        lock.lock()
        try { queryKeys.forEach { listeners += it to listener } } finally { lock.unlock() }
        delegate.addListener(*queryKeys, listener = listener)
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        delegate.removeListener(*queryKeys, listener = listener)
        lock.lock()
        try { queryKeys.forEach { listeners -= it to listener } } finally { lock.unlock() }
    }

    fun reset() {
        lock.lock()
        try { total = 0; onMain = 0; tables.clear(); rows.clear() } finally { lock.unlock() }
    }

    fun snapshot(): String {
        lock.lock()
        try {
            val counts = tables.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
            val readRows = rows.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
            return "{\"queries\":$total,\"mainThreadQueries\":$onMain,\"tables\":{$counts},\"rows\":{$readRows},\"activeListeners\":${listeners.size}}"
        } finally { lock.unlock() }
    }
}
