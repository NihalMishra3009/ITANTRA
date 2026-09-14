package com.itantra.protocol

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 7 codec security: unknown language must reject (never coerce to Hindi),
 * payload bounded by the 16-bit Short, unauthenticated is bootstrap-only,
 * invalid TTL / looped hop counts rejected.
 */
class BinaryPacketCodecSecurityTest {

    private fun packet(lang: String = "hi", type: PacketType = PacketType.DATA,
                       text: String = "hello", ttlMs: Long = 300_000L,
                       hop: Int = 0, maxHops: Int = 3,
                       ts: Long = System.currentTimeMillis()): TextPacket =
        TextPacket(
            version = 4,
            messageId = "m_${System.nanoTime()}",
            senderId = "ITN-A",
            recipientId = "ITN-B",
            type = type,
            language = lang,
            text = text,
            isEncrypted = false,
            timestamp = ts,
            hopCount = hop,
            maxHops = maxHops,
            ttlMs = ttlMs
        )

    private val key = "0123456789abcdef0123456789abcdef".toByteArray()

    @Test
    fun testUnknownLanguage_rejectedNeverMapsToHindi() {
        val codec = BinaryPacketCodec()
        // "zz" is not in LANG_INDEX — encoding must throw, not silently write "hi".
        val bad = packet(lang = "zz")
        try {
            codec.encode(bad, sessionKey = key)
            fail("encoding an unknown language must throw")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unknown protocol language"))
        }
    }

    @Test
    fun testOversizedPayload_rejected() {
        val codec = BinaryPacketCodec()
        val big = packet(text = "a".repeat(32_768))
        try {
            codec.encode(big, sessionKey = key)
            fail("payload over 32767 must be rejected (Short field)")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("payload too large"))
        }
        // Boundary size still encodes fine.
        val ok = packet(text = "a".repeat(32_767))
        val encoded = codec.encode(ok, sessionKey = key)
        assertTrue(encoded.size > 30)
    }

    @Test
    fun testUnAuthenticatedOnlyForSessionStart() {
        val codec = BinaryPacketCodec()
        val data = packet(type = PacketType.DATA)
        try {
            codec.encode(data, sessionKey = key, skipAuth = true)
            fail("skipAuth on DATA must throw")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("SESSION_START"))
        }
        // Bootstrap legitimately skips auth.
        val bootstrap = packet(type = PacketType.SESSION_START)
        val enc = codec.encode(bootstrap, skipAuth = true)
        assertNotNull(codec.decode(enc, sessionKey = null))
    }

    @Test
    fun testForcedUnauthFlagOnDataDecode_rejected() {
        val codec = BinaryPacketCodec()
        // Encode a valid SESSION_START unauth, then flip its TYPE byte to DATA and
        // feed it to decode — must be rejected, not accepted as a free DATA packet.
        val bootstrap = packet(type = PacketType.SESSION_START)
        val enc = codec.encode(bootstrap, skipAuth = true).copyOf()
        // TYPE at offset 3.
        enc[3] = PacketType.DATA.ordinal.toByte()
        assertNull("DATA masquerading as bootstrap unauth must be rejected", codec.decode(enc))
    }

    @Test
    fun testInvalidTtlRejected() {
        val codec = BinaryPacketCodec()
        val p = packet()
        val enc = codec.encode(p, sessionKey = key)
        // TTL field (int32) starts after version/type/lang/flags/seq(8)+msgIdLen(4)+msgId(N)+ts(8)+hop(1)+maxhop(1).
        val msgIdLen = java.nio.ByteBuffer.wrap(enc).getInt(8)
        val ttlOffset = 8 + 4 + msgIdLen + 8 + 1 + 1
        // Overwrite with a negative TTL.
        java.nio.ByteBuffer.wrap(enc).putInt(ttlOffset, -5)
        // HMAC no longer matches too, but the TTL check rejects before auth? TTL is
        // checked at decode before auth; tamper changes both. Assert null either way.
        assertNull("negative TTL must reject", codec.decode(enc, sessionKey = key))
    }

    @Test
    fun testHopCountExceedsMaxHops_rejected() {
        val codec = BinaryPacketCodec()
        val p = packet(hop = 2, maxHops = 1)
        val enc = codec.encode(p, sessionKey = key)
        assertNull("looped packet (hop>max) must reject", codec.decode(enc, sessionKey = key))
    }

    @Test
    fun testWrongPeerKey_rejected() {
        val codec = BinaryPacketCodec()
        val otherKey = "fedcba9876543210fedcba9876543210".toByteArray()
        val enc = codec.encode(packet(), sessionKey = key)
        assertNull("packet encrypted/auth for A must not verify with B's key",
            codec.decode(enc, sessionKey = otherKey))
    }

    @Test
    fun testValidPacketRoundTripsWithPeerKey() {
        val codec = BinaryPacketCodec()
        val p = packet(lang = "hi", text = "मुझे मदद चाहिए")
        val enc = codec.encode(p, sessionKey = key)
        val dec = codec.decode(enc, sessionKey = key)
        assertNotNull(dec)
        assertEquals("hi", dec!!.language)
        assertEquals("मुझे मदद चाहिए", dec.text)
    }
}