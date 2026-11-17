@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package finance.shilling.app

import finance.shilling.shared.data.ReceiptFileReader
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.sniffReceiptExtension
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.*

/** Quick Look copies use Store5 reads and bounded buffers, including for large receipts. */
suspend fun ReceiptFileStore.prepareIosReceiptPreview(receiptId: String, originalName: String): String? =
    withContext(Dispatchers.IO) {
        val reader = openReader(receiptId) ?: return@withContext null
        val safeName = originalName.substringAfterLast('/').substringAfterLast('\\')
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?.let { name ->
                // Quick Look picks the viewer from the extension; without one it shows "data".
                if ('.' in name.drop(1)) name
                else sniffReceiptExtension(reader.readRange(0, minOf(12L, reader.size).toInt()).copyBytes())?.let { "$name.$it" } ?: name
            }
            ?: "receipt.bin"
        val manager = NSFileManager.defaultManager
        val directory = "${NSTemporaryDirectory().trimEnd('/')}/receipt-preview-${NSUUID().UUIDString()}"
        check(manager.createDirectoryAtPath(directory, true, null, null))
        val path = "$directory/$safeName"
        try {
            check(manager.createFileAtPath(path, null, null))
            val output = NSFileHandle.fileHandleForWritingAtPath(path) ?: error("Unable to open preview")
            try {
                var offset = 0L
                while (offset < reader.size) {
                    val count = minOf(ReceiptFileReader.MAX_READ_BYTES.toLong(), reader.size - offset).toInt()
                    val block = reader.readRange(offset, count)
                    check(block.size == count) { "Receipt changed while preparing preview" }
                    val data = block.bytes.usePinned {
                        NSData.dataWithBytes(it.addressOf(block.offset), block.size.toULong())
                    }
                    output.writeData(data)
                    offset += block.size
                }
            } finally {
                output.closeFile()
            }
            path
        } catch (failure: Throwable) {
            manager.removeItemAtPath(directory, null)
            throw failure
        }
    }
