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

/** Extension for a receipt file from its leading bytes, for names saved without one. */
fun sniffReceiptExtension(header: ByteArray): String? {
    fun startsWith(vararg bytes: Int, offset: Int = 0) =
        header.size >= offset + bytes.size && bytes.indices.all { header[offset + it] == bytes[it].toByte() }
    return when {
        startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
        startsWith(0x25, 0x50, 0x44, 0x46) -> "pdf"
        startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
        // ISO-BMFF "ftyp" box: HEIC/HEIF photos from the iPhone camera.
        startsWith(0x66, 0x74, 0x79, 0x70, offset = 4) -> "heic"
        else -> null
    }
}
