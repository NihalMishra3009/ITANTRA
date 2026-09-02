package com.itantra.protocol

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.itantra.security.MessageSecurityManager
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class PacketType {
    DATA,        // Normal voice-text payload
    ACK,         // Delivery Acknowledgement
    SOS_ALERT,   // High-priority emergency alert (legacy)
    EMERGENCY,   // Highest-priority emergency (bypasses normal queue)
    PING,        // Heartbeat / Link probe
    PONG,        // Heartbeat response
    RELAY,       // Multi-hop forwarded packet
    SESSION_START,
    SESSION_END,
    LANGUAGE_CHANGE
}

/**
 * iTantra TextPacket — in-memory message model for the transceiver.
 * Wire encoding is the compact binary format ([BinaryPacketCodec]); JSON
 * remains available only for debugging/tests. End-to-end security uses
 * AEAD (AES-256-GCM) via [MessageSecurityManager] with a session key —
 * never a hard-coded secret.
 */
data class TextPacket(
    val version: Int = 2,
    val messageId: String = UUID.randomUUID().toString().substring(0, 8),
    val senderId: String,
    val recipientId: String = "*",
    val type: PacketType = PacketType.DATA,
    val language: String,
    val sequence: Int = 0,
    val text: String = "",
    val encryptedPayload: String = "",
    val isEncrypted: Boolean = false,
    val isAlert: Boolean = false,
    val isPriority: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val hopCount: Int = 0,
    val maxHops: Int = 3,
    val ttlMs: Long = 300000L,
    val checksum: String = ""
) {

    fun withEncryption(sessionKey: ByteArray? = null): TextPacket {
        if (!MessageSecurityManager.hasSessionKey() && sessionKey == null) {
            throw SecurityException("No session key established — cannot encrypt")
        }
        val cipher = MessageSecurityManager.encryptPayload(this.text, sessionKey)
        return this.copy(
            text = "",
            encryptedPayload = cipher,
            isEncrypted = true
        )
    }

    fun withDecryption(sessionKey: ByteArray? = null): TextPacket {
        if (!isEncrypted || encryptedPayload.isBlank()) return this
        val plain = MessageSecurityManager.decryptPayload(this.encryptedPayload, sessionKey)
        return this.copy(text = plain)
    }

    fun isExpired(): Boolean {
        return (System.currentTimeMillis() - timestamp) > ttlMs
    }

    fun createAckPacket(ackSenderId: String): TextPacket {
        return TextPacket(
            version = this.version,
            messageId = "ack_" + this.messageId,
            senderId = ackSenderId,
            recipientId = this.senderId,
            type = PacketType.ACK,
            language = this.language,
            sequence = this.sequence,
            text = "ACK:${this.messageId}",
            timestamp = System.currentTimeMillis(),
            maxHops = this.maxHops
        )
    }

    fun createForwardedPacket(): TextPacket {
        return this.copy(
            hopCount = this.hopCount + 1,
            type = if (this.type == PacketType.DATA) PacketType.RELAY else this.type
        )
    }

    // --- JSON (debug/tests only — binary is the production wire format) -----

    fun toJson(): String = gson.toJson(this)

    fun toJsonBytes(): ByteArray = toJson().toByteArray(StandardCharsets.UTF_8)

    fun toDelimitedBytes(): ByteArray {
        val jsonBytes = toJsonBytes()
        val length = jsonBytes.size
        val header = ByteArray(4)
        header[0] = (length shr 24).toByte()
        header[1] = (length shr 16).toByte()
        header[2] = (length shr 8).toByte()
        header[3] = length.toByte()
        return header + jsonBytes
    }

    companion object {
        private val gson = Gson()

        fun fromJson(json: String): TextPacket? {
            return try {
                val packet = gson.fromJson(json, TextPacket::class.java)
                if (packet.isValid()) packet else null
            } catch (e: JsonSyntaxException) {
                null
            }
        }
    }

    fun isValid(): Boolean {
        val hasPayload = text.isNotBlank() || (isEncrypted && encryptedPayload.isNotBlank())
        return version >= 1 &&
                messageId.isNotBlank() &&
                senderId.isNotBlank() &&
                recipientId.isNotBlank() &&
                language.isNotBlank() &&
                hasPayload &&
                timestamp > 0 &&
                hopCount <= maxHops &&
                !isExpired()
    }
}
