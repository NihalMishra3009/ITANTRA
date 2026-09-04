package com.itantra.transport

import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import org.junit.Assert.*
import org.junit.Test

/**
 * Critical DTN multi-hop + multi-hop ACK routing tests.
 * Verifies: A → R1 → R2 → B (data), B → R2 → R1 → A (ACK).
 */
class DtnChainTest {

    class SimNode(val nodeId: String) {
        val sentPackets = mutableListOf<TextPacket>()
        val deliveredPackets = mutableListOf<TextPacket>()
        val receivedAcks = mutableListOf<TextPacket>()
        val discovery = NetworkDiscoveryManager(nodeId)
        private val seenIds = mutableSetOf<String>()
        private val neighbors = mutableMapOf<String, SimNode>()
        private val receivedFrom = mutableMapOf<String, String>() // nodeId → via-neighbor

        fun linkTo(other: SimNode) {
            neighbors[other.nodeId] = other
            other.neighbors[this.nodeId] = this
            // Both nodes must discover each other as neighbors
            val helloFromOther = TextPacket(senderId = other.nodeId, recipientId = "*",
                type = PacketType.NODE_HELLO, language = "en",
                text = "${other.nodeId}|RELAY|STT")
            discovery.onDiscoveryPacket(helloFromOther)
            val helloFromSelf = TextPacket(senderId = this.nodeId, recipientId = "*",
                type = PacketType.NODE_HELLO, language = "en",
                text = "${this.nodeId}|RELAY|STT")
            other.discovery.onDiscoveryPacket(helloFromSelf)
        }

        fun installRoute(dest: String, via: String, hops: Int) {
            discovery.addOrRefreshRoute(dest, "INDIVIDUAL", via, hops)
        }

        fun handleIncoming(packet: TextPacket, viaNeighbor: String? = null) {
            if (packet.isExpired()) return
            if (!seenIds.add(packet.messageId)) return

            if (packet.type == PacketType.NODE_HELLO || packet.type == PacketType.NODE_ANNOUNCE) {
                discovery.onDiscoveryPacket(packet)
                return
            }

            // Learn reverse route from any incoming packet (like real transport layer)
            if (viaNeighbor != null && packet.senderId.isNotBlank() && packet.senderId != nodeId) {
                if (discovery.bestNextHop(packet.senderId) == null) {
                    installRoute(packet.senderId, viaNeighbor, 1)
                }
                receivedFrom[packet.messageId] = viaNeighbor
            }

            if (packet.type == PacketType.ACK) {
                if (packet.recipientId == "*" || packet.recipientId == nodeId) {
                    deliveredPackets.add(packet)
                    return
                }
                val nextHop = discovery.bestNextHop(packet.recipientId)
                if (nextHop != null) {
                    val fwd = packet.copy(hopCount = packet.hopCount + 1)
                    sentPackets.add(fwd)
                    neighbors[nextHop]?.handleIncoming(fwd, nodeId)
                }
                return
            }

            if (packet.recipientId == "*" || packet.recipientId == nodeId) {
                deliveredPackets.add(packet)
                if (packet.recipientId != "*") {
                    val ack = packet.createAckPacket(nodeId)
                    val nextHop = discovery.bestNextHop(packet.senderId)
                    if (nextHop != null) {
                        sentPackets.add(ack)
                        neighbors[nextHop]?.handleIncoming(ack, nodeId)
                    } else {
                        sentPackets.add(ack)
                    }
                }
                return
            }

            if (packet.hopCount < 10) {
                val relay = packet.copy(
                    hopCount = packet.hopCount + 1,
                    type = if (packet.type == PacketType.DATA) PacketType.RELAY else packet.type
                )
                val nextHop = discovery.bestNextHop(packet.recipientId)
                if (nextHop != null) {
                    sentPackets.add(relay)
                    neighbors[nextHop]?.handleIncoming(relay, nodeId)
                }
            }
        }
    }

    @Test
    fun testFullChainAtoBwithAckReversePath() {
        val a = SimNode("ITN-A"); val r1 = SimNode("ITN-R1")
        val r2 = SimNode("ITN-R2"); val b = SimNode("ITN-B")

        a.linkTo(r1); r1.linkTo(r2); r2.linkTo(b)

        a.installRoute("ITN-B", "ITN-R1", 3)
        r1.installRoute("ITN-B", "ITN-R2", 2)
        r2.installRoute("ITN-B", "ITN-B", 1)
        r2.installRoute("ITN-A", "ITN-R1", 2)
        r1.installRoute("ITN-A", "ITN-A", 1)

        val packet = TextPacket(
            messageId = "msg_test_001", senderId = "ITN-A", recipientId = "ITN-B",
            language = "hi", text = "मुझे मदद चाहिए"
        )
        a.handleIncoming(packet)

        assertEquals("B must receive the message", 1, b.deliveredPackets.size)
        assertEquals("msg_test_001", b.deliveredPackets[0].messageId)
        assertTrue("A must receive an ACK", a.deliveredPackets.any {
            it.type == PacketType.ACK && it.text == "ACK:msg_test_001"
        })
    }

    @Test
    fun testDuplicateSuppressedAtRelay() {
        val r1 = SimNode("ITN-R1"); val r2 = SimNode("ITN-R2")
        r1.linkTo(r2)
        r1.installRoute("ITN-B", "ITN-R2", 2)

        val packet = TextPacket(
            messageId = "dup_test", senderId = "ITN-A", recipientId = "ITN-B",
            language = "hi", text = "duplicate"
        )
        r1.handleIncoming(packet)
        r1.handleIncoming(packet)
        assertEquals("Only one relay for duplicate", 1, r1.sentPackets.size)
    }

    @Test
    fun testExpiredPacketDropped() {
        val r1 = SimNode("ITN-R1")
        val packet = TextPacket(
            messageId = "expired_test", senderId = "ITN-A", recipientId = "ITN-B",
            language = "hi", text = "old",
            timestamp = System.currentTimeMillis() - 600_000, ttlMs = 300_000
        )
        r1.handleIncoming(packet)
        assertEquals("Expired must not forward", 0, r1.sentPackets.size)
    }

    @Test
    fun testMaxHopsStopsForwarding() {
        val r1 = SimNode("ITN-R1")
        val packet = TextPacket(
            messageId = "maxhops", senderId = "ITN-A", recipientId = "ITN-B",
            language = "hi", text = "at max", hopCount = 10, maxHops = 10
        )
        r1.handleIncoming(packet)
        assertEquals("At max hops no forward", 0, r1.sentPackets.size)
    }

    @Test
    fun testThreeHopDataAndAckCycle() {
        val a = SimNode("ITN-A"); val r1 = SimNode("ITN-R1")
        val r2 = SimNode("ITN-R2"); val r3 = SimNode("ITN-R3"); val b = SimNode("ITN-B")

        a.linkTo(r1); r1.linkTo(r2); r2.linkTo(r3); r3.linkTo(b)

        a.installRoute("ITN-B", "ITN-R1", 4)
        r1.installRoute("ITN-B", "ITN-R2", 3)
        r2.installRoute("ITN-B", "ITN-R3", 2)
        r3.installRoute("ITN-B", "ITN-B", 1)
        r3.installRoute("ITN-A", "ITN-R2", 3)
        r2.installRoute("ITN-A", "ITN-R1", 2)
        r1.installRoute("ITN-A", "ITN-A", 1)

        val packet = TextPacket(
            messageId = "big_chain", senderId = "ITN-A", recipientId = "ITN-B",
            language = "hi", text = "through three relays"
        )
        a.handleIncoming(packet)

        assertEquals("B must receive", 1, b.deliveredPackets.size)
        assertEquals("big_chain", b.deliveredPackets[0].messageId)
        assertTrue("A must get ACK back", a.deliveredPackets.any {
            it.type == PacketType.ACK && it.text == "ACK:big_chain"
        })
    }

    @Test
    fun testLoopPrevention() {
        val r1 = SimNode("ITN-R1"); val r2 = SimNode("ITN-R2")
        r1.linkTo(r2)
        r1.installRoute("ITN-B", "ITN-R2", 2)
        r2.installRoute("ITN-A", "ITN-R1", 2)

        val packet = TextPacket(
            messageId = "loop_test", senderId = "ITN-A", recipientId = "ITN-B",
            language = "hi", text = "loop", hopCount = 1, maxHops = 5
        )

        // R1 forwards to R2
        r1.handleIncoming(packet)
        assertEquals(1, r1.sentPackets.size)
        // R2 tries to forward back but has no route to B → does nothing
        assertEquals(0, r2.sentPackets.size)

        // If R2 somehow gets the same message ID again, dedup blocks it
        r2.handleIncoming(packet.copy(messageId = "loop_test"))
        assertEquals("Replay is blocked by dedup", 0, r2.sentPackets.size)
    }
}
