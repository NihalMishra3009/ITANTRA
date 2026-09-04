package com.itantra.transport

import com.itantra.protocol.TextPacket

/**
 * Composite transport that wraps multiple underlying [TransportLayer]s
 * (e.g. Bluetooth + Wi-Fi Direct). For a relay node that has peers on
 * different transports, this routes [sendToPeer] to the correct transport.
 *
 * [sendPacket] broadcasts on ALL transports (legacy behavior).
 * [isConnected] returns true if ANY transport is connected.
 */
class CompositeTransport(private val transports: List<TransportLayer>) : TransportLayer {

    override val transportType: TransportType = TransportType.BLUETOOTH
    override var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private var onPacketCallback: ((TextPacket) -> Unit)? = null
    private var onStateCallback: ((ConnectionState) -> Unit)? = null

    override fun startListening(
        onPacketReceived: (TextPacket) -> Unit,
        onStateChanged: (ConnectionState) -> Unit
    ) {
        this.onPacketCallback = onPacketReceived
        this.onStateCallback = onStateChanged
        for (t in transports) {
            t.startListening(onPacketReceived, onStateChanged)
        }
    }

    override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {
        // Delegate to the first transport that supports discovery
        for (t in transports) {
            if (t !is CompositeTransport) {
                t.discoverDevices(onDevicesFound)
                return
            }
        }
        onDevicesFound(emptyList())
    }

    override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {
        for (t in transports) {
            if (t !is CompositeTransport) {
                t.connect(device, onResult)
                return
            }
        }
        onResult(false)
    }

    override fun sendPacket(packet: TextPacket): Boolean {
        var anySent = false
        for (t in transports) {
            if (t.isConnected()) {
                if (t.sendPacket(packet)) anySent = true
            }
        }
        return anySent
    }

    override fun disconnect() {
        for (t in transports) t.disconnect()
    }

    override fun isConnected(): Boolean = transports.any { it.isConnected() }

    /**
     * Route to a specific peer by trying each transport. The first transport
     * that has this peer connected handles the send.
     */
    override fun sendToPeer(nodeId: String, packet: TextPacket): Boolean {
        for (t in transports) {
            if (t.isConnected() && t.sendToPeer(nodeId, packet)) {
                return true
            }
        }
        return false
    }

    fun addTransport(transport: TransportLayer) {
        (transports as? MutableList<TransportLayer>)?.add(transport)
    }
}
