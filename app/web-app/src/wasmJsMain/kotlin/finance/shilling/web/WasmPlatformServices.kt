package finance.shilling.web

import co.touchlab.kermit.Logger
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.store.SqlReceiptFileStorage
import finance.shilling.shared.data.store.Store5ReceiptFileStore
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

fun WasmReceiptFileStore(db: ShillingDatabase, spaceId: String = "__local__"): ReceiptFileStore =
    Store5ReceiptFileStore(SqlReceiptFileStorage(db, spaceId), WebReceiptOpener()::open)

private class WebReceiptOpener {
    private val log = Logger.withTag("WebReceiptOpen")

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun open(receiptId: String, originalName: String, bytes: ByteArray) {
        log.i { "Open requested: id=$receiptId, name=$originalName" }
        runCatching {
            val mimeType = mimeTypeForName(originalName)
            val encodedBytes = Base64.Default.encode(bytes)

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
