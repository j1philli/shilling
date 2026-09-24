package finance.shilling.web

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import co.touchlab.kermit.Logger
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class WasmIdGenerator : IdGenerator {
    override fun newId(): String = Uuid.random().toString()
}

class WasmReceiptFileStore(private val db: ShillingDatabase) : ReceiptFileStore {
    private val log = Logger.withTag("WebReceiptOpen")

    override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
        val mimeType = mimeTypeForName(fileName)
        db.receiptFileQueries.upsert(receiptId, bytes, bytes.size.toLong(), mimeType)
        log.i { "Stored file bytes: id=$receiptId name=$fileName bytes=${bytes.size} mime=$mimeType" }
    }

    override suspend fun read(receiptId: String): ByteArray? {
        val row = db.receiptFileQueries.selectByReceiptId(receiptId).awaitAsOneOrNull()
        if (row == null) {
            log.d { "Read miss: id=$receiptId" }
            return null
        }
        log.d { "Read hit: id=$receiptId bytes=${row.file_bytes.size}" }
        return row.file_bytes
    }

    override suspend fun hasFile(receiptId: String): Boolean {
        val count = db.receiptFileQueries.hasFile(receiptId).awaitAsOneOrNull() ?: 0L
        val exists = count > 0
        log.d { "Has file: id=$receiptId exists=$exists" }
        return exists
    }

    override suspend fun delete(receiptId: String) {
        db.receiptFileQueries.deleteByReceiptId(receiptId)
        log.d { "Deleted file bytes: id=$receiptId" }
    }

    override suspend fun clearAll() {
        db.receiptFileQueries.deleteAll()
        log.d { "Cleared all receipt file bytes" }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun openExternally(receiptId: String, originalName: String) {
        log.i { "Open requested: id=$receiptId, name=$originalName" }
        runCatching {
            val row = db.receiptFileQueries.selectByReceiptId(receiptId).awaitAsOneOrNull()
            if (row == null) {
                log.w { "Open failed: file bytes missing for id=$receiptId" }
                return
            }
            val mimeType = row.mime_type ?: mimeTypeForName(originalName)
            val encodedBytes = Base64.Default.encode(row.file_bytes)

            if (originalName.isHeifFamilyName()) {
                val downloadName = originalName.ifBlank { "$receiptId.heic" }
                val downloadUrl = "data:application/octet-stream;base64,$encodedBytes"
                val link = document.createElement("a") as HTMLAnchorElement
                link.href = downloadUrl
                link.download = downloadName
                document.body?.appendChild(link)
                link.click()
                link.parentNode?.removeChild(link)
                log.i { "HEIC/HEIF download dispatched for id=$receiptId as $downloadName" }
                return
            }

            val dataUrl = "data:$mimeType;base64,$encodedBytes"
            val popup = window.open(dataUrl, "_blank")
            if (popup != null) {
                log.i { "Open succeeded in new tab for id=$receiptId" }
                return
            }
            log.e { "Popup blocked for id=$receiptId — enable popups in Tauri or use the in-app preview" }
        }.onFailure { t ->
            log.e(t) { "Open failed with exception: id=$receiptId, name=$originalName" }
        }
    }

    private fun mimeTypeForName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "bin")
        return when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> "application/octet-stream"
        }
    }

    private fun String.isHeifFamilyName(): Boolean {
        val ext = substringAfterLast('.', "").lowercase()
        return ext == "heic" || ext == "heif"
    }
}
