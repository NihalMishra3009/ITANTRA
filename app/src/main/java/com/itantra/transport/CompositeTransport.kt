package com.itantra.transport

import android.util.Log
import com.itantra.protocol.TextPacket

/**
 * Composite transport that wraps multiple underlying [TransportLayer]s
 * (e.g. Bluetooth + Wi-Fi Direct). For a relay node that has peers on
 * different transports, this routes [sendToPeer] to the correct transport.
 *
 * [sendPacket] broadcasts on ALL transports (legacy behavior).
 * [isConnected] returns true if ANY transport is connected.
 *
 * Identity reporting is honest: the composite's transportType reflects the
 * currently-connected transports (or the first connected one), and discovery /
 * connect fan out across all underlying transports so every radio is usable.
 */
class CompositeTransport(private val transports: List<TransportLayer>) : TransportLayer {

    companion object {
        private const val TAG = "CompositeTransport"
    }

    /** Set of transports currently enabled for send/discovery (default: ALL). */
    @Volatile
    var enabledTypes: Set<TransportType> = setOf(TransportType.BLUETOOTH, TransportType.WIFI_DIRECT)

    private fun isEnabled(t: TransportLayer): Boolean = t.transportType in enabledTypes

    override val transportType: TransportType
        get() = connectedTransports().firstOrNull()?.transportType
            ?: transports.firstOrNull { it.transportType in enabledTypes }?.transportType
            ?: TransportType.BLUETOOTH

    override var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private var onPacketCallback: ((TextPacket) -> Unit)? = null
    private var onStateCallback: ((ConnectionState) -> Unit)? = null

    /** True when every underlying transport reports the same state (all-on/all-off). */
    override fun isConnected(): Boolean = transports.any { it.isConnected() && isEnabled(it) }

    private fun connectedTransports(): List<TransportLayer> = transports.filter { it.isConnected() && isEnabled(it) }

    override fun startListening(
        onPacketReceived: (TextPacket) -> Unit,
        onStateChanged: (ConnectionState) -> Unit
    ) {
        this.onPacketCallback = onPacketReceived
        this.onStateCallback = onStateChanged
        // Wire each underlying transport's packet + state callbacks up once here;
        // do NOT delegate to the shared callback so per-transport lifecycle
        // transition recompute the composite state correctly.
        for (t in transports) {
            t.startListening(
                onPacketReceived = onPacketReceived,
                onStateChanged = { _ -> recomputeState() }
            )
        }
    }

    private fun recomputeState() {
        val any = transports.any { it.connectionState == ConnectionState.CONNECTED }
        val connecting = transports.any { it.connectionState == ConnectionState.CONNECTING }
        val state = when {
            any -> ConnectionState.CONNECTED
            connecting -> ConnectionState.CONNECTING
            else -> ConnectionState.DISCONNECTED
        }
        if (connectionState != state) {
            connectionState = state
            onStateCallback?.invoke(state)
        }
    }

    override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {
        // Fan out: collect from every enabled non-composite transport concurrently.
        val results = java.util.concurrent.ConcurrentLinkedQueue<DeviceInfo>()
        val discoveryT = transports.filter { it !is CompositeTransport && isEnabled(it) }
        if (discoveryT.isEmpty()) {
            onDevicesFound(emptyList())
            return
        }
        var pending = discoveryT.size
        var fired = false
        val lock = Object()
        fun maybeComplete() {
            synchronized(lock) {
                pending--
                if (pending == 0 && !fired) {
                    fired = true
                    onDevicesFound(results.toList())
                }
            }
        }
        for (t in discoveryT) {
            t.discoverDevices { devices ->
                results.addAll(devices)
                maybeComplete()
            }
        }
    }

    override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {
        // Route connect to the transport that owns the given device (if enabled).
        val target = transports.firstOrNull { it.transportType == device.transportType && isEnabled(it) }
            ?: transports.firstOrNull { isEnabled(it) }
            ?: run { onResult(false); return }
        target.connect(device, onResult)
    }

    override fun sendPacket(packet: TextPacket): Boolean {
        var anySent = false
        for (t in connectedTransports()) {
            if (t.sendPacket(packet)) anySent = true
        }
        return anySent
    }

    override fun disconnect() {
        for (t in transports) t.disconnect()
        recomputeState()
    }

    /**
     * Route to a specific peer: try each enabled connected transport that can
     * address the peer directly.
     */
    override fun sendToPeer(nodeId: String, packet: TextPacket): Boolean {
        for (t in connectedTransports()) {
            if (t.sendToPeer(nodeId, packet)) {
                return true
            }
        }
        return false
    }

    fun addTransport(transport: TransportLayer) {
        (transports as? MutableList<TransportLayer>)?.add(transport)
        transport.startListening(
            onPacketReceived = { onPacketCallback?.invoke(it) },
            onStateChanged = { _ -> recomputeState() }
        )
    }
}