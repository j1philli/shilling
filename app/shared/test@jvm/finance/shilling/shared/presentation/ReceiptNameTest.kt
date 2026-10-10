package finance.shilling.shared.presentation

import finance.shilling.shared.data.sniffReceiptExtension
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiptNameTest {
    @Test
    fun renamedReceiptKeepsPickedExtension() {
        assertEquals("Dinner.jpg", withExtensionOf("Photo 2026-10-01.jpg", "Dinner"))
        assertEquals("Dinner.JPG", withExtensionOf("Photo.jpg", "Dinner.JPG"))
        assertEquals("Dinner", withExtensionOf("scan", "Dinner"))
    }

    @Test
    fun extensionIsSniffedFromFileBytes() {
        assertEquals("jpg", sniffReceiptExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("pdf", sniffReceiptExtension("%PDF-1.7".encodeToByteArray()))
        assertEquals("heic", sniffReceiptExtension(byteArrayOf(0, 0, 0, 24) + "ftypheic".encodeToByteArray()))
        assertEquals(null, sniffReceiptExtension("hello".encodeToByteArray()))
    }
}
