package com.itantra.security

import android.util.Log

/**
 * Per-peer replay protection for iTantra wire packets.
 *
 * A duplicate is identified by (senderId, messageId): a packet the local node
 * already accepted once is rejected on re-play. Tracking is keyed PER PEER so a
 * replay of packet "X" from peer A does not collide with an unrelated packet
 * "X" from peer B (peers may independently use short ids).
 *
 * Memory is bounded: each peer's seen-list is capped at [maxEntriesPerPeer] and
 * entries older than [TTL_MS] are pruned on access, so the cache never grows
 * forever and retransmissions inside the TTL window keep working.
 *
 * This complements the HMAC: an old-session replay fails HMAC at the transport
 * before it ever reaches this cache (keys rotate per handshake). It also rejects
 * packets whose timestamps are too old relative to the local clock (expired).
 */
class ReplayProtection(
    private val maxEntriesPerPeer: Int = 256,
    private val ttlMs: Long = TEXT_PACKET_TTL_MS
) {
    companion object {
        private const val TAG = "ReplayProtection"
        private const val TEXT_PACKET_TTL_MS = 300_000L
        private const val MAX_CLOCK_SKEW_MS = 60_000L
    }

    private val seen = HashMap<String, java.util.LinkedHashMap<String, Long>>()

    /**
     * @return true if this packet is NEW (should be accepted); false if it is a
     *         replay/duplicate/expired-id and must be rejected.
     */
    @Synchronized
    fun isNew(senderId: String, messageId: String, packetTs: Long): Boolean {
        if (messageId.isBlank()) return false
        val now = System.currentTimeMillis()
        // Reject obviously-old timestamps (expired packet). A tiny clock skew is
        // tolerated. This is the packet-lifetime guard, separate from dedup.
        val age = now - packetTs
        if (age > ttlMs) {
            Log.d(TAG, "Reject expired packet '$messageId' from $senderId (age=${age}ms)")
            return false
        }
        if (-age > MAX_CLOCK_SKEW_MS) {
            Log.d(TAG, "Reject future-dated packet '$messageId' from $senderId (skew=${age}ms)")
            return false
        }

        val peerList = seen.getOrPut(senderId) { java.util.LinkedHashMap() }
        // Prune stale entries for this peer (bounded memory).
        val iter = peerList.entries.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > ttlMs) iter.remove()
        }
        // Reject if already seen within TTL.
        if (peerList.containsKey(messageId)) {
            Log.d(TAG, "Reject replay '$messageId' from $senderId (duplicate within ${ttlMs}ms)")
            return false
        }
        // Bound per-peer memory.
        while (peerList.size >= maxEntriesPerPeer) {
            val eldest = peerList.entries.firstOrNull()?.key ?: break
            peerList.remove(eldest)
        }
        peerList[messageId] = now
        return true
    }

    @Synchronized
    fun clearAll() = seen.clear()
}