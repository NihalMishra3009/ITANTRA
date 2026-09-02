package com.itantra.transport

import android.util.Log
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

data class QueuedMessage(
    val packet: TextPacket,
    var retryCount: Int = 0,
    val maxRetries: Int = 3,
    var lastAttemptTimestamp: Long = 0L,
    var isAcknowledged: Boolean = false
)

/**
 * Mesh Routing, Store-and-Forward, and Reliability Manager for iTantra.
 *
 * Outbox is persistent: messages are written to a Room DB so they survive
 * app/process restart wire [outboxDao] is supplied. Falls back to in-memory
 * only when offline persistence is unavailable.
 */
class MeshRoutingManager(
    val myNodeId: String,
    private val transportLayer: TransportLayer,
    private val outboxDao: OutboxDao? = null
) {
    companion object {
        private const val TAG = "MeshRoutingManager"
        private const val RETRY_INTERVAL_BASE_MS = 2000L
        private const val SEEN_CACHE_MAX_SIZE = 500
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Outbox: Messages waiting for delivery or ACK (deque allows priority prepend)
    private val outboxQueue = ConcurrentLinkedDeque<QueuedMessage>()
    
    // Seen messages deduplication cache
    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()

    // Unacknowledged outbound messages: messageId -> CompletableDeferred<Boolean>
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private var workerJob: Job? = null

    init {
        if (outboxDao != null) {
            coroutineScope.launch { restorePersistentOutbox() }
        }
        startQueueWorker()
    }

    /** Reload undelivered messages from persistent storage after restart. */
    private suspend fun restorePersistentOutbox() {
        val dao = outboxDao ?: return
        try {
            val pending = dao.pendingMessages()
            for (entity in pending) {
                val packet = TextPacket.fromJson(entity.packetJson) ?: continue
                outboxQueue.add(
                    QueuedMessage(
                        packet = packet,
                        retryCount = entity.retryCount,
                        lastAttemptTimestamp = entity.lastAttempt,
                        isAcknowledged = false
                    )
                )
            }
            if (pending.isNotEmpty()) Log.i(TAG, "Restored ${pending.size} persisted messages to outbox")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore persistent outbox", e)
        }
    }

    fun startQueueWorker() {
        workerJob?.cancel()
        workerJob = coroutineScope.launch {
            while (isActive) {
                delay(1000)
                processOutbox()
            }
        }
    }

    /**
     * Sends a packet with reliability, ACK tracking, and store-and-forward fallback.
     * Emergency/priority packets bypass the queue and are sent immediately.
     */
    fun sendReliablePacket(packet: TextPacket, onAckReceived: ((Boolean) -> Unit)? = null) {
        val isEmergency = packet.type == PacketType.EMERGENCY || packet.isPriority
        val queued = QueuedMessage(packet = packet)
        if (isEmergency) {
            // Emergency: transmit immediately, preempt normal queue
            outboxQueue.addFirst(queued)
        } else {
            outboxQueue.add(queued)
        }

        // Persist to disk so the message survives app restart (store-and-forward)
        val dao = outboxDao
        if (dao != null) {
            coroutineScope.launch {
                try {
                    dao.insert(
                        OutboxEntity(
                            messageId = packet.messageId,
                            packetJson = packet.toJson(),
                            createdAt = packet.timestamp,
                            retryCount = 0,
                            lastAttempt = 0L,
                            isAcknowledged = false
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist outbox message", e)
                }
            }
        }

        if (onAckReceived != null && packet.type != PacketType.ACK) {
            val deferred = CompletableDeferred<Boolean>()
            pendingAcks[packet.messageId] = deferred
            coroutineScope.launch {
                try {
                    val acked = withTimeoutOrNull(packet.ttlMs) {
                        deferred.await()
                    } ?: false
                    withContext(Dispatchers.Main) { onAckReceived(acked) }
                } finally {
                    pendingAcks.remove(packet.messageId)
                }
            }
        }

        // Trigger immediate send attempt
        if (isEmergency || transportLayer.isConnected()) {
            processOutbox()
        }
    }

    /**
     * Handles incoming packet: Deduplication, ACK generation, Intermediate Multi-hop Forwarding, or Local Consumption.
     * Integrity is verified at the transport boundary (BinaryPacketCodec HMAC); here we enforce TTL + dedup.
     */
    fun handleIncomingPacket(packet: TextPacket, onLocalDeliver: (TextPacket) -> Unit) {
        // 1. Verify TTL
        if (packet.isExpired()) {
            Log.w(TAG, "Rejected expired packet: ${packet.messageId}")
            return
        }

        // 2. Deduplication check
        if (!seenMessageIds.add(packet.messageId)) {
            Log.d(TAG, "Duplicate packet ${packet.messageId} ignored")
            return
        }
        if (seenMessageIds.size > SEEN_CACHE_MAX_SIZE) {
            seenMessageIds.clear()
            seenMessageIds.add(packet.messageId)
        }

        // 3. Handle ACK packets
        if (packet.type == PacketType.ACK) {
            val targetMsgId = packet.text.removePrefix("ACK:")
            Log.i(TAG, "Received ACK for message: $targetMsgId from ${packet.senderId}")
            
            // Mark message acknowledged in outbox (memory + disk)
            outboxQueue.find { it.packet.messageId == targetMsgId }?.let {
                it.isAcknowledged = true
                outboxQueue.remove(it)
            }
            val dao = outboxDao
            if (dao != null) {
                coroutineScope.launch {
                    try { dao.delete(targetMsgId) } catch (e: Exception) { /* ignore */ }
                }
            }
            pendingAcks[targetMsgId]?.complete(true)
            return
        }

        // 4. Check Addressing: Am I the destination or broadcast?
        val isForMe = packet.recipientId == "*" || packet.recipientId.equals(myNodeId, ignoreCase = true)

        if (isForMe) {
            Log.i(TAG, "Packet ${packet.messageId} delivered locally to $myNodeId")
            
            // Automatically send ACK back to sender if unicast
            if (packet.recipientId != "*") {
                val ackPacket = packet.createAckPacket(myNodeId)
                transportLayer.sendPacket(ackPacket)
                Log.i(TAG, "Dispatched ACK back to ${packet.senderId} for ${packet.messageId}")
            }

            // Decrypt payload for local consumption
            val decryptedPacket = packet.withDecryption()
            onLocalDeliver(decryptedPacket)
        } else {
            // 5. Multi-Hop Intermediate Relay Forwarding
            if (packet.hopCount < packet.maxHops) {
                val relayPacket = packet.createForwardedPacket()
                Log.i(TAG, "Relaying packet ${packet.messageId} (hop ${relayPacket.hopCount}/${packet.maxHops}) to destination ${packet.recipientId}")
                sendReliablePacket(relayPacket)
            } else {
                Log.w(TAG, "Packet ${packet.messageId} dropped: Max hops (${packet.maxHops}) reached")
            }
        }
    }

    private fun processOutbox() {
        if (!transportLayer.isConnected()) {
            // Destination or link offline -> Store-and-forward keeps messages safe in outboxQueue
            return
        }

        val now = System.currentTimeMillis()
        val iterator = outboxQueue.iterator()

        while (iterator.hasNext()) {
            val item = iterator.next()

            if (item.isAcknowledged || item.packet.isExpired()) {
                iterator.remove()
                continue
            }

            val backoffMs = RETRY_INTERVAL_BASE_MS * (1 shl item.retryCount)
            if (now - item.lastAttemptTimestamp >= backoffMs) {
                if (item.retryCount < item.maxRetries) {
                    item.lastAttemptTimestamp = now
                    item.retryCount++
                    Log.i(TAG, "Transmitting queued packet ${item.packet.messageId} (Attempt ${item.retryCount}/${item.maxRetries})")
                    val sent = transportLayer.sendPacket(item.packet)
                    if (sent && item.packet.recipientId == "*") {
                        // Broadcast packets don't expect ACKs
                        iterator.remove()
                        val dao = outboxDao
                        if (dao != null) {
                            coroutineScope.launch {
                                try { dao.delete(item.packet.messageId) } catch (e: Exception) { /* ignore */ }
                            }
                        }
                    } else {
                        // Persist retry attempt so it survives restart
                        val dao2 = outboxDao
                        if (dao2 != null) {
                            coroutineScope.launch {
                                try { dao2.updateAttempt(item.packet.messageId, item.retryCount, now) } catch (e: Exception) { /* ignore */ }
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Message ${item.packet.messageId} exceeded max retries. Kept in store-and-forward pending reconnect.")
                    if (item.packet.isExpired()) {
                        iterator.remove()
                        val dao = outboxDao
                        if (dao != null) {
                            coroutineScope.launch {
                                try { dao.delete(item.packet.messageId) } catch (e: Exception) { /* ignore */ }
                            }
                        }
                    }
                }
            }
        }
    }

    fun getOutboxSize(): Int = outboxQueue.size

    fun release() {
        workerJob?.cancel()
    }
}
