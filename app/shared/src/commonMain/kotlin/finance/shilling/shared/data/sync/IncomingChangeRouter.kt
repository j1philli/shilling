package finance.shilling.shared.data.sync

import co.touchlab.kermit.Logger
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.ApplyIncomingChangeResult
import finance.shilling.shared.data.store.SyncStoreFacade
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Logger.withTag("Router")

class IncomingChangeRouter(
    private val storeFacade: SyncStoreFacade,
    private val notifier: ChangeNotifier,
    private val webRtcManager: PeerSyncManager,
    private val fileTransferManager: FileTransferManager? = null,
    private val fileStore: ReceiptFileStore? = null,
    private val startupScanDelayMs: Long = 5_000L,
    private val fileRetryIntervalMs: Long = 30_000L,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val deviceId: String = "",
    private val idGenerator: IdGenerator? = null
) {
    private val processedIds = mutableSetOf<String>()
    private val pendingMutex = Mutex()
    private val pendingFileReceipts = mutableMapOf<String, String>() // receiptId → originalName
    private val inProgressSends = mutableSetOf<String>()

    /**
     * @param timerContext optional dispatcher for timer-based coroutines (file retry).
     *   On iOS, pass [Dispatchers.Main] to avoid thread starvation from blocking WebRTC native calls.
     */
    fun start(scope: CoroutineScope, timerContext: CoroutineContext = EmptyCoroutineContext) {
        // Listen for entity sync messages delivered over P2P WebRTC data channels.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            log.i { "[ROUTER] Listening for incoming changes from P2P data channels..." }
            webRtcManager.incomingChanges.collect { change ->
                try {
                    if (change.id in processedIds) {
                        log.d { "[ROUTER] Skipping duplicate change ${change.id}" }
                        return@collect
                    }
                    processedIds.add(change.id)
                    if (processedIds.size > 10_000) {
                        val toRemove = processedIds.take(5_000)
                        processedIds.removeAll(toRemove.toSet())
                    }
                    log.i { "[ROUTER] Applying ${change.entityType}/${change.op} entity=${change.entityId} from device=${change.deviceId}" }
                    applyChange(change)
                    notifier.notifyChanged()
                    log.i { "[ROUTER] Applied ${change.entityType}/${change.op} entity=${change.entityId} OK" }
                } catch (e: Exception) {
                    log.e { "[ROUTER] FAILED to apply ${change.entityType}/${change.op} entity=${change.entityId}: ${e::class.simpleName}: ${e.message}" }
                }
            }
        }

        // Listen for file transfer messages
        if (fileTransferManager != null && fileStore != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                webRtcManager.incomingFileMessages.collect { (peerId, msg) ->
                    try {
                        handleFileMessage(peerId, msg)
                    } catch (e: Exception) {
                        log.e { "handleFileMessage failed from $peerId (${msg::class.simpleName}): ${e::class.simpleName}: ${e.message}" }
                    }
                }
            }
            // Event-driven file scan: when a peer connects, immediately scan for missing files.
            // This replaces the old 5s startup delay which relied on iosDelay and caused
            // thread pool starvation on K/N iOS.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                webRtcManager.peerConnected.collect { peerId ->
                    log.i { "Peer $peerId connected — sending full state + scanning for missing receipt files" }
                    sendFullStateToPeer(peerId)
                    scanAndRequestMissingFiles()
                }
            }
            // Scan for receipts missing file bytes (from previous sessions) + periodic retry
            scope.launch(timerContext) {
                delayFn(startupScanDelayMs) // let P2P channels establish
                scanAndRequestMissingFiles()
                while (isActive) {
                    delayFn(fileRetryIntervalMs)
                    retryPendingFileRequests()
                }
            }
        }
    }

    private suspend fun sendFullStateToPeer(peerId: String) {
        if (idGenerator == null || deviceId.isBlank()) {
            log.w { "[ROUTER] Cannot send full state: missing deviceId or idGenerator" }
            return
        }
        try {
            val snapshot = storeFacade.buildFullStateSnapshot(deviceId)
            snapshot.changes.forEach { change ->
                webRtcManager.sendToPeer(peerId, change)
            }
            log.i {
                "[ROUTER] Full state sent to $peerId: ${snapshot.changes.size} entities " +
                    "(${snapshot.accountCount} accounts, ${snapshot.categoryCount} categories, " +
                    "${snapshot.scheduleCount} schedules, ${snapshot.exceptionCount} exceptions, " +
                    "${snapshot.postingCount} postings, ${snapshot.receiptCount} receipts)"
            }
        } catch (e: Exception) {
            log.e { "[ROUTER] Full state sync failed for $peerId: ${e::class.simpleName}: ${e.message}" }
        }
    }

    private suspend fun scanAndRequestMissingFiles() {
        try {
            val missing = storeFacade.findReceiptsMissingFiles { receiptId ->
                fileStore?.hasFile(receiptId) == true
            }
            if (missing.isNotEmpty()) {
                pendingMutex.withLock { pendingFileReceipts.putAll(missing) }
                log.i { "Found ${missing.size} receipt(s) missing file bytes" }
            }
        } catch (e: Exception) {
            log.w { "Receipt scan failed: ${e::class.simpleName}: ${e.message}" }
        }
        retryPendingFileRequests()
    }

    private suspend fun retryPendingFileRequests() {
        val snapshot = pendingMutex.withLock { pendingFileReceipts.toMap() }
        if (snapshot.isEmpty()) return
        val toRemove = mutableListOf<String>()
        for ((receiptId, _) in snapshot) {
            // Already have the file locally
            if (fileStore?.hasFile(receiptId) == true) {
                toRemove.add(receiptId)
                continue
            }
            // Receipt no longer exists in DB (deleted) — stop requesting
            if (!storeFacade.receiptExists(receiptId)) {
                log.d { "Removing pending file request for deleted receipt: $receiptId" }
                toRemove.add(receiptId)
            }
        }
        if (toRemove.isNotEmpty()) {
            pendingMutex.withLock { toRemove.forEach { pendingFileReceipts.remove(it) } }
        }
        val remaining = pendingMutex.withLock { pendingFileReceipts.toMap() }
        if (remaining.isEmpty()) return
        val preview = remaining.keys.take(3)
        log.i {
            "Retrying file requests for ${remaining.size} receipt(s)" +
                if (preview.isEmpty()) "" else ": ${preview.joinToString(",")}${if (remaining.size > preview.size) ",..." else ""}"
        }
        for ((receiptId, _) in remaining) {
            webRtcManager.broadcastFileMessage(FileTransferMessage.FileRequest(receiptId))
        }
    }

    private suspend fun handleFileMessage(peerId: String, msg: FileTransferMessage) {
        val ftm = fileTransferManager ?: return
        when (msg) {
            is FileTransferMessage.FileRequest -> {
                // Dedup: skip if we're already sending this file (duplicate from P2P + signaling)
                if (msg.receiptId in inProgressSends) {
                    log.d { "FileRequest from $peerId: id=${msg.receiptId} — already sending, skipping duplicate" }
                    return
                }
                val hasFile = fileStore?.hasFile(msg.receiptId) == true
                val receipt = storeFacade.receiptById(msg.receiptId)
                log.d { "FileRequest from $peerId: id=${msg.receiptId} hasFile=$hasFile inDb=${receipt != null}" }
                if (hasFile) {
                    val messages = ftm.prepareTransfer(
                        msg.receiptId,
                        receipt?.originalName ?: "file"
                    )
                    if (messages != null) {
                        inProgressSends.add(msg.receiptId)
                        log.i { "Sending file ${msg.receiptId} to $peerId (${messages.size} messages)" }
                        var sentCount = 0
                        try {
                            for ((i, m) in messages.withIndex()) {
                                webRtcManager.sendFileMessage(peerId, m)
                                sentCount++
                                // Yield between chunks to avoid flooding the data channel buffer
                                if (m is FileTransferMessage.FileChunk && i % 5 == 4) {
                                    kotlinx.coroutines.yield()
                                }
                            }
                        } finally {
                            inProgressSends.remove(msg.receiptId)
                        }
                        log.i { "File send complete: ${msg.receiptId} ($sentCount/${messages.size} messages sent)" }
                    } else {
                        log.w { "prepareTransfer returned null for ${msg.receiptId} (hasFile=true but read failed)" }
                        webRtcManager.sendFileMessage(
                            peerId,
                            FileTransferMessage.FileNotAvailable(msg.receiptId)
                        )
                    }
                } else {
                    log.d { "FileNotAvailable: id=${msg.receiptId} (hasFile=false)" }
                    webRtcManager.sendFileMessage(
                        peerId,
                        FileTransferMessage.FileNotAvailable(msg.receiptId)
                    )
                }
            }
            is FileTransferMessage.FileHeader -> {
                log.d { "FileHeader: id=${msg.receiptId} size=${msg.totalSize} chunks=${msg.chunkCount}" }
                ftm.handleHeader(msg)
            }
            is FileTransferMessage.FileChunk -> {
                ftm.handleChunk(msg)
            }
            is FileTransferMessage.FileComplete -> {
                val receipt = storeFacade.receiptById(msg.receiptId)
                val ok = ftm.finalizeTransfer(
                    msg.receiptId,
                    receipt?.originalName ?: "file"
                )
                if (ok) {
                    pendingMutex.withLock { pendingFileReceipts.remove(msg.receiptId) }
                    log.i { "File transfer complete: id=${msg.receiptId}" }
                } else {
                    log.w { "File transfer finalize failed: id=${msg.receiptId}" }
                }
                notifier.notifyChanged()
            }
            is FileTransferMessage.FileNotAvailable -> {
                val exists = storeFacade.receiptExists(msg.receiptId)
                val hasLocal = fileStore?.hasFile(msg.receiptId) == true
                if (!exists || hasLocal) {
                    log.d { "FileNotAvailable received for ${msg.receiptId}, removing from pending (exists=$exists hasLocal=$hasLocal)" }
                    pendingMutex.withLock { pendingFileReceipts.remove(msg.receiptId) }
                } else {
                    log.w { "FileNotAvailable received for ${msg.receiptId} but receipt still exists locally; keeping pending for retry" }
                }
                ftm.cancelTransfer(msg.receiptId)
            }
        }
    }

    private suspend fun applyChange(change: ChangeMessage) {
        when (val result = storeFacade.applyIncomingChange(change)) {
            is ApplyIncomingChangeResult.IgnoredStale -> {
                val current = result.current
                log.i {
                    "[ROUTER] Ignoring stale ${change.entityType}/${change.op} entity=${change.entityId} " +
                        "(incoming ts=${change.timestamp}, current ts=${current?.timestamp}, " +
                        "incoming id=${change.id}, current id=${current?.changeId})"
                }
                return
            }
            ApplyIncomingChangeResult.Applied -> {
                if (change.entityType == EntityType.RECEIPT) {
                    handleReceiptPrefetch(change)
                }
            }
        }
    }

    private suspend fun handleReceiptPrefetch(change: ChangeMessage) {
        when (change.op) {
            ChangeOp.UPSERT -> {
                val receipt = (change.payload as? ChangePayload.ReceiptPayload)?.receipt ?: return
                val hasLocal = fileStore?.hasFile(receipt.id) ?: true
                log.i { "Receipt ${receipt.id} hasLocal=$hasLocal fileStore=${fileStore != null}" }
                if (!hasLocal) {
                    pendingMutex.withLock { pendingFileReceipts[receipt.id] = receipt.originalName }
                    log.i { "Auto-prefetch: requesting file bytes for ${receipt.id}" }
                    webRtcManager.broadcastFileMessage(FileTransferMessage.FileRequest(receipt.id))
                }
            }
            ChangeOp.DELETE -> {
                pendingMutex.withLock { pendingFileReceipts.remove(change.entityId) }
                fileStore?.delete(change.entityId)
            }
        }
    }
}
