package com.itantra.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class TextPacketTest {

    @Test
    fun testSerializationAndDeserialization() {
        val now = System.currentTimeMillis()
        val packet = TextPacket(
            version = 2,
            messageId = "msg_001",
            senderId = "phone_A",
            recipientId = "phone_B",
            language = "hi",
            text = "मुझे मदद चाहिए",
            isAlert = true,
            timestamp = now
        ).withChecksum()

        val json = packet.toJson()
        assertTrue(json.contains("\"messageId\":\"msg_001\""))
        assertTrue(json.contains("\"language\":\"hi\""))
        assertTrue(json.contains("\"text\":\"मुझे मदद चाहिए\""))
        assertTrue(json.contains("\"isAlert\":true"))

        val parsed = TextPacket.fromJson(json)
        assertNotNull(parsed)
        assertEquals(packet.version, parsed!!.version)
        assertEquals(packet.messageId, parsed.messageId)
        assertEquals(packet.senderId, parsed.senderId)
        assertEquals(packet.recipientId, parsed.recipientId)
        assertEquals(packet.language, parsed.language)
        assertEquals(packet.text, parsed.text)
        assertEquals(packet.isAlert, parsed.isAlert)
        assertEquals(packet.timestamp, parsed.timestamp)
        assertTrue(parsed.verifyIntegrity())
    }

    @Test
    fun testDelimitedBytesFraming() {
        val now = System.currentTimeMillis()
        val packet = TextPacket(
            version = 2,
            messageId = "test_frame",
            senderId = "node_1",
            recipientId = "*",
            language = "en",
            text = "Emergency supplies required",
            isAlert = false,
            timestamp = now
        ).withChecksum()

        val bytes = packet.toDelimitedBytes()
        assertTrue(bytes.size > 4)

        // Verify 4-byte big endian header
        val headerLength = ByteBuffer.wrap(bytes, 0, 4).int
        val payloadLength = bytes.size - 4
        assertEquals(headerLength, payloadLength)

        val json = String(bytes, 4, payloadLength, Charsets.UTF_8)
        val parsed = TextPacket.fromJson(json)
        assertNotNull(parsed)
        assertEquals("Emergency supplies required", parsed?.text)
        assertTrue(parsed!!.verifyIntegrity())
    }

    @Test
    fun testMalformedJsonRejection() {
        val invalidJson = "{ \"version\": 2, \"text\": \"corrupt... "
        val parsed = TextPacket.fromJson(invalidJson)
        assertNull(parsed)
    }

    @Test
    fun testBlankFieldsRejection() {
        val invalidPacket = TextPacket(
            version = 2,
            messageId = "",
            senderId = "node_1",
            recipientId = "*",
            language = "hi",
            text = "",
            isAlert = false,
            timestamp = 0L
        )
        assertFalse(invalidPacket.isValid())
    }
}
