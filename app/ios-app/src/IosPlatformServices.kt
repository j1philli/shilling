package finance.shilling.app

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.kermit.Logger
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.posix.memcpy

class IosIdGenerator : IdGenerator {
    override fun newId(): String = NSUUID().UUIDString()
}

@OptIn(ExperimentalForeignApi::class)
class IosReceiptFileStore : ReceiptFileStore {
    private val log = Logger.withTag("IosReceiptOpen")

    private val receiptsDir: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        )
        @Suppress("UNCHECKED_CAST")
        val documentsDir = (paths as List<String>).first()
        val receiptsPath = "$documentsDir/receipts"
        NSFileManager.defaultManager.createDirectoryAtPath(
            receiptsPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        receiptsPath
    }

    private fun pathForReceipt(receiptId: String): String = "$receiptsDir/$receiptId"

    override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
        val destPath = pathForReceipt(receiptId)
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
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
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        log.d { "Read hit: id=$receiptId bytes=${bytes.size} path=$path" }
        return bytes
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

    override suspend fun openExternally(receiptId: String, originalName: String) {
        log.i { "Open requested: id=$receiptId, name=$originalName" }
        runCatching {
            val data = NSData.dataWithContentsOfFile(pathForReceipt(receiptId))
            if (data == null) {
                log.w { "Open failed: file bytes missing for id=$receiptId" }
                return
            }
            val safeName = originalName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { "$receiptId.bin" }
            val tempDir = NSTemporaryDirectory().trimEnd('/')
            val tempPath = "$tempDir/$receiptId-$safeName"
            data.writeToFile(tempPath, atomically = true)
            val fileUrl = NSURL.fileURLWithPath(tempPath)
            val app = UIApplication.sharedApplication
            if (!app.canOpenURL(fileUrl)) {
                log.w { "Open failed: cannot open URL for id=$receiptId path=$tempPath" }
                return
            }
            app.openURL(fileUrl)
            log.i { "Open dispatched to iOS for id=$receiptId path=$tempPath" }
        }.onFailure { t ->
            log.e(t) { "Open failed with exception: id=$receiptId, name=$originalName" }
        }
    }
}

private val noOpSchema = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1
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
