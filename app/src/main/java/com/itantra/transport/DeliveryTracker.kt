package com.itantra.transport

import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Delivery status of a message (driven by actual state, not animation).
 *
 * Lifecycle: CREATED -> QUEUED -> STORED -> FORWARDING -> FORWARDED ->
 *            DELIVERED -> PLAYING -> ACKNOWLEDGED
 * On failure/expiry: FAILED / EXPIRED
 */
enum class DeliveryStatus {
    CREATED,
    QUEUED,
    STORED,
    FORWARDING,
    FORWARDED,
    DELIVERED,
    PLAYING,
    ACKNOWLEDGED,
    FAILED,
    EXPIRED
}

data class MessageStatus(
    val messageId: String,
    val recipientId: String,
    val recipientMode: String,
    var status: DeliveryStatus,
    var hopCount: Int,
    var timestampMs: Long,
    var isEmergency: Boolean
)

/**
 * Tracks per-message delivery status for UI visibility and judging.
 * State is derived from real MeshRoutingManager / outbox events.
 */
class DeliveryTracker {

    private val statuses = ConcurrentHashMap<String, MessageStatus>()

    var onStatusChange: ((MessageStatus) -> Unit)? = null

    fun track(packet: TextPacket, status: DeliveryStatus, hopCount: Int = 0) {
        val entry = statuses.computeIfAbsent(packet.messageId) {
            MessageStatus(
                messageId = packet.messageId,
                recipientId = packet.recipientId,
                recipientMode = packet.addressMode.name,
                status = status,
                hopCount = hopCount,
                timestampMs = System.currentTimeMillis(),
                isEmergency = packet.type == PacketType.EMERGENCY || packet.isAlert
            )
        }
        entry.status = status
        entry.hopCount = hopCount
        onStatusChange?.invoke(entry)
    }

    fun update(messageId: String, status: DeliveryStatus, hopCount: Int? = null) {
        val entry = statuses[messageId] ?: return
        entry.status = status
        if (hopCount != null) entry.hopCount = hopCount
        onStatusChange?.invoke(entry)
    }

    fun getStatus(messageId: String): MessageStatus? = statuses[messageId]

    fun getAll(): List<MessageStatus> = statuses.values.toList()

    fun messageIdForRecipient(recipientId: String): List<MessageStatus> =
        statuses.values.filter { it.recipientId == recipientId }

    fun clear() = statuses.clear()
}
