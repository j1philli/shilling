package finance.shilling.app

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.kermit.Logger
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ReceiptFileBlock
import finance.shilling.shared.data.store.StagedReceiptFileStorage
import finance.shilling.shared.data.store.Store5ReceiptFileStore
import kotlinx.coroutines.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileHandle
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.Foundation.readDataOfLength
import platform.Foundation.closeFile
import platform.Foundation.writeToFile
import platform.Foundation.writeData
import platform.Foundation.truncateFileAtOffset
import platform.UIKit.UIApplication
import platform.posix.memcpy
import platform.posix.rename

class IosIdGenerator : IdGenerator {
    override fun newId(): String = NSUUID().UUIDString()
}

fun IosReceiptFileStore(spaceId: String? = null): ReceiptFileStore {
    val storage = IosReceiptFileStorage(spaceId)
    return Store5ReceiptFileStore(storage, storage::openExternally, Dispatchers.IO)
}

@OptIn(ExperimentalForeignApi::class)
private class IosReceiptFileStorage(private val spaceId: String?) : StagedReceiptFileStorage {
    private val log = Logger.withTag("IosReceiptOpen")

    private val receiptsDir: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        )
        @Suppress("UNCHECKED_CAST")
        val documentsDir = (paths as List<String>).first()
        val folder = spaceId?.let { "receipts-spaces/${receiptSpaceFolder(it)}" } ?: "receipts"
        val receiptsPath = "$documentsDir/$folder"
        NSFileManager.defaultManager.createDirectoryAtPath(
            receiptsPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        @Suppress("UNCHECKED_CAST")
        val entries = NSFileManager.defaultManager.contentsOfDirectoryAtPath(receiptsPath, error = null) as? List<String>
        entries?.filter { it.matches(Regex("\\.incoming-[0-9a-f]{1,16}")) }?.forEach { name ->
            NSFileManager.defaultManager.removeItemAtPath("$receiptsPath/$name", error = null)
        }
        receiptsPath
    }

    private fun pathForReceipt(receiptId: String): String = "$receiptsDir/${finance.shilling.shared.data.store.safeReceiptStorageId(receiptId)}"

    private fun stagePath(token: String): String {
        require(token.matches(Regex("[0-9a-f]{1,16}")))
        return "$receiptsDir/.incoming-$token"
    }

    override suspend fun beginStage(token: String, size: Long) {
        require(size > 0)
        val path = stagePath(token)
        val manager = NSFileManager.defaultManager
        check(!manager.fileExistsAtPath(path) && manager.createFileAtPath(path, contents = null, attributes = null)) {
            "Unable to create receipt stage"
        }
        val output = NSFileHandle.fileHandleForWritingAtPath(path) ?: error("Unable to open receipt stage")
        try { output.truncateFileAtOffset(size.toULong()) } finally { output.closeFile() }
    }

    override suspend fun writeStage(token: String, offset: Long, block: ReceiptFileBlock) {
        require(offset >= 0 && block.size > 0)
        val output = NSFileHandle.fileHandleForWritingAtPath(stagePath(token)) ?: error("Receipt stage missing")
        try {
            require(offset <= output.seekToEndOfFile().toLong() - block.size)
            output.seekToFileOffset(offset.toULong())
            val data = block.bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(block.offset), block.size.toULong())
            }
            output.writeData(data)
        } finally { output.closeFile() }
    }

    override suspend fun commitStage(token: String, receiptId: String) {
        require(receiptId != "." && receiptId != ".." && '/' !in receiptId)
        check(rename(stagePath(token), pathForReceipt(receiptId)) == 0) { "Unable to commit receipt stage" }
    }

    override suspend fun abortStage(token: String) {
        NSFileManager.defaultManager.removeItemAtPath(stagePath(token), error = null)
    }

    override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
        val destPath = pathForReceipt(receiptId)
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(if (bytes.isEmpty()) null else pinned.addressOf(0), bytes.size.toULong())
        }
        val wrote = data.writeToFile(destPath, atomically = true)
        if (wrote) {
            log.i { "Stored file bytes: id=$receiptId name=$fileName bytes=${bytes.size} path=$destPath" }
        } else {
            log.w { "Store failed: id=$receiptId name=$fileName bytes=${bytes.size} path=$destPath" }
        }
    }

    override suspend fun read(receiptId: String): ByteArray? {
        val path = pathForReceipt(receiptId)
        val data = NSData.dataWithContentsOfFile(path) ?: run {
            log.d { "Read miss: id=$receiptId path=$path" }
            return null
        }
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        log.d { "Read hit: id=$receiptId bytes=${bytes.size} path=$path" }
        return bytes
    }

    override suspend fun size(receiptId: String): Long? {
        val input = NSFileHandle.fileHandleForReadingAtPath(pathForReceipt(receiptId)) ?: return null
        return try { input.seekToEndOfFile().toLong() } finally { input.closeFile() }
    }

    override suspend fun readRange(receiptId: String, offset: Long, byteCount: Int): ByteArray? {
        require(offset >= 0 && byteCount > 0)
        val input = NSFileHandle.fileHandleForReadingAtPath(pathForReceipt(receiptId)) ?: return null
        return try {
            input.seekToFileOffset(offset.toULong())
            val data = input.readDataOfLength(byteCount.toULong())
            ByteArray(data.length.toInt()).also { bytes ->
                if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), data.bytes, data.length)
                }
            }
        } finally { input.closeFile() }
    }

    override suspend fun hasFile(receiptId: String): Boolean {
        val path = pathForReceipt(receiptId)
        val exists = NSFileManager.defaultManager.fileExistsAtPath(path)
        log.d { "Has file: id=$receiptId exists=$exists path=$path" }
        return exists
    }

    override suspend fun delete(receiptId: String) {
        val path = pathForReceipt(receiptId)
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        log.d { "Deleted file bytes: id=$receiptId path=$path" }
    }

    override suspend fun clearAll() {
        NSFileManager.defaultManager.removeItemAtPath(receiptsDir, error = null)
        NSFileManager.defaultManager.createDirectoryAtPath(
            receiptsDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    suspend fun openExternally(receiptId: String, originalName: String, bytes: ByteArray) {
        log.i { "Open requested: id=$receiptId, name=$originalName" }
        runCatching {
            val fileUrl = withContext(Dispatchers.IO) {
                val data = bytes.usePinned { pinned ->
                    NSData.dataWithBytes(if (bytes.isEmpty()) null else pinned.addressOf(0), bytes.size.toULong())
                }
                val safeName = originalName.substringAfterLast('/').substringAfterLast('\\')
                    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "$receiptId.bin"
                val scope = spaceId?.let(::receiptSpaceFolder) ?: "legacy"
                val safeId = finance.shilling.shared.data.store.safeReceiptStorageId(receiptId)
                val tempDir = "${NSTemporaryDirectory().trimEnd('/')}/receipts/$scope/$safeId"
                NSFileManager.defaultManager.createDirectoryAtPath(tempDir, true, null, null)
                val tempPath = "$tempDir/$safeName"
                check(data.writeToFile(tempPath, atomically = true)) { "Unable to prepare receipt for sharing" }
                NSURL.fileURLWithPath(tempPath)
            }
            val app = UIApplication.sharedApplication
            if (!app.canOpenURL(fileUrl)) {
                log.w { "Open failed: cannot open URL for id=$receiptId url=$fileUrl" }
                return
            }
            app.openURL(fileUrl)
            log.i { "Open dispatched to iOS for id=$receiptId url=$fileUrl" }
        }.onFailure { t ->
            log.e(t) { "Open failed with exception: id=$receiptId, name=$originalName" }
        }
    }
}

private val noOpSchema = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = finance.shilling.shared.db.ShillingDatabase.Schema.version
    override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion
    ) = QueryResult.Value(Unit)
}

fun provideNativeDriver(): SqlDriver {
    return NativeSqliteDriver(noOpSchema, "shilling.db")
}

private fun receiptSpaceFolder(id: String): String = id.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
