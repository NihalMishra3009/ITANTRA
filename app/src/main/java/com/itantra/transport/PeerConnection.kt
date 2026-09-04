package com.itantra.transport

import com.itantra.protocol.TextPacket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a single peer-to-peer connection, identified by the remote node's
 * iTantra ID (e.g. ITN-A1B2C3). One peer may be reachable over Bluetooth OR
 * Wi-Fi Direct; the transport type is recorded for diagnostics and routing.
 *
 * Each PeerConnection owns its own input/output streams so multiple peers
 * can be served simultaneously by a relay node.
 */
data class PeerConnection(
    val nodeId: String,
    val transportType: TransportType,
    var connectionState: ConnectionState = ConnectionState.CONNECTED,
    var dataInputStream: DataInputStream? = null,
    var dataOutputStream: DataOutputStream? = null,
    var socket: Any? = null, // BluetoothSocket or java.net.Socket — untyped to avoid cross-deps
    var lastSeenMs: Long = System.currentTimeMillis(),
    var linkQuality: Float = 0.5f
) {
    @Synchronized
    fun sendRaw(data: ByteArray): Boolean {
        val dos = dataOutputStream ?: return false
        return try {
            dos.writeInt(data.size)
            dos.write(data)
            dos.flush()
            lastSeenMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            connectionState = ConnectionState.ERROR
            false
        }
    }

    @Synchronized
    fun close() {
        connectionState = ConnectionState.DISCONNECTED
        try { dataInputStream?.close() } catch (_: Exception) {}
        try { dataOutputStream?.close() } catch (_: Exception) {}
        try {
            when (socket) {
                is android.bluetooth.BluetoothSocket -> (socket as android.bluetooth.BluetoothSocket).close()
                is java.net.Socket -> (socket as java.net.Socket).close()
            }
        } catch (_: Exception) {}
        dataInputStream = null
        dataOutputStream = null
        socket = null
    }

    fun isConnected(): Boolean =
        connectionState == ConnectionState.CONNECTED && dataOutputStream != null
}
