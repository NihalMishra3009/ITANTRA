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

    /**
     * Send a SESSION_START (secure handshake) packet. Defaults to a normal broadcast; the BLE
     * transport narrows it to links that have not identified themselves yet, so opening one new
     * link does not re-key every phone that is already connected.
     */
    fun sendHandshake(packet: TextPacket): Boolean = sendPacket(packet)

    /** Idempotent: start advertising/scanning as soon as radio + permissions allow. */
    fun ensureRunning() {}

    /**
     * The user started recording: find and connect to nearby peers NOW so the link (and its
     * secure handshake) is ready by the time the recording ends.
     */
    fun prepareForSend() {}

    /** True when a packet can be sent right now (a link exists AND it is authenticated). */
    fun isReadyToSend(): Boolean = isConnected()

    /**
     * Invoked (on the main thread) when a peer link comes up. `initiator` is true when THIS
     * phone opened the connection, which is the side that starts the secure handshake.
     */
    fun setOnPeerLinked(listener: ((initiator: Boolean) -> Unit)?) {}
}
