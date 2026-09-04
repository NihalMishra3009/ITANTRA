package com.itantra.transport

import com.itantra.protocol.TextPacket

data class DeviceInfo(
    val id: String,
    val name: String,
    val address: String,
    val transportType: TransportType
)

enum class TransportType {
    BLUETOOTH,
    WIFI_DIRECT
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Unified Transport Layer Interface for offline peer-to-peer transmission.
 *
 * `sendPacket` is the legacy single-socket broadcast path — still works for
 * direct 1-hop links.  `sendToPeer` targets a specific peer by node ID — the
 * correct path for routed relay traffic.
 */
interface TransportLayer {
    val transportType: TransportType
    val connectionState: ConnectionState

    fun startListening(onPacketReceived: (TextPacket) -> Unit, onStateChanged: (ConnectionState) -> Unit)
    fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit)
    fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit)
    fun sendPacket(packet: TextPacket): Boolean
    fun disconnect()
    fun isConnected(): Boolean

    /** Send to a specific peer by node ID. Falls back to sendPacket if unknown. */
    fun sendToPeer(nodeId: String, packet: TextPacket): Boolean = sendPacket(packet)
}
