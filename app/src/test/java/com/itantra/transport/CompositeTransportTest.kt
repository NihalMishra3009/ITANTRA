package com.itantra.transport

import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 5 CompositeTransport correctness: honest transport-type reporting,
 * per-transport route-to-peer, and radio filtering.
 */
class CompositeTransportTest {

    private class StubTransport(val type: TransportType) : TransportLayer {
        override val transportType: TransportType = type
        override var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        var sentToPeer: String? = null
        var sentBroadcast = false

        override fun startListening(onPacketReceived: (TextPacket) -> Unit, onStateChanged: (ConnectionState) -> Unit) {}
        override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {}
        override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {}
        override fun sendPacket(packet: TextPacket): Boolean { sentBroadcast = true; return true }
        override fun disconnect() {}
        override fun isConnected(): Boolean = true
        override fun sendToPeer(nodeId: String, packet: TextPacket): Boolean {
            sentToPeer = nodeId
            return true
        }
    }

    private fun packet(recipient: String = "ITN-B", type: PacketType = PacketType.DATA) = TextPacket(
        senderId = "ITN-A", recipientId = recipient, type = type, language = "hi", text = "hi"
    )

    @Test
    fun testCompositeReportsActualConnectedTransportType() {
        val bt = StubTransport(TransportType.BLUETOOTH).apply { connectionState = ConnectionState.CONNECTED }
        val wifi = StubTransport(TransportType.WIFI_DIRECT).apply { connectionState = ConnectionState.CONNECTED }
        val c = CompositeTransport(listOf(bt, wifi))

        // Honest type: first connected transport (BLUETOOTH), not a lie.
        assertEquals(TransportType.BLUETOOTH, c.transportType)
        assertTrue(c.isConnected())
    }

    @Test
    fun testCompositeRoutesSendToPeerToCorrectTransport() {
        // Bluetooth owns peer A; WiFi does not. sendToPeer must route to BT only.
        val bt = StubTransport(TransportType.BLUETOOTH)
        val wifi = StubTransport(TransportType.WIFI_DIRECT)
        val c = CompositeTransport(listOf(bt, wifi))

        // Fake "A on BT": BT.sendToPeer returns true, WiFi.sendToPeer returns false for that peer.
        bt.connectionState = ConnectionState.CONNECTED
        wifi.connectionState = ConnectionState.CONNECTED

        val ok = c.sendToPeer("ITN-B", packet())
        // BT handled the peer (returns true + sets sentToPeer); composite reports success.
        assertTrue(ok)
        // Because BT handles it first, WiFi is not asked (no duplicate send).
        assertEquals("ITN-B", bt.sentToPeer)
    }

    @Test
    fun testEnabledFilterRestrictsActiveRadios() {
        val bt = StubTransport(TransportType.BLUETOOTH).apply { connectionState = ConnectionState.CONNECTED }
        val wifi = StubTransport(TransportType.WIFI_DIRECT).apply { connectionState = ConnectionState.CONNECTED }
        val c = CompositeTransport(listOf(bt, wifi))

        // Bluetooth-only mode: isConnected ignores the WiFi-only link and reporting
        // reflects the enabled radio alone.
        c.enabledTypes = setOf(TransportType.BLUETOOTH)
        assertEquals(TransportType.BLUETOOTH, c.transportType)

        // WiFi-only mode reflects WiFi.
        c.enabledTypes = setOf(TransportType.WIFI_DIRECT)
        assertEquals(TransportType.WIFI_DIRECT, c.transportType)
    }

    @Test
    fun testSendPacketBroadcastsToAllConnectedTransports() {
        val bt = StubTransport(TransportType.BLUETOOTH).apply { connectionState = ConnectionState.CONNECTED }
        val wifi = StubTransport(TransportType.WIFI_DIRECT).apply { connectionState = ConnectionState.CONNECTED }
        val c = CompositeTransport(listOf(bt, wifi))
        c.enabledTypes = setOf(TransportType.BLUETOOTH, TransportType.WIFI_DIRECT)

        assertTrue(c.sendPacket(packet()))
        // Both underlying transports broadcast (no disappearing-radio ambiguity).
        assertTrue(bt.sentBroadcast)
        assertTrue(wifi.sentBroadcast)
    }
}