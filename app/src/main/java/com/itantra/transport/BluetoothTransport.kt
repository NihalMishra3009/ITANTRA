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
 * RFCOMM Bluetooth Classic Transport for offline phone-to-phone communication.
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
    private var activeSocket: BluetoothSocket? = null
    private var dataInputStream: DataInputStream? = null
    private var dataOutputStream: DataOutputStream? = null

    private var onPacketCallback: ((TextPacket) -> Unit)? = null
    private var onStateCallback: ((ConnectionState) -> Unit)? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerJob: Job? = null
    private var receiverJob: Job? = null

    private val codec = BinaryPacketCodec()

    // Deduplication cache for received message IDs
    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()

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
                Log.i(TAG, "RFCOMM server listening for incoming connection...")

                while (isActive) {
                    try {
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            Log.i(TAG, "Incoming Bluetooth connection accepted from: ${socket.remoteDevice?.name}")
                            serverSocket?.close()
                            serverSocket = null
                            handleConnectedSocket(socket)
                            break
                        }
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

        val found = linkedMapOf<String, DeviceInfo>() // dedupe by address, keep order

        // 1. Always include paired/bonded devices.
        bluetoothAdapter.bondedDevices?.forEach { dev ->
            found[dev.address] = DeviceInfo(
                id = dev.address,
                name = dev.name ?: "Unknown Device",
                address = dev.address,
                transportType = TransportType.BLUETOOTH
            )
        }

        // If already discovering, cancel to restart cleanly.
        try { bluetoothAdapter.cancelDiscovery() } catch (_: Exception) {}

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
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
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))

        val discoveryOk = bluetoothAdapter.startDiscovery()
        Log.i(TAG, "Bluetooth discovery started: $discoveryOk")

        // Collect for a limited window, then report and stop discovery.
        coroutineScope.launch {
            delay(8000)
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            try { bluetoothAdapter.cancelDiscovery() } catch (_: Exception) {}
            onDevicesFound(found.values.toList())
            Log.i(TAG, "Bluetooth discovery found ${found.size} in-range device(s)")
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

                // If not yet paired, initiate bonding and wait for it to complete.
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

                Log.i(TAG, "Successfully connected to ${device.name} (${device.address})")
                handleConnectedSocket(socket)
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Bluetooth device ${device.address}", e)
                updateState(ConnectionState.ERROR)
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    /** Initiate pairing and wait (up to 15s) for BOND_BONDED. */
    @SuppressLint("MissingPermission")
    private suspend fun bond(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        val bonded = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val d: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null && d.address == device.address) {
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        if (state == BluetoothDevice.BOND_BONDED) {
                            bonded.complete(true)
                        } else if (state == BluetoothDevice.BOND_NONE) {
                            bonded.complete(false)
                        }
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

    private fun handleConnectedSocket(socket: BluetoothSocket) {
        activeSocket = socket
        dataInputStream = DataInputStream(socket.inputStream)
        dataOutputStream = DataOutputStream(socket.outputStream)
        updateState(ConnectionState.CONNECTED)

        receiverJob?.cancel()
        receiverJob = coroutineScope.launch {
            val dis = dataInputStream ?: return@launch
            while (isActive && isConnected()) {
                try {
                    val length = dis.readInt()
                    if (length in 1..1000000) {
                        val buffer = ByteArray(length)
                        dis.readFully(buffer)
                        val packet = codec.decode(buffer)

                        if (packet != null && seenMessageIds.add(packet.messageId)) {
                            Log.i(TAG, "Received valid packet: id=${packet.messageId} lang=${packet.language}")
                            withContext(Dispatchers.Main) {
                                onPacketCallback?.invoke(packet)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Bluetooth connection lost", e)
                        disconnect()
                    }
                    break
                }
            }
        }
    }

    @Synchronized
    override fun sendPacket(packet: TextPacket): Boolean {
        if (!isConnected()) {
            Log.w(TAG, "Cannot send packet: Not connected")
            return false
        }

        return try {
            val dos = dataOutputStream ?: return false
            val bytes = codec.encode(packet)
            dos.writeInt(bytes.size)
            dos.write(bytes)
            dos.flush()
            Log.i(TAG, "Sent packet: id=${packet.messageId} size=${bytes.size}B text=\"${packet.text}\"")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet over Bluetooth", e)
            disconnect()
            false
        }
    }

    @Synchronized
    override fun disconnect() {
        receiverJob?.cancel()
        listenerJob?.cancel()
        try {
            dataInputStream?.close()
            dataOutputStream?.close()
            activeSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            activeSocket = null
            serverSocket = null
            dataInputStream = null
            dataOutputStream = null
            updateState(ConnectionState.DISCONNECTED)
        }
    }

    override fun isConnected(): Boolean {
        return connectionState == ConnectionState.CONNECTED && activeSocket?.isConnected == true
    }

    private fun updateState(state: ConnectionState) {
        connectionState = state
        coroutineScope.launch(Dispatchers.Main) {
            onStateCallback?.invoke(state)
        }
    }
}
