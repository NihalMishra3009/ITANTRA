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
 * VERSION 4 transmits the EXACT message ID as a length-prefixed UTF-8 string
 * (no lossy hash). VERSION 3 added sender/recipient IDs. The exact message id
 * is required so deduplication, outbox, retry, ACK, delivery tracking,
 * forwarding and diagnostics all reference the identical identifier.
 *
 * Layout (big-endian unless noted):
 *   OFFSET  SIZE  FIELD
 *   0       2     MAGIC 0x49 0x54 ("IT")
 *   2       1     VERSION (4)
 *   3       1     TYPE            -> PacketType ordinal byte
 *   4       1     LANGUAGE        -> index into LanguageIndex map (0..9)
 *   5       1     FLAGS           bit0=encrypted, bit1=alert, bit2=priority
 *   6       2     SEQUENCE        network order (16-bit)
 *   8       4     MSG_ID_LEN      length of the UTF-8 message-id string
 *   12      N     MESSAGE_ID      exact UTF-8 message id
 *   12+N    8     TIMESTAMP ms epoch
 *   20+N    1     SENDER_LEN      bytes of sender id
 *   21+N    S     SENDER_ID       UTF-8 node id
 *   21+N+S  1     RECIP_LEN       bytes of recipient id
 *   22+N+S  R     RECIPIENT_ID    UTF-8 node id (or "*")
 *   22+N+S+R  2   PAYLOAD_LEN     (network order)
 *   24+N+S+R  P   PAYLOAD         (UTF-8 text, or encrypted payload string bytes)
 *   ...     32    AUTH_TAG        (HMAC-SHA256 over everything before tag)
 *
 * VERSION 3 decode is kept for backward compatibility.
 */
class BinaryPacketCodec {

    companion object {
        private const val MAGIC0 = 0x49
        private const val MAGIC1 = 0x54
        private const val VERSION = 4
        private const val AUTH_LEN = 32

        val LANG_INDEX = listOf("hi", "en", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")

        const val FLAG_ENCRYPTED = 1
        const val FLAG_ALERT = 2
        const val FLAG_PRIORITY = 4
        const val FLAG_UNAUTH = 8    // bit3: packet has no HMAC (used for ECDH handshake bootstrap)
    }

    /**
     * Encode a TextPacket into compact binary.
     * @param skipAuth if true, writes an all-zero HMAC tag and skips authentication.
     *                 Used for SESSION_START bootstrap packets where no shared key exists yet.
     */
    fun encode(packet: TextPacket, sessionKey: ByteArray? = null, skipAuth: Boolean = false): ByteArray {
        val payloadBytes = packetPayloadBytes(packet)
        val langIdx = LANG_INDEX.indexOf(packet.language).coerceAtLeast(0)

        val msgIdBytes = packet.messageId.toByteArray(StandardCharsets.UTF_8)
        val senderBytes = packet.senderId.toByteArray(StandardCharsets.UTF_8)
        val recipientBytes = packet.recipientId.toByteArray(StandardCharsets.UTF_8)

        val flags = (if (packet.isEncrypted) FLAG_ENCRYPTED else 0) or
                (if (packet.isAlert) FLAG_ALERT else 0) or
                (if (packet.type == PacketType.EMERGENCY) FLAG_PRIORITY else 0) or
                (if (skipAuth) FLAG_UNAUTH else 0)

        // Preserve the FULL millisecond timestamp. Truncating to seconds here
        // would break AEAD decryption on the receiver: the encryption AAD binds
        // the exact ms timestamp, so a rounded value causes an AAD mismatch and
        // the payload is silently dropped (no message, no audio).
        val ts = packet.timestamp
        val ttlSecs = (packet.ttlMs / 1000).toInt().coerceAtMost(0x7FFFFFFF)

        val msgIdLen = msgIdBytes.size.coerceAtMost(0x7FFFFFFF)
        val senderLen = senderBytes.size.coerceAtMost(0xFF)
        val recipientLen = recipientBytes.size.coerceAtMost(0xFF)
        // Fixed: magic(2)+version(1)+type(1)+lang(1)+flags(1)+seq(2) =8
        //        +msgIdLen(4)+timestamp(8)+hop(1)+maxhop(1)+ttl(4)=18 -> 26
        //        +senderLen(1)+recipLen(1)+payloadLen(2)=4 -> 30
        val totalLen = 30 + msgIdBytes.size + senderBytes.size + recipientBytes.size + payloadBytes.size + AUTH_LEN
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        buf.put(MAGIC0.toByte())
        buf.put(MAGIC1.toByte())
        buf.put(VERSION.toByte())
        buf.put(packet.type.ordinal.toByte())
        buf.put(langIdx.toByte())
        buf.put(flags.toByte())
        buf.putShort(packet.sequence.toShort())
        buf.putInt(msgIdLen)
        buf.put(msgIdBytes)
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
        val auth = if (skipAuth) {
            ByteArray(AUTH_LEN) // all zeros — no authentication for bootstrap packets
        } else {
            val authData = buf.array().copyOf(preTagLen)
            sessionKey ?: MessageSecurityManager.currentSessionKeyOrNull()
                ?.let { MessageSecurityManager.computeHmac(authData, it) }
                ?: ByteArray(AUTH_LEN)
        }
        buf.put(auth.copyOf(AUTH_LEN))

        return buf.array()
    }

    fun decode(bytes: ByteArray, sessionKey: ByteArray? = null): TextPacket? {
        return try {
            if (bytes.size < 32) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            if (buf.get().toInt() and 0xFF != MAGIC0 || buf.get().toInt() and 0xFF != MAGIC1) return null
            when (val ver = buf.get().toInt() and 0xFF) {
                2 -> return decodeV2(bytes, sessionKey)
                3 -> return decodeV3(bytes, sessionKey)
                4 -> { /* continue below */ }
                else -> return null
            }

            val typeOrd = buf.get().toInt() and 0xFF
            val langIdx = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val seq = buf.short.toInt() and 0xFFFF
            val msgIdLen = buf.int
            if (msgIdLen < 0 || msgIdLen > bytes.size) return null
            val msgId = ByteArray(msgIdLen)
            buf.get(msgId)
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
            // Skip if FLAG_UNAUTH is set (bootstrap/handshake packet).
            val isUnauth = (flags and FLAG_UNAUTH) != 0
            if (!isUnauth) {
                val verifyKey = sessionKey ?: MessageSecurityManager.currentSessionKeyOrNull()
                if (verifyKey != null) {
                    val preTagLen = payloadStart + payloadLen
                    val authData = bytes.copyOfRange(0, preTagLen)
                    val expected = MessageSecurityManager.computeHmac(authData, verifyKey).copyOf(AUTH_LEN)
                    if (!expected.contentEquals(auth)) return null
                }
            }

            val type = PacketType.values().getOrNull(typeOrd) ?: return null
            val language = LANG_INDEX.getOrNull(langIdx) ?: return null

            val text = String(payload, StandardCharsets.UTF_8)
            val isEncrypted = flags and FLAG_ENCRYPTED != 0
            val isAlert = flags and FLAG_ALERT != 0

            TextPacket(
                version = VERSION,
                messageId = String(msgId, StandardCharsets.UTF_8),
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

    /** Decode VERSION 3 (sender/recipient present, but message id was a 32-bit hash). */
    private fun decodeV3(bytes: ByteArray, sessionKey: ByteArray?): TextPacket? {
        return try {
            if (bytes.size < 60) return null
            val buf = ByteBuffer.wrap(bytes, 2, bytes.size - 2).order(ByteOrder.BIG_ENDIAN)
            if (buf.get().toInt() and 0xFF != 3) return null

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
            val auth = ByteArray(32)
            buf.get(auth)

            val verifyKey = sessionKey ?: MessageSecurityManager.currentSessionKeyOrNull()
            if (verifyKey != null) {
                val preTagLen = payloadStart + payloadLen
                val authData = bytes.copyOfRange(0, preTagLen)
                val expected = MessageSecurityManager.computeHmac(authData, verifyKey).copyOf(32)
                if (!expected.contentEquals(auth)) return null
            }

            val type = PacketType.values().getOrNull(typeOrd) ?: return null
            val language = LANG_INDEX.getOrNull(langIdx) ?: return null
            val text = String(payload, StandardCharsets.UTF_8)
            val isEncrypted = flags and FLAG_ENCRYPTED != 0
            val isAlert = flags and FLAG_ALERT != 0
            val messageId = "%08x".format(sig) // v3 reconstructed from hash (lossy)

            TextPacket(
                version = 3,
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
     * sender/recipient IDs or an exact message id.
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
