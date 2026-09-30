package finance.shilling.perf

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.TextView
import app.cash.sqldelight.db.*
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.webrtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File

/** Live P2P fixture in the benchmark sandbox. No account credentials or real budget data. */
class P2pPerformanceActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var driver: SqlDriver? = null
    private var http: HttpClient? = null
    private var signal: SignalingClient? = null
    private var rtc: WebRtcConnectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val output = TextView(this).also { it.text = "Preparing synthetic P2P receipts…"; setContentView(it) }
        Logger.setMinSeverity(Severity.Info)
        scope.launch {
            try {
                val schema = object : SqlSchema<QueryResult.Value<Unit>> {
                    override val version = 1L
                    override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
                    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Value(Unit)
                }
                deleteDatabase("p2p-synthetic.db")
                val dbDriver = AndroidSqliteDriver(schema, applicationContext, "p2p-synthetic.db")
                driver = dbDriver
                ensureLocalSchemaReady(dbDriver)
                val db = ShillingDatabase(dbDriver)
                val notifier = ChangeNotifier()
                val files = Store5ReceiptFileStore(SyntheticFiles(File(filesDir, "p2p-receipts")) { id ->
                    if (id == "browser-upload") Log.i("ShillingP2p", JSONObject()
                        .put("stage", "upload_stored").put("memory", memoryProbe()).toString())
                }, { _, _, _ -> }, Dispatchers.IO)
                val receipts = ReceiptRepository(notifier, createReceiptStore(db))
                val reuseReceipts = intent.getBooleanExtra("reuseReceipts", false)
                if (!reuseReceipts) files.clearAll()
                for (mib in listOf(1, 10, 50)) {
                    val id = "p2p-$mib"
                    if (reuseReceipts) check(files.hasFile(id)) { "Missing synthetic receipt $id" }
                    else withContext(Dispatchers.IO) {
                        files.store(id, "synthetic.bin", ByteArray(mib * 1024 * 1024) { (it % 251).toByte() })
                    }
                    receipts.save(Receipt(id, filePath = id, originalName = "synthetic.bin", addedAt = 0))
                }
                val client = HttpClient(OkHttp) { install(WebSockets) }
                http = client
                val signaling = SignalingClient(client, "ws://127.0.0.1:18081", "z-pixel", "synthetic-live-perf")
                signal = signaling
                val webRtcClient = WebRtcClient(AndroidWebRtc) {
                    context = applicationContext
                    defaultConnectionConfig = { iceServers = emptyList() }
                }
                if (intent.getBooleanExtra("sctpLog", false)) {
                    org.webrtc.PeerConnectionFactory.initialize(
                        org.webrtc.PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
                    )
                    org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_VERBOSE)
                }
                var openedChannel: WebRtcDataChannel? = null
                var pressureWaits = 0
                var pressureWaitNanos = 0L
                val manager = WebRtcConnectionManager(webRtcClient, signaling, "z-pixel", onChannelOpen = { openedChannel = it }, delayFn = { ms ->
                    val start = System.nanoTime()
                    delay(ms)
                    if (ms == 10L) {
                        pressureWaits++
                        pressureWaitNanos += System.nanoTime() - start
                    }
                })
                rtc = manager
                val timedManager = object : PeerSyncManager by manager {
                    override suspend fun sendFileMessages(peerId: String, messages: Flow<FileTransferMessage>): Int {
                        val rawMode = intent.getStringExtra("raw") ?: ""
                        if (rawMode.isNotEmpty()) {
                            // Network diagnostic only: generate the fixture's known byte pattern.
                            // Do not advance the receipt sequence into chunk/frame preparation.
                            val header = messages.first() as FileTransferMessage.FileHeader
                            val channel = checkNotNull(openedChannel)
                            val chunkSize = intent.getIntExtra("chunkSize", 16 * 1024)
                            val pollMs = intent.getLongExtra("pollMs", 10)
                            val cap = intent.getIntExtra("bufferCap", 256 * 1024)
                            check(chunkSize in listOf(16 * 1024, 64 * 1024))
                            val blocks = Array(251) { start -> ByteArray(chunkSize) { ((start + it) % 251).toByte() } }
                            val count = (header.totalSize + chunkSize - 1) / chunkSize
                            manager.sendFileMessage(peerId, header.copy(chunkCount = count))
                            var bufferNs = 0L
                            var sendNs = 0L
                            var waitNs = 0L
                            var queries = 0
                            var waits = 0
                            var upperBound = 0L
                            val cpuStart = Debug.threadCpuTimeNanos()
                            val start = System.nanoTime()
                            val gcStart = Debug.getRuntimeStats()
                            for (index in 0 until count) {
                                val waitingSince = System.nanoTime()
                                while (true) {
                                    if (rawMode != "batched" || upperBound > cap) {
                                        val queryStart = System.nanoTime()
                                        upperBound = channel.bufferedAmount
                                        bufferNs += System.nanoTime() - queryStart
                                        queries++
                                    }
                                    if (upperBound <= cap) break
                                    check(channel.state == WebRtc.DataChannel.State.OPEN) { "Raw test channel closed" }
                                    check(System.nanoTime() - waitingSince < 30_000_000_000L) { "Raw test stalled" }
                                    val waitStart = System.nanoTime()
                                    delay(pollMs)
                                    waitNs += System.nanoTime() - waitStart
                                    waits++
                                }
                                val offset = index * chunkSize
                                val block = blocks[offset % 251]
                                val remaining = header.totalSize - offset
                                val bytes = if (remaining < chunkSize) block.copyOf(remaining) else block
                                val sendStart = System.nanoTime()
                                if (rawMode == "ktor") channel.send(bytes)
                                else check(channel.getNative().send(org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), true))) { "Raw send failed" }
                                sendNs += System.nanoTime() - sendStart
                                upperBound += bytes.size
                                if (index % 5 == 4) yield()
                            }
                            manager.sendFileMessage(peerId, FileTransferMessage.FileComplete(header.receiptId))
                            val gcEnd = Debug.getRuntimeStats()
                            Log.i("ShillingP2p", JSONObject().put("rawMode", rawMode).put("chunkSize", chunkSize)
                                .put("pollMs", pollMs).put("bufferCap", cap).put("queries", queries).put("waits", waits)
                                .put("bufferQueryMs", bufferNs / 1_000_000.0).put("sendMs", sendNs / 1_000_000.0)
                                .put("waitMs", waitNs / 1_000_000.0).put("cpuMs", (Debug.threadCpuTimeNanos() - cpuStart) / 1_000_000.0)
                                .put("totalMs", (System.nanoTime() - start) / 1_000_000.0)
                                .put("gcCount", (gcEnd["art.gc.gc-count"]?.toLongOrNull() ?: 0) - (gcStart["art.gc.gc-count"]?.toLongOrNull() ?: 0))
                                .toString())
                            return count + 2
                        }
                        if (intent.getBooleanExtra("pipeline", true)) {
                            val before = System.nanoTime()
                            val count = manager.sendFileMessages(peerId, messages)
                            Log.i("ShillingP2p", JSONObject().put("pipeline", true).put("messages", count)
                                .put("totalMs", (System.nanoTime() - before) / 1_000_000.0)
                                .put("memory", memoryProbe()).toString())
                            return count
                        }
                        var count = 0
                        messages.collect { message ->
                            sendFileMessage(peerId, message)
                            count++
                            if (count % 5 == 0) yield()
                        }
                        return count
                    }
                    var sendNanos = 0L
                    var started = 0L
                    var waitStart = 0L
                    override suspend fun sendFileMessage(peerId: String, message: FileTransferMessage) {
                        if (message is FileTransferMessage.FileHeader) {
                            sendNanos = 0L
                            started = System.nanoTime()
                            waitStart = pressureWaitNanos
                        }
                        val before = System.nanoTime()
                        manager.sendFileMessage(peerId, message)
                        sendNanos += System.nanoTime() - before
                        if (message is FileTransferMessage.FileComplete) Log.i("ShillingP2p", JSONObject()
                            .put("receipt", message.receiptId).put("sendMs", sendNanos / 1_000_000.0)
                            .put("waitMs", (pressureWaitNanos - waitStart) / 1_000_000.0)
                            .put("totalMs", (System.nanoTime() - started) / 1_000_000.0).toString())
                    }
                }
                IncomingChangeRouter(
                    SyncStoreFacade(db), notifier, timedManager, FileTransferManager(files), files,
                    startupScanDelayMs = 0, fileRetryIntervalMs = 2_000, deviceId = "z-pixel"
                ).start(scope)
                manager.start(scope)
                signaling.connect(scope)
                output.text = "Synthetic WebRTC peer ready. Waiting for browser peer."
                Log.i("ShillingP2p", JSONObject().put("status", "ready").put("memory", memoryProbe()).toString())
                scope.launch {
                    while (isActive) {
                        delay(1_000)
                        Log.i("ShillingP2p", JSONObject().put("memory", memoryProbe())
                            .put("pressureWaits", pressureWaits).put("pressureWaitMs", pressureWaitNanos / 1_000_000.0)
                            .toString())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                output.text = "P2P fixture failed: ${failure.message}"
                Log.e("ShillingP2p", "Fixture failed", failure)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        rtc?.stop()
        signal?.disconnect()
        scope.cancel()
        http?.close()
        driver?.close()
        super.onDestroy()
    }
}
