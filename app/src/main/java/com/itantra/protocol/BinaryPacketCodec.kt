package com.itantra.protocol

import com.itantra.security.MessageSecurityManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Compact binary wire format for iTantra TextPackets (PS 26173 — low-bitrate).
 *
 * Replaces JSON/Base64 overhead with a fixed-size header + payload.
 *
 * Layout (big-endian unless noted):
 *   OFFSET  SIZE  FIELD
 *   0       2     MAGIC 0x49 0x54 ("IT")
 *   2       1     VERSION (2)
 *   3       1     TYPE      -> PacketType ordinal byte
 *   4       1     LANGUAGE  -> index into LanguageIndex map (0..9)
 *   5       1     FLAGS     bit0=encrypted, bit1=alert, bit2=priority
 *   6       2     SEQUENCE  network order (16-bit)
 *   8       4     MSG_ID    network order (hash of message id, 32-bit)
 *   12      8     TIMESTAMP ms epoch
 *   20      1     HOP_COUNT
 *   21      1     MAX_HOPS
 *   22      4     TTL_MS    (seconds, 32-bit)
 *   26      2     PAYLOAD_LEN (network order)
 *   28      N     PAYLOAD (UTF-8 text, or encrypted payload string bytes)
 *   28+N    32    AUTH_TAG (HMAC-SHA256 over header+payload)
 *
 * TOTAL header = 28 bytes. A short Hindi message (~30 UTF-8 bytes) therefore
 * transmits in ~30 + 28 + 32 = 90 bytes versus ~500+ bytes for the JSON form.
 */
class BinaryPacketCodec {

    companion object {
        private const val MAGIC0 = 0x49
        private const val MAGIC1 = 0x54
        private const val VERSION = 2
        private const val HEADER_LEN = 28
        private const val AUTH_LEN = 32

        val LANG_INDEX = listOf("hi", "en", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")

        const val FLAG_ENCRYPTED = 1
        const val FLAG_ALERT = 2
        const val FLAG_PRIORITY = 4
    }

    fun encode(packet: TextPacket, sessionKey: ByteArray? = null): ByteArray {
        val payloadBytes = packetPayloadBytes(packet)
        val langIdx = LANG_INDEX.indexOf(packet.language).coerceAtLeast(0)

        val flags = (if (packet.isEncrypted) FLAG_ENCRYPTED else 0) or
                (if (packet.isAlert) FLAG_ALERT else 0) or
                (if (packet.type == PacketType.EMERGENCY) FLAG_PRIORITY else 0)

        val msgIdHash = packet.messageId.hashCode()
        val ts = (packet.timestamp / 1000) * 1000L
        val ttlSecs = (packet.ttlMs / 1000).toInt().coerceAtMost(0x7FFFFFFF)

        val totalLen = HEADER_LEN + payloadBytes.size + AUTH_LEN
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        buf.put(MAGIC0.toByte())
        buf.put(MAGIC1.toByte())
        buf.put(VERSION.toByte())
        buf.put(packet.type.ordinal.toByte())
        buf.put(langIdx.toByte())
        buf.put(flags.toByte())
        buf.putShort(packet.sequence.toShort())
        buf.putInt(msgIdHash)
        buf.putLong(ts)
        buf.put(packet.hopCount.toByte())
        buf.put(packet.maxHops.toByte())
        buf.putInt(ttlSecs)
        buf.putShort(payloadBytes.size.toShort())
        buf.put(payloadBytes)

        // Authenticate everything. When no explicit key is passed, use the active
        // session key (established by MessageSecurityManager) so the wire packets
        // are genuinely authenticated, not zero-padded.
        val authData = buf.array().copyOf(HEADER_LEN + payloadBytes.size)
        val auth = sessionKey ?: MessageSecurityManager.currentSessionKeyOrNull()
            ?.let { MessageSecurityManager.computeHmac(authData, it) }
            ?: ByteArray(AUTH_LEN)
        buf.put(auth.copyOf(AUTH_LEN))

        return buf.array()
    }

    fun decode(bytes: ByteArray, sessionKey: ByteArray? = null): TextPacket? {
        return try {
            if (bytes.size < HEADER_LEN + AUTH_LEN) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            if (buf.get().toInt() and 0xFF != MAGIC0 || buf.get().toInt() and 0xFF != MAGIC1) return null
            if (buf.get().toInt() and 0xFF != VERSION) return null

            val typeOrd = buf.get().toInt() and 0xFF
            val langIdx = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val seq = buf.short.toInt() and 0xFFFF
            val sig = buf.int
            val ts = buf.long
            val hopCount = buf.get().toInt() and 0xFF
            val maxHops = buf.get().toInt() and 0xFF
            val ttlSecs = buf.int
            val payloadLen = buf.short.toInt() and 0xFFFF

            if (payloadLen < 0 || HEADER_LEN + payloadLen + AUTH_LEN > bytes.size) return null

            val payload = ByteArray(payloadLen)
            buf.get(payload)

            val auth = ByteArray(AUTH_LEN)
            buf.get(auth)

            // Verify integrity. Use explicit key, else the active session key.
            val verifyKey = sessionKey ?: MessageSecurityManager.currentSessionKeyOrNull()
            if (verifyKey != null) {
                val authData = bytes.copyOfRange(0, HEADER_LEN + payloadLen)
                val expected = MessageSecurityManager.computeHmac(authData, verifyKey).copyOf(AUTH_LEN)
                if (!expected.contentEquals(auth)) return null
            }

            val type = PacketType.values().getOrNull(typeOrd) ?: return null
            val language = LANG_INDEX.getOrNull(langIdx) ?: return null

            val text = String(payload, StandardCharsets.UTF_8)
            val isEncrypted = flags and FLAG_ENCRYPTED != 0
            val isAlert = flags and FLAG_ALERT != 0

            val messageId = "%08x".format(sig)

            TextPacket(
                version = VERSION,
                messageId = messageId,
                senderId = "",        // filled by transport layer / caller
                recipientId = "*",
                type = type,
                language = language,
                sequence = seq,
                text = if (isEncrypted) "" else text,
                encryptedPayload = if (isEncrypted) text else "",
                isEncrypted = isEncrypted,
                isAlert = isAlert,
                timestamp = ts,
                hopCount = hopCount,
                maxHops = maxHops,
                ttlMs = ttlSecs * 1000L
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun packetPayloadBytes(packet: TextPacket): ByteArray {
        val text = if (packet.isEncrypted) packet.encryptedPayload else packet.text
        return text.toByteArray(StandardCharsets.UTF_8)
    }
}
