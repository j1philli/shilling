package finance.shilling.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import co.touchlab.kermit.Logger
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import java.io.File
import java.util.UUID

class AndroidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
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

fun provideAndroidDriver(context: Context): SqlDriver =
    AndroidSqliteDriver(noOpSchema, context, "shilling.db")

class AndroidReceiptFileStore(private val context: Context) : ReceiptFileStore {
    private val log = Logger.withTag("AndroidReceiptStore")

    private val receiptsDir: File by lazy {
        File(context.filesDir, "receipts").also { it.mkdirs() }
    }

    private fun fileForReceipt(receiptId: String): File = File(receiptsDir, receiptId)

    override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
        val dest = fileForReceipt(receiptId)
        dest.writeBytes(bytes)
        log.i { "Stored receipt id=$receiptId name=$fileName bytes=${bytes.size}" }
    }

    override suspend fun read(receiptId: String): ByteArray? {
        val file = fileForReceipt(receiptId)
        if (!file.exists()) return null
        return file.readBytes()
    }

    override suspend fun hasFile(receiptId: String): Boolean =
        fileForReceipt(receiptId).exists()

    override suspend fun delete(receiptId: String) {
        fileForReceipt(receiptId).delete()
    }

    override suspend fun clearAll() {
        receiptsDir.listFiles()?.forEach(File::delete)
    }

    override suspend fun openExternally(receiptId: String, originalName: String) {
        runCatching {
            val sourceFile = fileForReceipt(receiptId)
            if (!sourceFile.exists()) {
                log.w { "Open failed: file missing for id=$receiptId" }
                return
            }
            val safeName = originalName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { "$receiptId.bin" }
            val cacheDir = File(context.cacheDir, "receipts_share").also { it.mkdirs() }
            val tempFile = File(cacheDir, safeName)
            sourceFile.copyTo(tempFile, overwrite = true)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(originalName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { t ->
            log.e(t) { "Open failed: id=$receiptId, name=$originalName" }
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "bin").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> "application/octet-stream"
        }
    }
}
