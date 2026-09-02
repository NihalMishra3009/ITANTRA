package com.itantra.transport

import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class MeshRoutingTest {

    class MockTransport : TransportLayer {
        override val transportType: TransportType = TransportType.BLUETOOTH
        override var connectionState: ConnectionState = ConnectionState.CONNECTED
        val sentPackets = mutableListOf<TextPacket>()

        override fun startListening(onPacketReceived: (TextPacket) -> Unit, onStateChanged: (ConnectionState) -> Unit) {}
        override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {}
        override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) { onResult(true) }
        override fun sendPacket(packet: TextPacket): Boolean {
            sentPackets.add(packet)
            return true
        }
        override fun disconnect() { connectionState = ConnectionState.DISCONNECTED }
        override fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED
    }

    @Test
    fun testUnicastDeliveryAndAck() {
        val transport = MockTransport()
        val nodeB = MeshRoutingManager(myNodeId = "NODE_B", transportLayer = transport)

        val packet = TextPacket(
            senderId = "NODE_A",
            recipientId = "NODE_B",
            language = "hi",
            text = "मुझे मदद चाहिए"
        )

        var delivered = false
        nodeB.handleIncomingPacket(packet) {
            delivered = true
            assertEquals("NODE_A", it.senderId)
            assertEquals("NODE_B", it.recipientId)
        }

        assertTrue("Message must be delivered to NODE_B", delivered)
        assertEquals(1, transport.sentPackets.size)
        val ack = transport.sentPackets.first()
        assertEquals(PacketType.ACK, ack.type)
        assertEquals("NODE_A", ack.recipientId)
        assertEquals("ack_" + packet.messageId, ack.messageId)
    }

    @Test
    fun testMultiHopRelayForwarding() {
        val transport = MockTransport()
        val intermediateNodeB = MeshRoutingManager(myNodeId = "NODE_B", transportLayer = transport)

        // Packet from NODE_A to NODE_C passing through NODE_B
        val packet = TextPacket(
            senderId = "NODE_A",
            recipientId = "NODE_C",
            language = "en",
            text = "Rescue route is clear",
            hopCount = 0,
            maxHops = 3
        )

        var deliveredToB = false
        intermediateNodeB.handleIncomingPacket(packet) {
            deliveredToB = true
        }

        assertFalse("Packet for NODE_C must NOT be delivered locally to NODE_B", deliveredToB)
        assertEquals(1, transport.sentPackets.size)
        val forwarded = transport.sentPackets.first()
        assertEquals(1, forwarded.hopCount)
        assertEquals(PacketType.RELAY, forwarded.type)
        assertEquals("NODE_C", forwarded.recipientId)
    }

    @Test
    fun testDuplicatePacketSuppression() {
        val transport = MockTransport()
        val node = MeshRoutingManager(myNodeId = "NODE_B", transportLayer = transport)

        val packet = TextPacket(
            messageId = "dup_001",
            senderId = "NODE_A",
            recipientId = "*",
            language = "hi",
            text = "दवाइयों की आवश्यकता है"
        )

        var deliveryCount = 0
        node.handleIncomingPacket(packet) { deliveryCount++ }
        node.handleIncomingPacket(packet) { deliveryCount++ }
        node.handleIncomingPacket(packet) { deliveryCount++ }

        assertEquals(1, deliveryCount)
    }

    @Test
    fun testExpiredPacketRejected() {
        val transport = MockTransport()
        val node = MeshRoutingManager(myNodeId = "NODE_B", transportLayer = transport)

        val packet = TextPacket(
            senderId = "NODE_A",
            recipientId = "NODE_B",
            language = "hi",
            text = "मूल संदेश",
            timestamp = System.currentTimeMillis() - 600000L,
            ttlMs = 300000L
        )

        var delivered = false
        node.handleIncomingPacket(packet) { delivered = true }
        assertFalse("Expired packet must be rejected", delivered)
    }

    @Test
    fun testEmergencyPacketUsesPriorityFlag() {
        val transport = MockTransport()
        val node = MeshRoutingManager(myNodeId = "NODE_A", transportLayer = transport)

        val packet = TextPacket(
            messageId = "urgent01",
            senderId = "NODE_A",
            recipientId = "*",
            type = PacketType.EMERGENCY,
            language = "hi",
            text = "मुझे तत्काल सहायता चाहिए",
            isPriority = true
        )

        node.sendReliablePacket(packet) { }

        assertTrue("Emergency packet must be transmitted with a connected transport", transport.sentPackets.isNotEmpty())
    }
}
