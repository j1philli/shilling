package finance.shilling.shared.data

interface IdGenerator {
    fun newId(): String
}

interface ReceiptFileStore {
    suspend fun store(receiptId: String, fileName: String, bytes: ByteArray)
    suspend fun read(receiptId: String): ByteArray?
    suspend fun hasFile(receiptId: String): Boolean
    suspend fun delete(receiptId: String)
    suspend fun clearAll()
    suspend fun openExternally(receiptId: String, originalName: String)
}
