package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import finance.shilling.shared.db.ShillingDatabase

/** SQLite backing storage for the web/desktop receipt file SourceOfTruth. */
class SqlReceiptFileStorage(private val db: ShillingDatabase, private val spaceId: String = LOCAL_SPACE_ID) : ReceiptFileStorage {
    override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
        val mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> "application/octet-stream"
        }
        db.receiptFileQueries.upsert(receiptId, bytes, bytes.size.toLong(), mimeType, space_id = spaceId).await()
    }

    override suspend fun read(receiptId: String): ByteArray? =
        db.receiptFileQueries.selectByReceiptId(receiptId, space_id = spaceId).awaitAsOneOrNull()?.file_bytes

    override suspend fun hasFile(receiptId: String): Boolean =
        (db.receiptFileQueries.hasFile(receiptId, space_id = spaceId).awaitAsOneOrNull() ?: 0L) > 0L

    override suspend fun delete(receiptId: String) {
        db.receiptFileQueries.deleteByReceiptId(receiptId, space_id = spaceId)
    }

    override suspend fun clearAll() {
        db.receiptFileQueries.deleteAll(space_id = spaceId)
    }
}
