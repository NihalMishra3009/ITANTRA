package com.itantra.protocol

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.itantra.security.MessageSecurityManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class PacketType {
    DATA,       // Normal voice-text payload
    ACK,        // Delivery Acknowledgement
    SOS_ALERT,  // High-priority emergency alert
    PING,       // Heartbeat / Link probe
    PONG,       // Heartbeat response
    RELAY       // Multi-hop forwarded packet
}

/**
 * AI4Bharat-integrated Secure TextPacket Protocol (ISRO PS 26173)
 * Decouples AI audio/text inference from transport routing, supporting end-to-end AES encryption.
 */
data class TextPacket(
    val version: Int = 2,
    val messageId: String = UUID.randomUUID().toString().substring(0, 8),
    val senderId: String,                    // Source Node ID (e.g. "NODE_A")
    val recipientId: String = "*",           // Destination Node ID ("*" for broadcast, or specific "NODE_B")
    val type: PacketType = PacketType.DATA,  // Packet Type
    val language: String,                    // hi, en, gu, mr, kn, ml, ta, te, or, bn
    val text: String,                        // Transcribed / normalized text (or decrypted text)
    val encryptedPayload: String = "",       // Encrypted ciphertext for transit
    val isEncrypted: Boolean = false,        // Flag indicating payload encryption
    val isAlert: Boolean = false,            // High-priority override flag
    val timestamp: Long = System.currentTimeMillis(),
    val hopCount: Int = 0,                   // Multi-hop hops traversed
    val maxHops: Int = 3,                    // Maximum TTL hops in mesh
    val ttlMs: Long = 300000L,               // Store & forward Time-To-Live (5 minutes)
    val checksum: String = ""                // SHA-256 HMAC / integrity signature
) {
    fun calculateChecksum(secretKey: String = "ITANTRA_OFFLINE_SECRET_26173"): String {
        val payloadBody = if (isEncrypted) encryptedPayload else text
        val raw = "$version:$messageId:$senderId:$recipientId:$type:$language:$payloadBody:$isAlert:$timestamp:$hopCount:$secretKey"
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(raw.toByteArray(StandardCharsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    fun withChecksum(): TextPacket {
        return this.copy(checksum = calculateChecksum())
    }

    fun withEncryption(secretKey: String = "ITANTRA_SECURE_PEER_KEY_26173"): TextPacket {
        val cipher = MessageSecurityManager.encryptPayload(this.text, secretKey)
        return this.copy(
            text = "", // Clear plain text from transit packet
            encryptedPayload = cipher,
            isEncrypted = true
        ).withChecksum()
    }

    fun withDecryption(secretKey: String = "ITANTRA_SECURE_PEER_KEY_26173"): TextPacket {
        if (!isEncrypted || encryptedPayload.isBlank()) return this
        val plain = MessageSecurityManager.decryptPayload(this.encryptedPayload, secretKey)
        return this.copy(
            text = plain
        )
    }

    fun verifyIntegrity(secretKey: String = "ITANTRA_OFFLINE_SECRET_26173"): Boolean {
        if (checksum.isBlank()) return true
        return checksum == calculateChecksum(secretKey)
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
            text = "ACK:${this.messageId}",
            isAlert = false,
            timestamp = System.currentTimeMillis(),
            hopCount = 0,
            maxHops = this.maxHops
        ).withChecksum()
    }

    fun createForwardedPacket(): TextPacket {
        return this.copy(
            hopCount = this.hopCount + 1,
            type = if (this.type == PacketType.DATA) PacketType.RELAY else this.type
        ).withChecksum()
    }

    fun toJson(): String {
        return gson.toJson(this)
    }

    fun toDelimitedBytes(): ByteArray {
        val jsonBytes = toJson().toByteArray(StandardCharsets.UTF_8)
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
