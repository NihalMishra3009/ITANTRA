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
}
