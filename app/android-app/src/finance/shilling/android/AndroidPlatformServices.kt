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
import finance.shilling.shared.data.store.AndroidSqliteDriver
import finance.shilling.shared.data.store.DirectoryReceiptFileStorage
import finance.shilling.shared.data.store.RangedReceiptFileStorage
import finance.shilling.shared.data.store.Store5ReceiptFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AndroidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
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

fun provideAndroidDriver(context: Context): SqlDriver =
    AndroidSqliteDriver(noOpSchema, context, "shilling.db")

fun AndroidReceiptFileStore(context: Context, spaceId: String? = null): ReceiptFileStore {
    val storage = AndroidReceiptFileStorage(context, spaceId)
    return Store5ReceiptFileStore(storage, storage::openExternally, Dispatchers.IO)
}

private class AndroidReceiptFileStorage(private val context: Context, private val spaceId: String?) : RangedReceiptFileStorage by
    DirectoryReceiptFileStorage(File(context.filesDir, spaceId?.let { "receipts-spaces/${receiptSpaceFolder(it)}" } ?: "receipts")) {
    private val log = Logger.withTag("AndroidReceiptStore")

    suspend fun openExternally(receiptId: String, originalName: String, bytes: ByteArray) {
        runCatching {
            val tempFile = withContext(Dispatchers.IO) {
                val safeName = originalName.substringAfterLast('/').substringAfterLast('\\')
                    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "$receiptId.bin"
                val scope = spaceId?.let(::receiptSpaceFolder) ?: "legacy"
                val safeId = finance.shilling.shared.data.store.safeReceiptStorageId(receiptId)
                val cacheDir = File(context.cacheDir, "receipts_share/$scope/$safeId").also { it.mkdirs() }
                File(cacheDir, safeName).also { it.writeBytes(bytes) }
            }

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

private fun receiptSpaceFolder(id: String): String = id.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
