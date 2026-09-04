package com.itantra.transport

import android.util.Log
import com.itantra.protocol.BinaryPacketCodec
import com.itantra.protocol.TextPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-peer transport manager for iTantra.
 *
 * Wraps Bluetooth and Wi-Fi Direct transports, maintaining a map of
 * active [PeerConnection]s keyed by remote node ID. This allows a relay
 * node to simultaneously serve A<->Relay and Relay<->B connections.
 *
 * Responsibilities:
 *  - Register/unregister peer connections when transports deliver them
 *  - Route packets to a specific peer by node ID ([sendToPeer])
 *  - Broadcast to all connected peers ([sendPacket])
 *  - Maintain the peer connection registry
 *
 * The [TransportLayer] interface is preserved for backward compatibility.
 */
class TransportManager : TransportLayer {

    companion object {
        private const val TAG = "TransportManager"
    }

    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val codec = BinaryPacketCodec()

    private var onPacketCallback: ((TextPacket) -> Unit)? = null
    private var onStateCallback: ((ConnectionState) -> Unit)? = null

    override val transportType: TransportType = TransportType.BLUETOOTH
    override var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    override fun startListening(
        onPacketReceived: (TextPacket) -> Unit,
        onStateChanged: (ConnectionState) -> Unit
    ) {
        this.onPacketCallback = onPacketReceived
        this.onStateCallback = onStateChanged
    }

    override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {
        onDevicesFound(emptyList())
    }

    override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    override fun sendPacket(packet: TextPacket): Boolean {
        if (peers.isEmpty()) return false
        var anySent = false
        for ((_, peer) in peers) {
            if (peer.isConnected()) {
                val bytes = codec.encode(packet)
                if (peer.sendRaw(bytes)) anySent = true
            }
        }
        return anySent
    }

    override fun disconnect() {
        for ((_, peer) in peers) peer.close()
        peers.clear()
        connectionState = ConnectionState.DISCONNECTED
        onStateCallback?.invoke(ConnectionState.DISCONNECTED)
    }

    override fun isConnected(): Boolean = peers.values.any { it.isConnected() }

    override fun sendToPeer(nodeId: String, packet: TextPacket): Boolean {
        val peer = peers[nodeId]
        if (peer == null || !peer.isConnected()) {
            Log.w(TAG, "Cannot send to $nodeId: no active connection")
            return false
        }
        val bytes = codec.encode(packet)
        val sent = peer.sendRaw(bytes)
        if (!sent) {
            Log.w(TAG, "Failed to send to $nodeId, removing dead peer")
            peers.remove(nodeId)
        }
        return sent
    }

    fun registerPeer(peer: PeerConnection) {
        val existing = peers[peer.nodeId]
        if (existing != null && existing !== peer) {
            Log.i(TAG, "Replacing existing connection for ${peer.nodeId}")
            existing.close()
        }
        peers[peer.nodeId] = peer
        connectionState = if (peers.isNotEmpty()) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
        onStateCallback?.invoke(connectionState)
        Log.i(TAG, "Peer registered: ${peer.nodeId} via ${peer.transportType} (total=${peers.size})")
    }

    fun registerPeerFromStreams(
        transportType: TransportType,
        inputStream: java.io.DataInputStream,
        outputStream: java.io.DataOutputStream,
        socket: Any? = null
    ): PeerConnection {
        return PeerConnection(
            nodeId = "PENDING_${System.currentTimeMillis()}",
            transportType = transportType,
            connectionState = ConnectionState.CONNECTED,
            dataInputStream = inputStream,
            dataOutputStream = outputStream,
            socket = socket
        )
    }

    fun onPacketFromPeer(packet: TextPacket, peer: PeerConnection) {
        val senderId = packet.senderId
        if (senderId.isNotBlank() && !senderId.startsWith("PENDING_")) {
            if (peer.nodeId.startsWith("PENDING_")) {
                peers.remove(peer.nodeId)
                val promoted = peer.copy(nodeId = senderId)
                peers[senderId] = promoted
                Log.i(TAG, "Peer identified: $senderId via ${promoted.transportType}")
            } else if (peer.nodeId != senderId) {
                peers.remove(peer.nodeId)
                val updated = peer.copy(nodeId = senderId)
                peers[senderId] = updated
            }
        }
        peer.lastSeenMs = System.currentTimeMillis()
        onPacketCallback?.invoke(packet)
    }

    fun removePeer(nodeId: String) {
        peers.remove(nodeId)?.close()
        connectionState = if (peers.isNotEmpty()) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
        onStateCallback?.invoke(connectionState)
        Log.i(TAG, "Peer removed: $nodeId (remaining=${peers.size})")
    }

    fun getPeer(nodeId: String): PeerConnection? = peers[nodeId]
    fun getAllPeers(): List<PeerConnection> = peers.values.toList()
    fun getConnectedPeerIds(): Set<String> = peers.filter { it.value.isConnected() }.keys.toSet()
    fun peerCount(): Int = peers.size
    fun hasPeer(nodeId: String): Boolean = peers.containsKey(nodeId) && peers[nodeId]?.isConnected() == true

    fun startPeerReader(peer: PeerConnection, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val dis = peer.dataInputStream ?: return@launch
            while (isActive && peer.isConnected()) {
                try {
                    val length = dis.readInt()
                    if (length in 1..1000000) {
                        val buffer = ByteArray(length)
                        dis.readFully(buffer)
                        val packet = codec.decode(buffer)
                        if (packet != null) {
                            peer.lastSeenMs = System.currentTimeMillis()
                            withContext(Dispatchers.Main) {
                                onPacketFromPeer(packet, peer)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (peer.isConnected()) {
                        Log.w(TAG, "Peer ${peer.nodeId} connection lost", e)
                        withContext(Dispatchers.Main) {
                            removePeer(peer.nodeId)
                        }
                    }
                    break
                }
            }
        }
    }
}
