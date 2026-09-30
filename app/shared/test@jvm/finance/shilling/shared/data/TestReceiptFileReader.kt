package finance.shilling.shared.data

/** Immutable in-memory file fixture; production readers live behind Store5. */
internal fun ByteArray.asReceiptReader(): ReceiptFileReader {
    val bytes = this
    return object : ReceiptFileReader {
        override val size = bytes.size.toLong()
        override suspend fun readRange(offset: Long, byteCount: Int) =
            ReceiptFileBlock(bytes, offset.toInt(), byteCount)
    }
}
