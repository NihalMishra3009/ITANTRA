package com.itantra.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.itantra.protocol.TextPacket
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct (P2P) Transport using local TCP sockets for high-bandwidth offline communication.
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
    private var clientSocket: Socket? = null
    private var dataInputStream: DataInputStream? = null
    private var dataOutputStream: DataOutputStream? = null

    private var onPacketCallback: ((TextPacket) -> Unit)? = null
    private var onStateCallback: ((ConnectionState) -> Unit)? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var receiverJob: Job? = null
    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()

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
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        Log.i(TAG, "Incoming TCP connection accepted from ${socket.inetAddress.hostAddress}")
                        handleConnectedSocket(socket)
                        break
                    }
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
                // Connect TCP client to group owner
                coroutineScope.launch {
                    delay(2000) // Wait for group negotiation
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(device.address, SERVER_PORT), 5000)
                        handleConnectedSocket(socket)
                        withContext(Dispatchers.Main) { onResult(true) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed TCP handshake with peer", e)
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

    private fun handleConnectedSocket(socket: Socket) {
        clientSocket = socket
        dataInputStream = DataInputStream(socket.getInputStream())
        dataOutputStream = DataOutputStream(socket.getOutputStream())
        updateState(ConnectionState.CONNECTED)

        receiverJob?.cancel()
        receiverJob = coroutineScope.launch {
            val dis = dataInputStream ?: return@launch
            while (isActive && isConnected()) {
                try {
                    val length = dis.readInt()
                    if (length in 1..100000) {
                        val buffer = ByteArray(length)
                        dis.readFully(buffer)
                        val json = String(buffer, Charsets.UTF_8)
                        val packet = TextPacket.fromJson(json)

                        if (packet != null && seenMessageIds.add(packet.messageId)) {
                            Log.i(TAG, "Received Wi-Fi Direct packet: ${packet.messageId}")
                            withContext(Dispatchers.Main) {
                                onPacketCallback?.invoke(packet)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Wi-Fi Direct connection closed", e)
                        disconnect()
                    }
                    break
                }
            }
        }
    }

    @Synchronized
    override fun sendPacket(packet: TextPacket): Boolean {
        if (!isConnected()) return false

        return try {
            val dos = dataOutputStream ?: return false
            val json = packet.toJson()
            val bytes = json.toByteArray(Charsets.UTF_8)
            dos.writeInt(bytes.size)
            dos.write(bytes)
            dos.flush()
            Log.i(TAG, "Sent Wi-Fi Direct packet: ${packet.messageId}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet over Wi-Fi Direct", e)
            disconnect()
            false
        }
    }

    @Synchronized
    override fun disconnect() {
        receiverJob?.cancel()
        serverJob?.cancel()
        try {
            dataInputStream?.close()
            dataOutputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            clientSocket = null
            serverSocket = null
            dataInputStream = null
            dataOutputStream = null
            updateState(ConnectionState.DISCONNECTED)
        }
    }

    override fun isConnected(): Boolean {
        return connectionState == ConnectionState.CONNECTED && clientSocket?.isConnected == true
    }

    private fun updateState(state: ConnectionState) {
        connectionState = state
        coroutineScope.launch(Dispatchers.Main) {
            onStateCallback?.invoke(state)
        }
    }
}
