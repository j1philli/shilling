package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class ReceiptFileStoreTest {
    @Test
    fun receiptOperationsUseStore5WithoutReadingBlobsForPresenceChecks() = runBlocking {
        val storage = MemoryStorage()
        var opened: ByteArray? = null
        val files = Store5ReceiptFileStore(storage, { _, _, bytes -> opened = bytes })
        assertFalse(files.hasFile("r"))
        assertNull(files.read("r"))
        files.store("r", "receipt.jpg", byteArrayOf(1, 2))
        val reads = storage.reads
        assertTrue(files.hasFile("r"))
        assertEquals(reads, storage.reads)
        assertContentEquals(byteArrayOf(1, 2), files.read("r"))
        files.store("r", "receipt.jpg", byteArrayOf(3))
        files.openExternally("r", "receipt.jpg")
        assertContentEquals(byteArrayOf(3), opened)
        files.delete("r")
        assertFalse(files.hasFile("r"))
        assertNull(files.read("r"))
        files.store("another", "receipt.jpg", byteArrayOf(4))
        files.clearAll()
        assertFalse(files.hasFile("another"))
    }

    @Test
    fun sqlReceiptStorageIsReadAndClearedThroughStore5() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ShillingDatabase.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            val db = ShillingDatabase(driver)
            val files = Store5ReceiptFileStore(SqlReceiptFileStorage(db), { _, _, _ -> })
            assertFails { files.store("orphan", "receipt.png", byteArrayOf(1)) }
            val receipts = ReceiptRepository(ChangeNotifier(), createReceiptStore(db))
            receipts.saveWithFile(finance.shilling.shared.data.Receipt("receipt", null, "receipt.png", "receipt.png", 1),
                files, byteArrayOf(1, 2, 3))
            assertTrue(files.hasFile("receipt"))
            assertContentEquals(byteArrayOf(1, 2, 3), files.read("receipt"))
            val reader = assertNotNull(files.openReader("receipt"))
            assertEquals(3L, reader.size)
            assertContentEquals(byteArrayOf(2, 3), reader.readRange(1, 2).copyBytes())
            assertContentEquals(byteArrayOf(1), reader.readRange(0, 1).copyBytes())
            files.clearAll()
            assertFalse(files.hasFile("receipt"))
            assertNull(files.read("receipt"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun failedFileWriteRollsBackNewReceiptAndPartialFile() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ShillingDatabase.Schema.create(driver).await()
            val db = ShillingDatabase(driver)
            val storage = MemoryStorage().apply { failWrites = true }
            val files = Store5ReceiptFileStore(storage, { _, _, _ -> })
            val receipts = ReceiptRepository(ChangeNotifier(), createReceiptStore(db))
            val receipt = finance.shilling.shared.data.Receipt("failed", null, "file.png", "file.png", 1)
            assertFails { receipts.saveWithFile(receipt, files, byteArrayOf(1, 2)) }
            assertFalse(files.hasFile(receipt.id))
            assertTrue(createReceiptStore(db).readLocalSourceOfTruth(ReceiptKey.ById(receipt.id)).isEmpty())
            storage.failWrites = false
            receipts.saveWithFile(receipt, files, byteArrayOf(3))
            assertContentEquals(byteArrayOf(3), files.read(receipt.id))
        } finally { driver.close() }
    }

    @Test
    fun rangeReadsAvoidWholeBlobsAndRejectChangedFiles(): Unit = runBlocking {
        withTimeout(5_000) {
            val storage = MemoryStorage()
            val files = Store5ReceiptFileStore(storage, { _, _, _ -> })
            assertNull(files.openReader("missing"))
            files.store("r", "file", byteArrayOf(1, 2, 3))
            val original = assertNotNull(files.openReader("r"))
            assertEquals(3L, original.size)
            assertContentEquals(byteArrayOf(2, 3), original.readRange(1, 2).copyBytes())
            assertEquals(0, storage.reads, "Metadata and ranges must not read the whole file")
            assertFailsWith<IllegalArgumentException> { original.readRange(-1, 1) }
            assertFailsWith<IllegalArgumentException> { original.readRange(2, 2) }
            files.store("other", "file", byteArrayOf(8))
            assertContentEquals(byteArrayOf(1), original.readRange(0, 1).copyBytes())
            files.store("r", "file", byteArrayOf(4, 5, 6)) // Same-size replacement.
            assertFails { original.readRange(0, 1) }
            val replacement = assertNotNull(files.openReader("r"))
            assertContentEquals(byteArrayOf(4, 5, 6), replacement.readRange(0, 3).copyBytes())
            files.delete("r")
            assertFails { replacement.readRange(0, 1) }
            val another = assertNotNull(files.openReader("other"))
            files.clearAll()
            assertFails { another.readRange(0, 1) }
        }
    }

    @Test
    fun sourceOfTruthReadFailuresReachTheCaller() = runBlocking {
        withTimeout(5_000) {
            val storage = MemoryStorage()
            val files = Store5ReceiptFileStore(storage, { _, _, _ -> })
            files.store("r", "file", byteArrayOf(1))
            val reader = assertNotNull(files.openReader("r"))
            storage.failRangeReads = true
            assertFails { reader.readRange(0, 1) }
            storage.failRangeReads = false
            assertContentEquals(byteArrayOf(1), reader.readRange(0, 1).copyBytes())
        }
    }

    private class MemoryStorage : RangedReceiptFileStorage {
        private val files = mutableMapOf<String, ByteArray>()
        var reads = 0
        var failRangeReads = false
        var failWrites = false
        override suspend fun store(receiptId: String, fileName: String, bytes: ByteArray) {
            files[receiptId] = bytes
            check(!failWrites) { "Synthetic partial write failure" }
        }
        override suspend fun read(receiptId: String): ByteArray? { reads++; return files[receiptId] }
        override suspend fun size(receiptId: String) = files[receiptId]?.size?.toLong()
        override suspend fun readRange(receiptId: String, offset: Long, byteCount: Int): ByteArray? {
            check(!failRangeReads) { "Synthetic I/O failure" }
            return files[receiptId]?.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
        }
        override suspend fun hasFile(receiptId: String) = receiptId in files
        override suspend fun delete(receiptId: String) { files.remove(receiptId) }
        override suspend fun clearAll() { files.clear() }
    }
}
