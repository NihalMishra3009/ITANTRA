package com.itantra.transport

import android.util.Log
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import com.itantra.security.ReplayProtection
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
    private val outboxDao: OutboxDao? = null,
    val discovery: NetworkDiscoveryManager? = null,
    val deliveryTracker: DeliveryTracker? = null
) {
    companion object {
        private const val TAG = "MeshRoutingManager"
        private const val RETRY_INTERVAL_BASE_MS = 2000L
    }

    private val scopeJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + scopeJob)

    /**
     * Single-threaded dispatcher for every outbox DB write. Insert and delete for
     * one message are launched from different call sites; on the shared IO pool they
     * can run out of order, and a delete that lands before its insert leaves an
     * orphaned row that is restored on every restart. Serializing keeps them FIFO.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
    
    // Outbox: Messages waiting for delivery or ACK (deque allows priority prepend)
    private val outboxQueue = ConcurrentLinkedDeque<QueuedMessage>()
    
    // Replay protection: per-peer seen-message tracking, bounded + TTL'd.
    // Rejecting a duplicate stops relay loops and replayed packets. When the
    // duplicate reaches its DESTINATION, the destination re-ACKs (see below) so a
    // lost-ACK retransmission still completes instead of being silently dropped.
    private val replayProtection = ReplayProtection()

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
        deliveryTracker?.track(packet, DeliveryStatus.QUEUED)
        if (isEmergency) {
            // Emergency: transmit immediately, preempt normal queue
            outboxQueue.addFirst(queued)
        } else {
            outboxQueue.add(queued)
        }

        // Persist to disk so the message survives app restart (store-and-forward)
        val dao = outboxDao
        if (dao != null) {
            coroutineScope.launch(dbDispatcher) {
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
                    deliveryTracker?.update(packet.messageId, DeliveryStatus.STORED)
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
     * Integrity is verified at the transport boundary (BinaryPacketCodec HMAC); here we enforce TTL + dedup + loop prevention.
     */
    fun handleIncomingPacket(packet: TextPacket, onLocalDeliver: (TextPacket) -> Unit) {
        // 1. Verify TTL
        if (packet.isExpired()) {
            Log.w(TAG, "Rejected expired packet: ${packet.messageId}")
            return
        }

        // 3. Handle ACK packets — ACK itself may need multi-hop routing back to origin.
        if (packet.type == PacketType.ACK) {
            val targetMsgId = packet.text.removePrefix("ACK:")
            Log.i(TAG, "Received ACK for message: $targetMsgId from ${packet.senderId}")

            // Am I the target of this ACK? (i.e. I am the original sender)
            if (packet.recipientId == "*" || packet.recipientId.equals(myNodeId, ignoreCase = true)) {
                // ACK is for me: mark as acknowledged and consume.
                outboxQueue.find { it.packet.messageId == targetMsgId }?.let {
                    it.isAcknowledged = true
                    outboxQueue.remove(it)
                }
                deletePersisted(targetMsgId)
                deliveryTracker?.update(targetMsgId, DeliveryStatus.ACKNOWLEDGED)
                pendingAcks[targetMsgId]?.complete(true)
                return
            }
            // ACK is NOT for me: I am a relay. Forward it via routing table toward original sender.
            forwardPacketViaRoute(packet)
            return
        }

        // 3b. Handle network-discovery / routing-control packets
        if (packet.type == PacketType.NODE_HELLO ||
            packet.type == PacketType.NODE_ANNOUNCE ||
            packet.type == PacketType.ROUTE_REQUEST ||
            packet.type == PacketType.ROUTE_RESPONSE ||
            packet.type == PacketType.ROUTE_UPDATE ||
            packet.type == PacketType.LOCATION_UPDATE
        ) {
            discovery?.onDiscoveryPacket(packet)
            return
        }

        // 4. Check Addressing: Am I the destination or broadcast?
        val isForMe = packet.recipientId == "*" ||
                packet.recipientId.equals(myNodeId, ignoreCase = true) ||
                packet.isGroupOrZone

        if (isForMe) {
            Log.i(TAG, "Packet ${packet.messageId} delivered locally to $myNodeId")
            deliveryTracker?.track(packet, DeliveryStatus.DELIVERED, packet.hopCount)

            // Replay protection is enforced at the DESTINATION only. A relay must
            // pass retransmissions through (the sender's outbox retries the same
            // messageId), so replays are checked exactly where the packet is
            // consumed. TTL + hopCount still bound relay/loop behaviour.
            if (packet.type != PacketType.ACK) {
                if (!replayProtection.isNew(packet.senderId, packet.messageId, packet.timestamp)) {
                    // A duplicate that reaches its DESTINATION: re-ACK so a lost-ACK
                    // retransmission still completes rather than silently dying.
                    if (packet.recipientId != "*" && !packet.isGroupOrZone) {
                        Log.i(TAG, "Duplicate ${packet.messageId} at destination — re-ACK")
                        sendReliablePacket(packet.createAckPacket(myNodeId))
                    }
                    Log.d(TAG, "Replay/duplicate packet ${packet.messageId} ignored (destination dedup)")
                    return
                }
            }

            // Phase 8: ACK must route back through routing table, NOT via raw sendPacket.
            if (packet.recipientId != "*" && !packet.isGroupOrZone) {
                val ackPacket = packet.createAckPacket(myNodeId)
                // Queue ACK via the routing table so it hops back A ← R1 ← R2 ← B
                sendReliablePacket(ackPacket)
                Log.i(TAG, "ACK for ${packet.messageId} queued via routing table (back to ${packet.senderId})")
            }

            // Transport boundary already hop-decrypted the payload, so packet.text is
            // the plaintext — deliver directly. (Replay protection is enforced at the
            // dedup step above; this is the single local-consumption path.)
            onLocalDeliver(packet)
        } else {
            // 5. Multi-Hop Intermediate Relay Forwarding — route-aware, next-hop targeted.
            forwardPacketViaRoute(packet)
        }
    }

    /**
     * Phase 6 + 13: Forward a packet toward its destination using the routing table.
     * Next-hop targeted (sendToPeer), with loop prevention (hopCount/maxHops check already
     * done by dedup cache + TTL).
     */
    private fun forwardPacketViaRoute(packet: TextPacket) {
        if (packet.hopCount >= packet.maxHops) {
            Log.w(TAG, "Packet ${packet.messageId} dropped: max hops (${packet.maxHops}) reached (hop=${packet.hopCount})")
            deliveryTracker?.update(packet.messageId, DeliveryStatus.FAILED, packet.hopCount)
            return
        }

        val relayPacket = packet.createForwardedPacket()

        // Route-aware next-hop selection
        val routeNextHop = discoverRoute(packet.recipientId)
        val sent = if (routeNextHop != null) {
            // Phase 6: send to specific next-hop peer, not broadcast.
            Log.i(TAG, "Relaying ${packet.messageId} (hop ${relayPacket.hopCount}/${relayPacket.maxHops}) → via $routeNextHop")
            sendToPeer(routeNextHop, relayPacket)
        } else {
            // No route: fall back to broadcast (hope a neighbor forwards it).
            Log.w(TAG, "Relaying ${packet.messageId} (hop ${relayPacket.hopCount}/${relayPacket.maxHops}) → BROADCAST (no route)")
            sendDirectPacket(relayPacket)
        }

        if (sent) {
            deliveryTracker?.update(packet.messageId, DeliveryStatus.FORWARDED, relayPacket.hopCount)
            discovery?.markDeliverySuccess(packet.recipientId, routeNextHop)
        } else {
            deliveryTracker?.update(packet.messageId, DeliveryStatus.FAILED, relayPacket.hopCount)
            discovery?.markDeliveryFailure(packet.recipientId, routeNextHop)
        }
    }

    /**
     * [processOutbox] runs both on the 1 Hz worker and inline from
     * [sendReliablePacket]. Without this guard the two can walk the deque at once
     * and both bump the same item's retryCount, transmitting one message twice per
     * backoff window. Single-flight: a concurrent caller simply skips the pass.
     */
    private val outboxPassRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun processOutbox() {
        if (!transportLayer.isConnected()) {
            // Destination or link offline -> Store-and-forward keeps messages safe in outboxQueue
            return
        }
        if (!outboxPassRunning.compareAndSet(false, true)) return
        try {
            processOutboxPass()
        } finally {
            outboxPassRunning.set(false)
        }
    }

    private fun processOutboxPass() {
        val now = System.currentTimeMillis()
        val iterator = outboxQueue.iterator()

        while (iterator.hasNext()) {
            val item = iterator.next()

            if (item.isAcknowledged || item.packet.isExpired()) {
                if (item.packet.isExpired()) {
                    deliveryTracker?.update(item.packet.messageId, DeliveryStatus.EXPIRED)
                }
                iterator.remove()
                // Drop the persisted row too. Without this, an expired message is
                // removed from the queue but its Room row survives, so every restart
                // restores it again and the outbox table grows without bound.
                deletePersisted(item.packet.messageId)
                continue
            }

            val backoffMs = RETRY_INTERVAL_BASE_MS * (1 shl item.retryCount)
            if (now - item.lastAttemptTimestamp >= backoffMs) {
                if (item.retryCount < item.maxRetries) {
                    item.lastAttemptTimestamp = now
                    item.retryCount++
                    Log.i(TAG, "Transmitting queued packet ${item.packet.messageId} (Attempt ${item.retryCount}/${item.maxRetries})")
                    deliveryTracker?.update(item.packet.messageId, DeliveryStatus.FORWARDING, item.packet.hopCount)

                    // Phase 6: route-aware send — next-hop targeted if available
                    val routeNextHop = discoverRoute(item.packet.recipientId)
                    val sent = if (routeNextHop != null) {
                        sendToPeer(routeNextHop, item.packet)
                    } else {
                        sendDirectPacket(item.packet)
                    }

                    if (sent && item.packet.recipientId == "*") {
                        // Broadcast packets don't expect ACKs
                        iterator.remove()
                        deletePersisted(item.packet.messageId)
                    } else {
                        // Persist retry attempt so it survives restart
                        val dao2 = outboxDao
                        if (dao2 != null) {
                            coroutineScope.launch(dbDispatcher) {
                                try { dao2.updateAttempt(item.packet.messageId, item.retryCount, now) } catch (e: Exception) { /* ignore */ }
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Message ${item.packet.messageId} exceeded max retries. Kept in store-and-forward pending reconnect.")
                    if (item.packet.isExpired()) {
                        iterator.remove()
                        deletePersisted(item.packet.messageId)
                    }
                }
            }
        }
    }

    /** Remove a message from the persistent outbox, if persistence is configured. */
    private fun deletePersisted(messageId: String) {
        val dao = outboxDao ?: return
        coroutineScope.launch(dbDispatcher) {
            try { dao.delete(messageId) } catch (e: Exception) { Log.w(TAG, "outbox delete failed: $messageId", e) }
        }
    }

    /** Phase 6: Send a packet to a specific next-hop peer node ID. */
    private fun sendToPeer(nodeId: String, packet: TextPacket): Boolean {
        return transportLayer.sendToPeer(nodeId, packet)
    }

    /** Fallback: send via any connected transport (broadcast / unknown peer). */
    private fun sendDirectPacket(packet: TextPacket): Boolean {
        return transportLayer.sendPacket(packet)
    }

    fun getOutboxSize(): Int = outboxQueue.size

    /**
     * Look up a known route to a destination in the discovery routing table.
     * Returns the next-hop node ID (the directly-reachable neighbor), or null
     * if no valid route is known (blind relay fallback).
     */
    private fun discoverRoute(destinationId: String): String? {
        val d = discovery ?: return null
        return d.bestNextHop(destinationId)
    }

    /** Snapshot of the current outbox contents (for UI delivery visibility). */
    fun getOutboxSnapshot(): List<QueuedMessage> = outboxQueue.toList()

    fun release() {
        // Phase 8: cancel the COMPLETE scope (all coroutines, incl. retry watchers),
        // not just the worker job. Idempotent: cancelling an already-cancelled job
        // is a no-op.
        workerJob?.cancel()
        workerJob = null
        scopeJob.cancel()
        outboxQueue.clear()
        pendingAcks.values.forEach { it.complete(false) }
        pendingAcks.clear()
    }
}
