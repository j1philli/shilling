package finance.shilling.shared.data.sync

/** SHF v1: magic/version (4), UTF-8 ID length (u16), index (u32), ID, raw chunk.
 * Multi-byte integers use network byte order. Header/complete messages remain JSON.
 * All receipt chunks use this frame format.
 */
internal object BinaryFileChunkCodec {
    private val magic = byteArrayOf(0x53, 0x48, 0x46, 1)
    private const val HEADER_BYTES = 10
    private const val MAX_ID_BYTES = 1024

    fun isValidReceiptId(id: String): Boolean {
        val bytes = id.encodeToByteArray()
        return bytes.size in 1..MAX_ID_BYTES && bytes.decodeToString() == id
    }

    fun encode(chunk: FileTransferMessage.FileChunk): ByteArray {
        val id = chunk.receiptId.encodeToByteArray()
        require(id.size in 1..MAX_ID_BYTES && id.decodeToString() == chunk.receiptId)
        require(chunk.index in 0 until FileTransferManager.MAX_CHUNKS)
        require(chunk.payloadSize in 1..FileTransferManager.CHUNK_SIZE)
        return ByteArray(HEADER_BYTES + id.size + chunk.payloadSize).also { frame ->
            magic.copyInto(frame)
            frame[4] = (id.size ushr 8).toByte()
            frame[5] = id.size.toByte()
            repeat(4) { frame[6 + it] = (chunk.index ushr (24 - it * 8)).toByte() }
            id.copyInto(frame, HEADER_BYTES)
            chunk.copyPayloadInto(frame, HEADER_BYTES + id.size)
        }
    }

    fun decode(frame: ByteArray): FileTransferMessage.FileChunk {
        require(frame.size in (HEADER_BYTES + 2)..(HEADER_BYTES + MAX_ID_BYTES + FileTransferManager.CHUNK_SIZE))
        require(magic.indices.all { frame[it] == magic[it] }) { "Unknown receipt frame version" }
        val idSize = ((frame[4].toInt() and 255) shl 8) or (frame[5].toInt() and 255)
        require(idSize in 1..MAX_ID_BYTES)
        val payloadStart = HEADER_BYTES + idSize
        require(frame.size - payloadStart in 1..FileTransferManager.CHUNK_SIZE)
        var index = 0
        repeat(4) { index = (index shl 8) or (frame[6 + it].toInt() and 255) }
        require(index in 0 until FileTransferManager.MAX_CHUNKS)
        val id = frame.decodeToString(HEADER_BYTES, payloadStart, throwOnInvalidSequence = true)
        return FileTransferMessage.FileChunk.slice(id, index, frame, payloadStart, frame.size - payloadStart)
    }
}
