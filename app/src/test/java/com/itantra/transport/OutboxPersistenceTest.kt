package com.itantra.transport

import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Store-and-forward persistence contract for the outbox.
 *
 * Covers two defects that only surface over a long-lived session:
 *  - an expired message left the in-memory queue but its persisted row survived,
 *    so every restart restored it again and the table grew without bound;
 *  - the 1 Hz queue worker and an inline send could walk the deque at once and
 *    both bump the same item's retryCount, transmitting it twice per window.
 */
class OutboxPersistenceTest {

    /** In-memory stand-in for the Room DAO (unit tests have no Android runtime). */
    class FakeOutboxDao : OutboxDao {
        val rows = ConcurrentHashMap<String, OutboxEntity>()
        /** Every id ever written, so a test can tell "not inserted yet" from "already cleaned up". */
        val everInserted = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        override suspend fun insert(message: OutboxEntity) {
            everInserted.add(message.messageId)
            rows[message.messageId] = message
        }
        override suspend fun pendingMessages(): List<OutboxEntity> = rows.values.filter { !it.isAcknowledged }
        override suspend fun deliveredMessages(): List<OutboxEntity> = rows.values.filter { it.isAcknowledged }
        override suspend fun updateAttempt(id: String, retry: Int, t: Long) {
            rows[id]?.let { it.retryCount = retry; it.lastAttempt = t }
        }
        override suspend fun markAcknowledged(id: String) { rows[id]?.isAcknowledged = true }
        override suspend fun delete(id: String) { rows.remove(id) }
    }

    /** Counts every transmission so duplicate sends are observable. */
    class CountingTransport : TransportLayer {
        val sends = AtomicInteger(0)
        override val transportType = TransportType.BLUETOOTH
        override var connectionState = ConnectionState.CONNECTED
        override fun startListening(onPacketReceived: (TextPacket) -> Unit, onStateChanged: (ConnectionState) -> Unit) {}
        override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {}
        override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {}
        override fun disconnect() { connectionState = ConnectionState.DISCONNECTED }
        override fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED
        override fun sendPacket(packet: TextPacket): Boolean { sends.incrementAndGet(); return true }
        override fun sendToPeer(nodeId: String, packet: TextPacket): Boolean { sends.incrementAndGet(); return true }
    }

    private fun packet(id: String, ttlMs: Long, ageMs: Long = 0L) = TextPacket(
        messageId = id,
        senderId = "A",
        recipientId = "B",
        type = PacketType.DATA,
        language = "en",
        text = "hello",
        timestamp = System.currentTimeMillis() - ageMs,
        ttlMs = ttlMs
    )

    /** Waits for the manager's IO coroutines to drain the DAO writes. */
    private fun settle(dao: FakeOutboxDao, predicate: (FakeOutboxDao) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(dao)) return true
            Thread.sleep(20)
        }
        return predicate(dao)
    }

    @Test
    fun expiredMessageIsAlsoRemovedFromPersistentOutbox() {
        val dao = FakeOutboxDao()
        val transport = CountingTransport()
        val mgr = MeshRoutingManager("A", transport, outboxDao = dao)
        try {
            // Already past its TTL when queued.
            mgr.sendReliablePacket(packet("m-expired", ttlMs = 1_000L, ageMs = 5_000L))

            assertTrue(
                "expired message must be dropped from the queue",
                settle(dao) { mgr.getOutboxSize() == 0 }
            )
            assertTrue(
                "expired message must not survive in the persistent outbox",
                settle(dao) { !it.rows.containsKey("m-expired") }
            )
        } finally {
            mgr.release()
        }
    }

    @Test
    fun acknowledgedMessageIsRemovedFromPersistentOutbox() {
        val dao = FakeOutboxDao()
        val mgr = MeshRoutingManager("A", CountingTransport(), outboxDao = dao)
        try {
            val p = packet("m-ack", ttlMs = 60_000L)
            mgr.sendReliablePacket(p)
            assertTrue(settle(dao) { it.rows.containsKey("m-ack") })

            mgr.handleIncomingPacket(p.createAckPacket("B")) { fail("ACK must not be delivered locally") }

            assertTrue(
                "ACKed message must not survive in the persistent outbox",
                settle(dao) { !it.rows.containsKey("m-ack") }
            )
        } finally {
            mgr.release()
        }
    }

    @Test
    fun concurrentSendsDoNotLeaveOrphanedPersistedRows() {
        val dao = FakeOutboxDao()
        val mgr = MeshRoutingManager("A", CountingTransport(), outboxDao = dao)
        try {
            // Every one of these is already past its TTL, so each is inserted and then
            // dropped. If the delete can overtake its own insert, rows are left behind.
            val start = CountDownLatch(1)
            val threads = (1..8).map { i ->
                Thread {
                    start.await()
                    mgr.sendReliablePacket(packet("m-orphan-$i", ttlMs = 1_000L, ageMs = 5_000L))
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join(3000) }

            // Wait until all 8 rows have actually been written, otherwise an empty
            // table would just mean the inserts had not run yet.
            assertTrue(
                "all messages must reach the persistent outbox",
                settle(dao) { it.everInserted.size == 8 }
            )
            assertTrue("queue must drain", settle(dao) { mgr.getOutboxSize() == 0 })
            assertTrue(
                "no expired message may be left behind in the persistent outbox",
                settle(dao) { it.rows.isEmpty() }
            )
        } finally {
            mgr.release()
        }
    }
}
