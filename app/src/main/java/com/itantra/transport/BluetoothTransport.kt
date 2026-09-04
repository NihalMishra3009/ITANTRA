package com.itantra.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.itantra.protocol.BinaryPacketCodec
import com.itantra.protocol.TextPacket
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * RFCOMM Bluetooth Classic Transport supporting multiple simultaneous peer
 * connections. A relay node can maintain connections to A and B at the same time.
 *
 * The server socket stays open after each accept to allow additional peers.
 * Each peer connection is tracked independently.
 */
class BluetoothTransport(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
) : TransportLayer {

    companion object {
        private const val TAG = "BluetoothTransport"
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        private const val SERVICE_NAME = "iTantra_RFCOMM"
    }

    override val transportType: TransportType = TransportType.BLUETOOTH
    override var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private var serverSocket: BluetoothServerSocket? = null

    /** Per-peer connections: address -> PeerConnection */
    private val peerConnections = ConcurrentHashMap<String, ActivePeer>()

    private var onPacketCallback: ((TextPacket) -> Unit)? = null
    private var onStateCallback: ((ConnectionState) -> Unit)? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerJob: Job? = null

    private val codec = BinaryPacketCodec()

    private data class ActivePeer(
        val address: String,
        var socket: BluetoothSocket,
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

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter not enabled or not present")
            updateState(ConnectionState.ERROR)
            return
        }

        listenerJob?.cancel()
        listenerJob = coroutineScope.launch {
            try {
                serverSocket?.close()
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                Log.i(TAG, "RFCOMM server listening for incoming connections...")

                while (isActive) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        val addr = socket.remoteDevice?.address ?: "unknown"
                        Log.i(TAG, "Incoming Bluetooth connection from: ${socket.remoteDevice?.name} ($addr)")
                        handleNewPeer(socket, addr)
                        // DO NOT close serverSocket — keep accepting more peers
                    } catch (e: IOException) {
                        if (!isActive) break
                        Log.w(TAG, "Server socket accept interrupted", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Bluetooth listener", e)
                updateState(ConnectionState.ERROR)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onDevicesFound(emptyList())
            return
        }

        val found = linkedMapOf<String, DeviceInfo>()

        // Include paired/bonded devices — these are most likely iTantra peers
        bluetoothAdapter.bondedDevices?.forEach { dev ->
            found[dev.address] = DeviceInfo(
                id = dev.address,
                name = dev.name ?: "Unknown Device",
                address = dev.address,
                transportType = TransportType.BLUETOOTH
            )
        }

        try { bluetoothAdapter.cancelDiscovery() } catch (_: Exception) {}

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        // Fetch UUIDs to check for iTantra service
                        val uuids = device.uuids
                        val isITantra = uuids?.any { it.uuid == APP_UUID } == true
                        if (isITantra || device.bondState == BluetoothDevice.BOND_BONDED) {
                            found[device.address] = DeviceInfo(
                                id = device.address,
                                name = device.name ?: "Unknown Device",
                                address = device.address,
                                transportType = TransportType.BLUETOOTH
                            )
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))

        val discoveryOk = bluetoothAdapter.startDiscovery()
        Log.i(TAG, "Bluetooth discovery started: $discoveryOk (filtering for iTantra UUID)")

        coroutineScope.launch {
            delay(8000)
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            try { bluetoothAdapter.cancelDiscovery() } catch (_: Exception) {}
            onDevicesFound(found.values.toList())
            Log.i(TAG, "Bluetooth discovery found ${found.size} device(s)")
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onResult(false)
            return
        }

        updateState(ConnectionState.CONNECTING)
        coroutineScope.launch {
            try {
                bluetoothAdapter.cancelDiscovery()
                val btDevice: BluetoothDevice = bluetoothAdapter.getRemoteDevice(device.address)

                if (btDevice.bondState != BluetoothDevice.BOND_BONDED) {
                    if (!bond(btDevice)) {
                        Log.e(TAG, "Pairing with ${device.name} failed")
                        updateState(ConnectionState.ERROR)
                        withContext(Dispatchers.Main) { onResult(false) }
                        return@launch
                    }
                }

                val socket = btDevice.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()

                Log.i(TAG, "Connected to ${device.name} (${device.address})")
                handleNewPeer(socket, device.address)
                updateState(ConnectionState.CONNECTED)
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ${device.address}", e)
                updateState(ConnectionState.ERROR)
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun bond(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val d: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null && d.address == device.address) {
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        if (state == BluetoothDevice.BOND_BONDED) bonded.complete(true)
                        else if (state == BluetoothDevice.BOND_NONE) bonded.complete(false)
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        val ok = device.createBond()
        val result = withTimeoutOrNull(15000) { bonded.await() } ?: false
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        ok && result
    }

    private fun handleNewPeer(socket: BluetoothSocket, address: String) {
        val peer = ActivePeer(
            address = address,
            socket = socket,
            dataIn = DataInputStream(socket.inputStream),
            dataOut = DataOutputStream(socket.outputStream)
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
                        } else {
                            Log.w(TAG, "Bluetooth peer $address: codec.decode returned null (${buffer.size}B) — " +
                                    "HMAC/format rejected")
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Bluetooth peer $address connection lost", e)
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
        if (peerConnections.isEmpty()) {
            Log.w(TAG, "Cannot send packet: no connected peers")
            return false
        }
        var anySent = false
        for ((_, peer) in peerConnections) {
            if (peer.socket.isConnected) {
                try {
                    val dos = peer.dataOut
            val bytes = codec.encode(packet, skipAuth = packet.type == com.itantra.protocol.PacketType.SESSION_START)
                    dos.writeInt(bytes.size)
                    dos.write(bytes)
                    dos.flush()
                    anySent = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send to peer ${peer.address}", e)
                }
            }
        }
        return anySent
    }

    /**
     * Send to a specific peer by node ID. Resolves the peer's BT address
     * from the packet's sender ID mapping.
     */
    override fun sendToPeer(nodeId: String, packet: TextPacket): Boolean {
        val peer = peerConnections.values.find { it.nodeId == nodeId }
        if (peer == null || !peer.socket.isConnected) {
            Log.w(TAG, "Cannot send to BT peer $nodeId: not connected")
            return false
        }
        return try {
            val dos = peer.dataOut
            val bytes = codec.encode(packet, skipAuth = packet.type == com.itantra.protocol.PacketType.SESSION_START)
            dos.writeInt(bytes.size)
            dos.write(bytes)
            dos.flush()
            Log.d(TAG, "Sent to BT peer $nodeId (${bytes.size}B)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to BT peer $nodeId", e)
            peerConnections.remove(peer.address)
            false
        }
    }

    @Synchronized
    override fun disconnect() {
        listenerJob?.cancel()
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
