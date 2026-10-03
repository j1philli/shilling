package finance.shilling.app

import co.touchlab.kermit.Logger
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.webrtc.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.UIKit.*
import kotlin.time.TimeSource

/** Isolated app sandbox; ios-platform reuses the production iOS adapters exactly. */
@OptIn(ExperimentalForeignApi::class)
fun PerformanceViewController(): UIViewController {
    check(NSBundle.mainBundle.bundleIdentifier == "finance.shilling.perf")
    UIApplication.sharedApplication.idleTimerDisabled = true
    val label = UILabel().apply {
        text = "Preparing synthetic WebRTC receipts…"
        numberOfLines = 0
        textAlignment = NSTextAlignmentCenter
        backgroundColor = UIColor.systemBackgroundColor
        textColor = UIColor.labelColor
    }
    val controller = UIViewController().apply { view = label }
    PerformancePeer.start(label)
    return controller
}

private object PerformancePeer {
    // Match AppBootstrap's production sync dispatcher.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalForeignApi::class)
    fun start(label: UILabel) {
        // Avoid OSLogWriter's private output and use a non-OSLog sink, like the
        // production iOS app. Keep diagnostics visible in devicectl's console.
        Logger.setLogWriters(object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                println("[$tag] $severity: $message")
                throwable?.printStackTrace()
            }
        })
        Logger.setMinSeverity(Severity.Info)
        val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
        fun argument(name: String, default: String): String =
            arguments.indexOf(name).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) } ?: default
        val server = argument("--perf-server", "")
        val receiver = argument("--perf-receiver", "adapter")
        require(server.startsWith("ws://")) { "Pass --perf-server ws://<Mac LAN address>:8081" }
        require(receiver in listOf("adapter", "ktor"))
        scope.launch {
            try {
                val schema = object : app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>> {
                    override val version = ShillingDatabase.Schema.version
                    override fun create(driver: app.cash.sqldelight.db.SqlDriver) = app.cash.sqldelight.db.QueryResult.Value(Unit)
                    override fun migrate(driver: app.cash.sqldelight.db.SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: app.cash.sqldelight.db.AfterVersion) = app.cash.sqldelight.db.QueryResult.Value(Unit)
                }
                val driver = app.cash.sqldelight.driver.native.NativeSqliteDriver(schema, "p2p-synthetic.db")
                ensureLocalSchemaReady(driver, logTag = "iOS-Perf")
                val db = ShillingDatabase(driver)
                val notifier = ChangeNotifier()
                val files = IosReceiptFileStore()
                files.clearAll() // Only finance.shilling.perf's sandbox.
                val receipts = ReceiptRepository(notifier, createReceiptStore(db))
                for (mib in listOf(1, 10, 50)) {
                    val id = "p2p-$mib"
                    withContext(Dispatchers.Default) {
                        files.store(id, "synthetic.bin", ByteArray(mib * 1024 * 1024) { (it % 251).toByte() })
                    }
                    receipts.save(Receipt(id, filePath = id, originalName = "synthetic.bin", addedAt = 0))
                }
                val client = HttpClient(Darwin) { install(WebSockets) }
                val signal = SignalingClient(client, server, "z-iphone", "synthetic-live-perf")
                val rtcClient = WebRtcClient(IosWebRtc) { defaultConnectionConfig = { iceServers = emptyList() } }
                val status = PeerConnectionStatus()
                scope.launch { status.connectedPeerIds.collect { println("SHILLING_PEERS iphone=$it") } }
                val manager = if (receiver == "adapter") {
                    WebRtcConnectionManager(rtcClient, signal, "z-iphone", status,
                        receiveMessageFn = iosReceiveMessage, onChannelOpen = nativeChannelOpen, delayFn = iosDelay)
                } else {
                    WebRtcConnectionManager(rtcClient, signal, "z-iphone", status, delayFn = iosDelay)
                }
                val timed = object : PeerSyncManager by manager {
                    override suspend fun sendFileMessages(peerId: String, messages: Flow<FileTransferMessage>): Int {
                        val started = TimeSource.Monotonic.markNow()
                        val count = manager.sendFileMessages(peerId, messages)
                        println("SHILLING_PERF {\"stage\":\"send\",\"messages\":$count,\"milliseconds\":${started.elapsedNow().inWholeMicroseconds / 1000.0}}")
                        return count
                    }
                }
                IncomingChangeRouter(
                    SyncStoreFacade(db), notifier, timed, FileTransferManager(files), files,
                    startupScanDelayMs = 0, fileRetryIntervalMs = 2_000, deviceId = "z-iphone", delayFn = iosDelay,
                    hasConnectedPeers = { status.connectedPeerIds.value.isNotEmpty() }
                ).start(scope)
                manager.start(scope)
                signal.connect(scope)
                withContext(Dispatchers.Main) {
                    label.text = "Shilling synthetic WebRTC peer ready.\nReceiver: $receiver"
                }
                println("SHILLING_PERF {\"status\":\"ready\",\"receiver\":\"$receiver\"}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main) { label.text = "Benchmark failed: ${failure.message}" }
                println("SHILLING_PERF failed: $failure")
                failure.printStackTrace()
            }
        }
    }
}
