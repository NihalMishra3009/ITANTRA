package com.itantra.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.itantra.protocol.BinaryPacketCodec
import com.itantra.protocol.TextPacket
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct (P2P) Transport supporting multiple simultaneous TCP peer
 * connections. A relay node can maintain connections to multiple peers.
 *
 * The server socket stays open to accept additional incoming connections.
 * Each peer is tracked independently.
 */
class WifiDirectTransport(
    private val context: Context
) : TransportLayer {

    companion object {
        private const val TAG = "WifiDirectTransport"
        private const val SERVER_PORT = 8888
    }

    override val transportType: TransportType = TransportType.WIFI_DIRECT
    override var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private val wifiP2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

    private var serverSocket: ServerSocket? = null

    private val peerConnections = ConcurrentHashMap<String, ActivePeer>()

    private var onPacketCallback: ((TextPacket) -> Unit)? = null
    private var onStateCallback: ((ConnectionState) -> Unit)? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private val codec = BinaryPacketCodec()

    private data class ActivePeer(
        val address: String,
        var socket: Socket,
        var dataIn: DataInputStream,
        var dataOut: DataOutputStream,
        var readerJob: Job? = null,
        var nodeId: String? = null
    )

    @SuppressLint("MissingPermission")
    override fun startListening(
        onPacketReceived: (TextPacket) -> Unit,
        onStateChanged: (ConnectionState) -> Unit
    ) {
        this.onPacketCallback = onPacketReceived
        this.onStateCallback = onStateChanged

        serverJob?.cancel()
        serverJob = coroutineScope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(SERVER_PORT)
                Log.i(TAG, "Wi-Fi Direct TCP Server listening on port $SERVER_PORT")

                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    val addr = socket.inetAddress?.hostAddress ?: "unknown"
                    Log.i(TAG, "Incoming TCP connection from $addr")
                    handleNewPeer(socket, addr)
                    // DO NOT close serverSocket — keep accepting more peers
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Error in Wi-Fi Direct server socket", e)
                    updateState(ConnectionState.ERROR)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {
        val manager = wifiP2pManager
        val ch = channel
        if (manager == null || ch == null) {
            onDevicesFound(emptyList())
            return
        }

        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct peer discovery started")
                manager.requestPeers(ch) { peers ->
                    val list = peers.deviceList.map { dev ->
                        DeviceInfo(
                            id = dev.deviceAddress,
                            name = dev.deviceName ?: "Wi-Fi Direct Peer",
                            address = dev.deviceAddress,
                            transportType = TransportType.WIFI_DIRECT
                        )
                    }
                    onDevicesFound(list)
                }
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct discovery failed with code $reason")
                onDevicesFound(emptyList())
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {
        val manager = wifiP2pManager
        val ch = channel
        if (manager == null || ch == null) {
            onResult(false)
            return
        }

        updateState(ConnectionState.CONNECTING)

        val config = WifiP2pConfig().apply {
            deviceAddress = device.address
        }

        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connecting to Wi-Fi Direct peer ${device.address}...")
                coroutineScope.launch {
                    delay(2500)
                    try {
                        val ip = resolveGroupOwnerIp(manager, ch)
                        if (ip == null) {
                            Log.e(TAG, "Could not resolve group owner IP")
                            updateState(ConnectionState.ERROR)
                            withContext(Dispatchers.Main) { onResult(false) }
                            return@launch
                        }
                        val socket = Socket()
                        socket.connect(InetSocketAddress(ip, SERVER_PORT), 8000)
                        handleNewPeer(socket, device.address)
                        updateState(ConnectionState.CONNECTED)
                        withContext(Dispatchers.Main) { onResult(true) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed TCP handshake", e)
                        updateState(ConnectionState.ERROR)
                        withContext(Dispatchers.Main) { onResult(false) }
                    }
                }
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Wi-Fi Direct connect failed: $reason")
                updateState(ConnectionState.ERROR)
                onResult(false)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private suspend fun resolveGroupOwnerIp(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): String? = withContext(Dispatchers.Main) {
        var result: String? = null
        try {
            manager.requestConnectionInfo(ch) { info ->
                val goAddr = info.groupOwnerAddress?.hostAddress
                if (goAddr != null && info.groupFormed && goAddr != "0.0.0.0") {
                    result = goAddr
                }
            }
            delay(800)
        } catch (e: Exception) {
            Log.w(TAG, "requestConnectionInfo failed", e)
        }
        result
    }

    private fun handleNewPeer(socket: Socket, address: String) {
        val peer = ActivePeer(
            address = address,
            socket = socket,
            dataIn = DataInputStream(socket.getInputStream()),
            dataOut = DataOutputStream(socket.getOutputStream())
        )
        peerConnections[address] = peer
        updateState(ConnectionState.CONNECTED)

        peer.readerJob = coroutineScope.launch {
            val dis = peer.dataIn
            while (isActive && socket.isConnected) {
                try {
                    val length = dis.readInt()
                    if (length in 1..1000000) {
                        val buffer = ByteArray(length)
                        dis.readFully(buffer)
                        val packet = codec.decode(buffer)
                        if (packet != null) {
                            peer.nodeId = packet.senderId
                            withContext(Dispatchers.Main) {
                                onPacketCallback?.invoke(packet)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "WiFi peer $address connection lost", e)
                        peerConnections.remove(address)
                        updateState(
                            if (peerConnections.isEmpty()) ConnectionState.DISCONNECTED
                            else ConnectionState.CONNECTED
                        )
                    }
                    break
                }
            }
        }
    }

    @Synchronized
    override fun sendPacket(packet: TextPacket): Boolean {
        if (peerConnections.isEmpty()) return false
        var anySent = false
        for ((_, peer) in peerConnections) {
            if (peer.socket.isConnected) {
                try {
                    val dos = peer.dataOut
                    val bytes = codec.encode(packet)
                    dos.writeInt(bytes.size)
                    dos.write(bytes)
                    dos.flush()
                    anySent = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send to WiFi peer ${peer.address}", e)
                }
            }
        }
        return anySent
    }

    override fun sendToPeer(nodeId: String, packet: TextPacket): Boolean {
        val peer = peerConnections.values.find { it.nodeId == nodeId }
        if (peer == null || !peer.socket.isConnected) {
            Log.w(TAG, "Cannot send to WiFi peer $nodeId: not connected")
            return false
        }
        return try {
            val dos = peer.dataOut
            val bytes = codec.encode(packet)
            dos.writeInt(bytes.size)
            dos.write(bytes)
            dos.flush()
            Log.d(TAG, "Sent to WiFi peer $nodeId (${bytes.size}B)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to WiFi peer $nodeId", e)
            peerConnections.remove(peer.address)
            false
        }
    }

    @Synchronized
    override fun disconnect() {
        serverJob?.cancel()
        for ((addr, peer) in peerConnections) {
            peer.readerJob?.cancel()
            try {
                peer.dataIn.close()
                peer.dataOut.close()
                peer.socket.close()
            } catch (_: Exception) {}
        }
        peerConnections.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        updateState(ConnectionState.DISCONNECTED)
    }

    override fun isConnected(): Boolean = peerConnections.values.any { it.socket.isConnected }

    private fun updateState(state: ConnectionState) {
        connectionState = state
        coroutineScope.launch(Dispatchers.Main) {
            onStateCallback?.invoke(state)
        }
    }
}
