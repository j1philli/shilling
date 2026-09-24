package finance.shilling.shared.data.sync

import finance.shilling.core.sync.SignalingMessage
import finance.shilling.shared.data.Receipt
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that verify the sync serialization pipeline and signaling envelope types.
 */
class SyncSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // --- SignalingMessage serialization ---

    @Test
    fun signalingMessageTypesRoundTrip() {
        val messages: List<SignalingMessage> = listOf(
            SignalingMessage.Join("device-1", "household-1"),
            SignalingMessage.PeerList(listOf("device-2", "device-3")),
            SignalingMessage.Offer("device-1", "device-2", "sdp-offer-data"),
            SignalingMessage.Answer("device-1", "device-2", "sdp-answer-data"),
            SignalingMessage.IceCandidate("device-1", "device-2", "candidate-data", "audio", 0)
        )

        messages.forEach { original ->
            val encoded = json.encodeToString<SignalingMessage>(original)
            val decoded = json.decodeFromString<SignalingMessage>(encoded)
            assertEquals(original, decoded, "Round-trip failed for ${original::class.simpleName}")
        }
    }

    @Test
    fun syncDataWithNullIceCandidateFieldsRoundTrips() {
        val original = SignalingMessage.IceCandidate("device-1", "device-2", "candidate", null, null)
        val encoded = json.encodeToString<SignalingMessage>(original)
        val decoded = json.decodeFromString<SignalingMessage>(encoded)
        assertIs<SignalingMessage.IceCandidate>(decoded)
        assertEquals(null, decoded.sdpMid)
        assertEquals(null, decoded.sdpMLineIndex)
    }

    // --- ChangeMessage with ReceiptPayload serialization ---

    @Test
    fun changeMessageWithReceiptPayloadRoundTrips() {
        val receipt = Receipt(
            id = "r-1",
            postingId = "p-1",
            filePath = "/receipts/r-1.jpg",
            originalName = "receipt.jpg",
            addedAt = 1000L,
            receiptDate = 2000L,
            amount = 42.50,
            notes = "grocery receipt"
        )
        val change = ChangeMessage(
            id = "change-1",
            entityType = EntityType.RECEIPT,
            op = ChangeOp.UPSERT,
            entityId = "r-1",
            timestamp = 1000L,
            deviceId = "device-1",
            payload = ChangePayload.ReceiptPayload(receipt)
        )

        val encoded = json.encodeToString(change)
        val decoded = json.decodeFromString<ChangeMessage>(encoded)

        assertEquals(change.id, decoded.id)
        assertEquals(EntityType.RECEIPT, decoded.entityType)
        assertEquals(ChangeOp.UPSERT, decoded.op)
        val decodedPayload = decoded.payload
        assertIs<ChangePayload.ReceiptPayload>(decodedPayload)
        assertEquals(receipt.id, decodedPayload.receipt.id)
        assertEquals(receipt.amount, decodedPayload.receipt.amount)
        assertEquals(receipt.originalName, decodedPayload.receipt.originalName)
    }

    @Test
    fun changeMessageDeleteHasNullPayload() {
        val change = ChangeMessage(
            id = "change-2",
            entityType = EntityType.RECEIPT,
            op = ChangeOp.DELETE,
            entityId = "r-1",
            timestamp = 2000L,
            deviceId = "device-1",
            payload = null
        )

        val encoded = json.encodeToString(change)
        val decoded = json.decodeFromString<ChangeMessage>(encoded)

        assertEquals(ChangeOp.DELETE, decoded.op)
        assertEquals(null, decoded.payload)
    }

    /**
     * Verifies that unknown SignalingMessage types don't crash deserialization
     * when ignoreUnknownKeys is true. This simulates what happens when a newer
     * client sends a message type the server doesn't know about.
     *
     * NOTE: kotlinx.serialization does NOT support ignoring unknown polymorphic
     * types in sealed classes — it WILL throw. This test documents that behavior
     * and proves Bug 1: the server MUST handle decode failures gracefully.
     */
    @Test
    fun unknownSignalingMessageTypeCrashes() {
        val unknownTypeJson = """{"type":"NewFutureMessage","data":"something"}"""
        var threw = false
        try {
            json.decodeFromString<SignalingMessage>(unknownTypeJson)
        } catch (e: Exception) {
            threw = true
            // This proves that unknown types CRASH deserialization.
            // The server MUST wrap this in try/catch to prevent killing the WebSocket.
        }
        assertTrue(threw, "Deserializing an unknown SignalingMessage type MUST throw — this proves the server needs error handling")
    }

    // --- FileTransferMessage serialization ---

    @Test
    fun fileTransferMessageRoundTrips() {
        val messages: List<FileTransferMessage> = listOf(
            FileTransferMessage.FileRequest("r-1"),
            FileTransferMessage.FileHeader("r-1", 1024, 2, "image/jpeg"),
            FileTransferMessage.FileChunk("r-1", 0, "base64data=="),
            FileTransferMessage.FileComplete("r-1"),
            FileTransferMessage.FileNotAvailable("r-1")
        )

        messages.forEach { original ->
            val encoded = json.encodeToString(original)
            val decoded = json.decodeFromString<FileTransferMessage>(encoded)
            assertEquals(original, decoded, "Round-trip failed for ${original::class.simpleName}")
        }
    }

    // --- All ChangePayload types ---

    @Test
    fun allChangePayloadTypesRoundTrip() {
        val payloads = listOf(
            ChangePayload.AccountPayload(
                finance.shilling.shared.data.Account("a-1", "Checking", 1000.0)
            ),
            ChangePayload.CategoryPayload(
                finance.shilling.shared.data.Category("c-1", "Groceries", "#00FF00")
            ),
            ChangePayload.ReceiptPayload(
                Receipt("r-1", null, "path", "file.jpg", 1000L, null, null, null)
            )
        )

        payloads.forEach { original ->
            val change = ChangeMessage(
                id = "test",
                entityType = EntityType.ACCOUNT, // type doesn't matter for serialization
                op = ChangeOp.UPSERT,
                entityId = "test",
                timestamp = 1000L,
                deviceId = "device",
                payload = original
            )
            val encoded = json.encodeToString(change)
            val decoded = json.decodeFromString<ChangeMessage>(encoded)
            assertNotNull(decoded.payload)
            val decodedPayload = decoded.payload
            assertNotNull(decodedPayload)
            assertEquals(original::class, decodedPayload::class)
        }
    }
}
