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
        )

        val json = packet.toJson()
        assertTrue(json.contains("\"messageId\":\"msg_001\""))
        assertTrue(json.contains("\"language\":\"hi\""))
        assertTrue(json.contains("\"text\":\"मुझे मदद चाहिए\""))
        assertTrue(json.contains("\"isAlert\":true"))

        val parsed = TextPacket.fromJson(json)
        assertNotNull(parsed)
        assertEquals(packet.messageId, parsed!!.messageId)
        assertEquals(packet.senderId, parsed.senderId)
        assertEquals(packet.language, parsed.language)
        assertEquals(packet.text, parsed.text)
        assertEquals(packet.isAlert, parsed.isAlert)
        assertEquals(packet.timestamp, parsed.timestamp)
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
        )

        val bytes = packet.toDelimitedBytes()
        assertTrue(bytes.size > 4)

        val headerLength = ByteBuffer.wrap(bytes, 0, 4).int
        val payloadLength = bytes.size - 4
        assertEquals(headerLength, payloadLength)

        val json = String(bytes, 4, payloadLength, Charsets.UTF_8)
        val parsed = TextPacket.fromJson(json)
        assertNotNull(parsed)
        assertEquals("Emergency supplies required", parsed?.text)
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

    @Test
    fun testBinaryCodecRoundTripCompactSize() {
        val codec = BinaryPacketCodec()
        val text = "मुझे सहायता चाहिए"  // 20+ UTF-8 bytes in Hindi
        val packet = TextPacket(
            messageId = "abc12345",
            senderId = "ITN-AAAA11",
            recipientId = "ITN-B91C",
            type = PacketType.DATA,
            language = "hi",
            sequence = 77,
            text = text,
            timestamp = System.currentTimeMillis()
        )

        val encoded = codec.encode(packet)
        val decoded = codec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(PacketType.DATA, decoded!!.type)
        assertEquals("hi", decoded.language)
        assertEquals(77, decoded.sequence)
        assertEquals(text, decoded.text)
        // VERSION 4+ preserves sender/recipient AND the exact message id
        assertEquals("ITN-AAAA11", decoded.senderId)
        assertEquals("ITN-B91C", decoded.recipientId)
        assertEquals("abc12345", decoded.messageId)
    }

    @Test
    fun testMessageIdSurvivesMultiHopEncodeDecode() {
        // Phase 2 requirement: the exact message id must not change across
        // encode -> decode -> (relay) -> decode -> destination.
        val codec = BinaryPacketCodec()
        val originalId = "7f3a9c21" // realistic UUID-substring id

        val senderPacket = TextPacket(
            messageId = originalId,
            senderId = "ITN-A111",
            recipientId = "ITN-D999",
            type = PacketType.DATA,
            language = "hi",
            text = "मुझे मदद चाहिए"
        )

        // Relay 1: encode on A, decode on R1
        val atR1 = codec.decode(codec.encode(senderPacket))!!
        assertEquals("Exact id must survive first hop", originalId, atR1.messageId)

        // Relay relays: same id, hopCount increments (createForwardedPacket)
        val atR1Forwarded = atR1.copy(messageId = atR1.messageId, hopCount = atR1.hopCount + 1)
        // Relay 2: encode on R1, decode on R2
        val atR2 = codec.decode(codec.encode(atR1Forwarded))!!
        assertEquals("Exact id must survive second hop", originalId, atR2.messageId)

        // Destination: decode after final relay
        val dest = codec.decode(codec.encode(atR2.copy(messageId = atR2.messageId, hopCount = atR2.hopCount + 1)))!!
        assertEquals("Exact id must survive to destination", originalId, dest.messageId)
    }

    @Test
    fun testAckCarriesExactMessageId() {
        val codec = BinaryPacketCodec()
        val originalId = "cafe1234"
        val data = TextPacket(
            messageId = originalId,
            senderId = "ITN-A111",
            recipientId = "ITN-B222",
            type = PacketType.DATA,
            language = "hi",
            text = "help"
        )
        val ack = data.createAckPacket("ITN-B222")
        assertEquals("ack_$originalId", ack.messageId)

        // ACK round-trips through the codec preserving its own (exact) id
        val decodedAck = codec.decode(codec.encode(ack))!!
        assertEquals("ack_$originalId", decodedAck.messageId)
    }

    @Test
    fun testBinaryPacketIsFarSmallerThanJson() {
        val codec = BinaryPacketCodec()
        val packet = TextPacket(
            messageId = "abc12345",
            senderId = "NODE_A",
            recipientId = "NODE_B",
            type = PacketType.DATA,
            language = "hi",
            text = "मुझे सहायता चाहिए",
            timestamp = System.currentTimeMillis()
        )

        val binarySize = codec.encode(packet).size
        val jsonSize = packet.toJsonBytes().size

        // Binary must remove JSON structural overhead
        assertTrue("binary $binarySize < json $jsonSize", binarySize < jsonSize)
    }

    @Test
    fun testBinaryCorruptionRejectedWithSessionKey() {
        val codec = BinaryPacketCodec()
        val key = ByteArray(32) { it.toByte() } // 32-byte session key
        val packet = TextPacket(
            messageId = "corrupt01",
            senderId = "NODE_A",
            recipientId = "*",
            type = PacketType.DATA,
            language = "en",
            text = "verify me",
            timestamp = System.currentTimeMillis()
        )

        val encoded = codec.encode(packet, sessionKey = key)
        // Flip a byte in the payload region (after 28-byte header)
        val corrupted = encoded.copyOf()
        val idx = encoded.size - 32 - 4 // last 4 bytes of payload
        corrupted[idx] = (corrupted[idx].toInt() xor 0xFF).toByte()

        assertNull(codec.decode(corrupted, sessionKey = key))
    }
}
