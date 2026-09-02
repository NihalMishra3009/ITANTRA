package com.itantra.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
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

        val devices = mutableListOf<DeviceInfo>()
        val paired = bluetoothAdapter.bondedDevices
        paired?.forEach { dev ->
            devices.add(
                DeviceInfo(
                    id = dev.address,
                    name = dev.name ?: "Unknown Device",
                    address = dev.address,
                    transportType = TransportType.BLUETOOTH
                )
            )
        }
        onDevicesFound(devices)
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
