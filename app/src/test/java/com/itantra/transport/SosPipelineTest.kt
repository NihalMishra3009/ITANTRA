package com.itantra.transport

import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 3 SOS tests — the emergency path must NOT require a microphone or STT.
 * The MeshRoutingManager is tested here (Orchestrator SOS is a thin wrapper that
 * builds a TEXT packet directly; microphone is never involved).
 */
class SosPipelineTest {

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

    /** The emergency packet is a plain TEXT packet — no audio, no STT required. */
    @Test
    fun testEmergencyPacketCarriesPresetText_NoMicNoStt() {
        val transport = MockTransport()
        val node = MeshRoutingManager(myNodeId = "NODE_A", transportLayer = transport)

        val sos = TextPacket(
            messageId = "sos_001",
            senderId = "NODE_A",
            recipientId = "*",
            type = PacketType.EMERGENCY,
            language = "hi",
            text = "SOS — Emergency assistance required",
            isAlert = true,
            isPriority = true
        )

        node.sendReliablePacket(sos) { }

        assertTrue("Emergency must be transmitted with a connected transport", transport.sentPackets.isNotEmpty())
        assertEquals(PacketType.EMERGENCY, transport.sentPackets[0].type)
        assertEquals("SOS — Emergency assistance required", transport.sentPackets[0].text)
    }

    /** Emergency goes to the FRONT of the queue (priority over normal DATA). */
    @Test
    fun testEmergencyGetsFrontOfQueuePriority() {
        // Disconnected transport so nothing sends immediately and the queue order
        // reflects the insertion order (priority prepend for emergency).
        val transport = MockTransport().apply { connectionState = ConnectionState.DISCONNECTED }
        val node = MeshRoutingManager(myNodeId = "NODE_A", transportLayer = transport)

        val normal = TextPacket(
            messageId = "normal_1", senderId = "NODE_A", recipientId = "*",
            type = PacketType.DATA, language = "hi", text = "normal traffic"
        )
        val sos = TextPacket(
            messageId = "sos_urgent", senderId = "NODE_A", recipientId = "*",
            type = PacketType.EMERGENCY, language = "hi", text = "help now",
            isAlert = true, isPriority = true
        )

        // Queue normal, then emergency — emergency must be processed first.
        node.sendReliablePacket(normal) { _ -> }
        node.sendReliablePacket(sos) { _ -> }

        val queued = node.getOutboxSnapshot()
        assertTrue(queued.isNotEmpty())
        assertEquals("sos_urgent", queued.first().packet.messageId)
    }

    /** Duplicate SOS arrives at the DESTINATION — still ACKed once (retransmission recovery). */
    @Test
    fun testDuplicateEmergencyStillAcked() {
        val transport = MockTransport()
        val nodeB = MeshRoutingManager(myNodeId = "NODE_B", transportLayer = transport)

        val sos = TextPacket(
            messageId = "sos_dup", senderId = "NODE_A", recipientId = "NODE_B",
            type = PacketType.EMERGENCY, language = "hi", text = "help",
            isAlert = true, isPriority = true
        )
        var deliveries = 0
        nodeB.handleIncomingPacket(sos) { deliveries++ }
        nodeB.handleIncomingPacket(sos) { deliveries++ } // replay of same SOS

        // Same sender+id replay: the second is rejected at the destination,
        // so it is delivered (and ACKed) exactly once — no double-emergency.
        assertEquals(1, deliveries)
        assertTrue(transport.sentPackets.isNotEmpty())
        assertEquals(PacketType.ACK, transport.sentPackets[0].type)
    }

    /** Emergency with no transport / no connected peer is queued, NOT silently lost. */
    @Test
    fun testEmergencyWithoutPeerIsQueued() {
        val transport = MockTransport().apply { connectionState = ConnectionState.DISCONNECTED }
        val node = MeshRoutingManager(myNodeId = "NODE_A", transportLayer = transport)

        val sos = TextPacket(
            messageId = "sos_offline", senderId = "NODE_A", recipientId = "*",
            type = PacketType.EMERGENCY, language = "hi", text = "help",
            isAlert = true, isPriority = true
        )
        node.sendReliablePacket(sos) { _ -> }

        // Stored in the outbox for later (store-and-forward), not dropped.
        assertEquals(1, node.getOutboxSize())
    }

    /** Relay path for an emergency that is NOT addressed to the relay. */
    @Test
    fun testEmergencyRelayedThroughHop() {
        val transport = MockTransport()
        val relay = MeshRoutingManager(myNodeId = "NODE_R", transportLayer = transport)

        val sos = TextPacket(
            messageId = "sos_relay", senderId = "NODE_A", recipientId = "NODE_C",
            type = PacketType.EMERGENCY, language = "hi", text = "forward me",
            isAlert = true, isPriority = true,
            hopCount = 0, maxHops = 5
        )
        var local = false
        relay.handleIncomingPacket(sos) { local = true }

        assertFalse("Relay must NOT consume an emergency addressed to C", local)
        assertTrue(transport.sentPackets.isNotEmpty())
        // A forwarded emergency KEEPS its EMERGENCY type (priority semantics must
        // survive relay), while its hopCount increments.
        assertEquals(PacketType.EMERGENCY, transport.sentPackets[0].type)
        assertEquals(1, transport.sentPackets[0].hopCount)
        assertEquals("NODE_C", transport.sentPackets[0].recipientId)
    }
}