package finance.shilling.shared.data

interface IdGenerator {
    fun newId(): String
}

/** Each reader yields one file version: a buffered snapshot or ranges that fail on mutation. */
interface ReceiptFileReader {
    companion object { const val MAX_READ_BYTES = 256 * 1024 }
    val size: Long
    suspend fun readRange(offset: Long, byteCount: Int): ReceiptFileBlock
}

/** A view allows buffered storage to provide ranges without another copy. */
class ReceiptFileBlock(val bytes: ByteArray, val offset: Int = 0, val size: Int = bytes.size) {
    init { require(offset >= 0 && size >= 0 && offset <= bytes.size - size) }
    fun copyBytes(): ByteArray = bytes.copyOfRange(offset, offset + size)
}

/** A staged receipt becomes visible only after all validated chunks commit. */
interface ReceiptFileWriter {
    suspend fun writeRange(offset: Long, block: ReceiptFileBlock)
    suspend fun commit(fileName: String)
    suspend fun abort()
}

interface ReceiptFileStore {
    suspend fun store(receiptId: String, fileName: String, bytes: ByteArray)
    suspend fun read(receiptId: String): ByteArray?
    suspend fun openReader(receiptId: String): ReceiptFileReader?
    suspend fun openWriter(receiptId: String, size: Long): ReceiptFileWriter? = null
    suspend fun hasFile(receiptId: String): Boolean
    suspend fun delete(receiptId: String)
    suspend fun clearAll()
    suspend fun openExternally(receiptId: String, originalName: String)
}
