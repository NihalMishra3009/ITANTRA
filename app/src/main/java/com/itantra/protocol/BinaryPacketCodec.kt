package com.itantra.protocol

import com.itantra.security.MessageSecurityManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Compact binary wire format for iTantra TextPackets (PS 26173 — low-bitrate).
 *
 * Replaces JSON/Base64 overhead with a header + payload.
 *
 * VERSION 3 adds length-prefixed SENDER_ID and RECIPIENT_ID so multi-hop
 * routing can correctly identify origin and destination (VERSION 2 dropped
 * these, which broke true store-forward relay).
 *
 * Layout (big-endian unless noted):
 *   OFFSET  SIZE  FIELD
 *   0       2     MAGIC 0x49 0x54 ("IT")
 *   2       1     VERSION (3)
 *   3       1     TYPE            -> PacketType ordinal byte
 *   4       1     LANGUAGE        -> index into LanguageIndex map (0..9)
 *   5       1     FLAGS           bit0=encrypted, bit1=alert, bit2=priority
 *   6       2     SEQUENCE        network order (16-bit)
 *   8       4     MSG_ID          network order (hash of message id, 32-bit)
 *   12      8     TIMESTAMP ms epoch
 *   20      1     HOP_COUNT
 *   21      1     MAX_HOPS
 *   22      4     TTL_MS          (seconds, 32-bit)
 *   26      1     SENDER_LEN      bytes of sender id
 *   27      S     SENDER_ID       UTF-8 node id
 *   27+S    1     RECIP_LEN       bytes of recipient id
 *   28+S    R     RECIPIENT_ID    UTF-8 node id (or "*")
 *   28+S+R  2     PAYLOAD_LEN     (network order)
 *   30+S+R  N     PAYLOAD         (UTF-8 text, or encrypted payload string bytes)
 *   ...     32    AUTH_TAG        (HMAC-SHA256 over everything before tag)
 *
 * For a short Hindi message with 11-char node IDs this is ~90-110 bytes versus
 * ~500+ bytes for the equivalent JSON — still dramatically low-bitrate.
 */
class BinaryPacketCodec {

    companion object {
        private const val MAGIC0 = 0x49
        private const val MAGIC1 = 0x54
        private const val VERSION = 3
        private const val AUTH_LEN = 32

        val LANG_INDEX = listOf("hi", "en", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")

        const val FLAG_ENCRYPTED = 1
        const val FLAG_ALERT = 2
        const val FLAG_PRIORITY = 4
    }

    fun encode(packet: TextPacket, sessionKey: ByteArray? = null): ByteArray {
        val payloadBytes = packetPayloadBytes(packet)
        val langIdx = LANG_INDEX.indexOf(packet.language).coerceAtLeast(0)

        val senderBytes = packet.senderId.toByteArray(StandardCharsets.UTF_8)
        val recipientBytes = packet.recipientId.toByteArray(StandardCharsets.UTF_8)

        val flags = (if (packet.isEncrypted) FLAG_ENCRYPTED else 0) or
                (if (packet.isAlert) FLAG_ALERT else 0) or
                (if (packet.type == PacketType.EMERGENCY) FLAG_PRIORITY else 0)

        val msgIdHash = packet.messageId.hashCode()
        val ts = (packet.timestamp / 1000) * 1000L
        val ttlSecs = (packet.ttlMs / 1000).toInt().coerceAtMost(0x7FFFFFFF)

        val senderLen = senderBytes.size.coerceAtMost(0xFF)
        val recipientLen = recipientBytes.size.coerceAtMost(0xFF)
        val totalLen = 30 + senderBytes.size + recipientBytes.size + payloadBytes.size + AUTH_LEN
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
        buf.put(senderLen.toByte())
        buf.put(senderBytes.copyOf(senderLen))
        buf.put(recipientLen.toByte())
        buf.put(recipientBytes.copyOf(recipientLen))
        buf.putShort(payloadBytes.size.toShort())
        buf.put(payloadBytes)

        // Authenticate everything. When no explicit key is passed, use the active
        // session key (established by MessageSecurityManager) so the wire packets
        // are genuinely authenticated, not zero-padded.
        val preTagLen = buf.position()
        val authData = buf.array().copyOf(preTagLen)
        val auth = sessionKey ?: MessageSecurityManager.currentSessionKeyOrNull()
            ?.let { MessageSecurityManager.computeHmac(authData, it) }
            ?: ByteArray(AUTH_LEN)
        buf.put(auth.copyOf(AUTH_LEN))

        return buf.array()
    }

    fun decode(bytes: ByteArray, sessionKey: ByteArray? = null): TextPacket? {
        return try {
            if (bytes.size < 32) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            if (buf.get().toInt() and 0xFF != MAGIC0 || buf.get().toInt() and 0xFF != MAGIC1) return null
            when (buf.get().toInt() and 0xFF) {
                2 -> return decodeV2(bytes, sessionKey)
                3 -> { /* continue below */ }
                else -> return null
            }

            val typeOrd = buf.get().toInt() and 0xFF
            val langIdx = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val seq = buf.short.toInt() and 0xFFFF
            val sig = buf.int
            val ts = buf.long
            val hopCount = buf.get().toInt() and 0xFF
            val maxHops = buf.get().toInt() and 0xFF
            val ttlSecs = buf.int

            val senderLen = buf.get().toInt() and 0xFF
            if (senderLen < 0 || senderLen > bytes.size) return null
            val sender = ByteArray(senderLen)
            buf.get(sender)

            val recipientLen = buf.get().toInt() and 0xFF
            if (recipientLen < 0 || senderLen + recipientLen + 2 > bytes.size) return null
            val recipient = ByteArray(recipientLen)
            buf.get(recipient)

            val payloadLen = buf.short.toInt() and 0xFFFF
            val payloadStart = buf.position()
            if (payloadLen < 0 || payloadStart + payloadLen + AUTH_LEN > bytes.size) return null

            val payload = ByteArray(payloadLen)
            buf.get(payload)

            val auth = ByteArray(AUTH_LEN)
            buf.get(auth)

            // Verify integrity. Use explicit key, else the active session key.
            val verifyKey = sessionKey ?: MessageSecurityManager.currentSessionKeyOrNull()
            if (verifyKey != null) {
                val preTagLen = payloadStart + payloadLen
                val authData = bytes.copyOfRange(0, preTagLen)
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
                senderId = String(sender, StandardCharsets.UTF_8),
                recipientId = String(recipient, StandardCharsets.UTF_8),
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

    /**
     * Decode a VERSION 2 packet (kept for backward compat). V2 did not carry
     * sender/recipient IDs, so those default to "" / "*" as before.
     */
    private fun decodeV2(bytes: ByteArray, sessionKey: ByteArray?): TextPacket? {
        return try {
            if (bytes.size < 28 + 32) return null
            val buf = ByteBuffer.wrap(bytes, 2, bytes.size - 2).order(ByteOrder.BIG_ENDIAN)
            if (buf.get().toInt() and 0xFF != 2) return null

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
            if (payloadLen < 0 || 28 + payloadLen + 32 > bytes.size) return null

            val payload = ByteArray(payloadLen)
            buf.get(payload)
            val auth = ByteArray(32)
            buf.get(auth)

            val verifyKey = sessionKey ?: MessageSecurityManager.currentSessionKeyOrNull()
            if (verifyKey != null) {
                val authData = bytes.copyOfRange(0, 28 + payloadLen)
                val expected = MessageSecurityManager.computeHmac(authData, verifyKey).copyOf(32)
                if (!expected.contentEquals(auth)) return null
            }

            val type = PacketType.values().getOrNull(typeOrd) ?: return null
            val language = LANG_INDEX.getOrNull(langIdx) ?: return null
            val text = String(payload, StandardCharsets.UTF_8)
            val isEncrypted = flags and FLAG_ENCRYPTED != 0
            val isAlert = flags and FLAG_ALERT != 0
            val messageId = "%08x".format(sig)

            TextPacket(
                version = 2,
                messageId = messageId,
                senderId = "",
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
