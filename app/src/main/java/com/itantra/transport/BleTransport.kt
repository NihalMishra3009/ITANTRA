package com.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.itantra.identity.NodeIdentity
import com.itantra.protocol.BinaryPacketCodec
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import com.itantra.security.PeerSessionManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Low Energy transport that connects to nearby iTantra phones WITHOUT pairing,
 * discoverable mode or any tap.
 *
 *  - Every phone always ADVERTISES the iTantra service and runs a GATT server.
 *  - Every phone always SCANS for that service and connects to phones it finds.
 *  - While the user is recording, [prepareForSend] boosts scanning so neighbours are
 *    connected (and the ECDH handshake done) by the time the recording ends.
 *  - Packets travel over the link in the same length-prefixed frames as the other
 *    transports, so the codec and per-hop encryption are unchanged.
 *
 * Why not Bluetooth Classic: it needs a pairing dialog, and an unpaired phone is only visible
 * while the OTHER phone is set discoverable by hand. BLE advertising has neither limit.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context) : TransportLayer {

    companion object {
        private const val TAG = "BleTransport"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val MAX_PARALLEL_CONNECTS = 3
        private const val BOOST_SCAN_MS = 25_000L
        private const val MIN_SCAN_RESTART_MS = 6_000L
        private const val STALE_SEEN_MS = 30_000L

        /** A peer must have advertised this recently to be worth connecting to (addresses rotate). */
        private const val FRESH_SEEN_MS = 6_000L
        private const val IO_TIMEOUT_S = 3L
    }

    override val transportType: TransportType = TransportType.BLUETOOTH
    override var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val codec = BinaryPacketCodec()

    private var onPacket: ((TextPacket) -> Unit)? = null
    private var onState: ((ConnectionState) -> Unit)? = null
    private var onLinked: ((Boolean) -> Unit)? = null

    private val links = ConcurrentHashMap<String, Link>()
    private val connecting = ConcurrentHashMap<String, Long>()
    private val seen = ConcurrentHashMap<String, Seen>()

    private data class Seen(val hint: ByteArray?, val firstSeenMs: Long, var lastSeenMs: Long, var name: String)

    @Volatile private var running = false
    @Volatile private var scanBoosted = false
    private var lastScanStartMs = 0L
    private var boostEndRunnable: Runnable? = null

    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var stateReceiverRegistered = false

    // ------------------------------------------------------------------ lifecycle

    override fun startListening(
        onPacketReceived: (TextPacket) -> Unit,
        onStateChanged: (ConnectionState) -> Unit
    ) {
        onPacket = onPacketReceived
        onState = onStateChanged
        registerStateReceiver()
        ensureRunning()
    }

    override fun setOnPeerLinked(listener: ((Boolean) -> Unit)?) { onLinked = listener }

    private fun hasPermissions(): Boolean {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_ADVERTISE) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** Idempotent: starts advertising, the GATT server and scanning once radio + permissions allow. */
    @Synchronized
    override fun ensureRunning() {
        val a = adapter
        if (a == null || !a.isEnabled) { setState(ConnectionState.DISCONNECTED); return }
        if (!hasPermissions()) { Log.w(TAG, "BLE permissions not granted yet"); return }
        if (running) { if (!scanning) startScan(); return }
        try {
            openGattServer()
            startAdvertising()
            startScan()
            running = true
            Log.i(TAG, "BLE transport running (advertising + GATT server + scanning)")
        } catch (e: Exception) {
            Log.e(TAG, "BLE start failed", e)
            stopAll()
        }
    }

    @Synchronized
    private fun stopAll() {
        running = false
        try { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        advertiser = null
        links.values.toList().forEach { it.close() }
        links.clear()
        connecting.clear()
        // Forget who was seen: a stale first-seen time would make the phone that should wait
        // connect immediately after a Bluetooth toggle, creating a duplicate link.
        seen.clear()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        setState(ConnectionState.DISCONNECTED)
    }

    private fun registerStateReceiver() {
        if (stateReceiverRegistered) return
        stateReceiverRegistered = true
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> ensureRunning()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stopAll()
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    // ------------------------------------------------------------------ advertising

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { Log.i(TAG, "Advertising started") }
        override fun onStartFailure(errorCode: Int) { Log.e(TAG, "Advertising failed: $errorCode") }
    }

    private fun selfHint(): ByteArray = BleProtocol.nodeHint(NodeIdentity.current()?.nodeId ?: "ITN-000000")

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: run { Log.w(TAG, "BLE advertising unsupported"); return }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // The node hint rides in the PRIMARY advertisement (21 bytes of UUID/flags + 7 of hint =
        // 28 of 31), not the scan response: a scanner often sees the first packet before any scan
        // response, and without the hint both phones connect to each other at once.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .addManufacturerData(BleProtocol.COMPANY_ID, selfHint())
            .build()
        adv.startAdvertising(settings, data, advCallback)
    }

    // ------------------------------------------------------------------ scanning + auto-connect

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handleScanResult(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handleScanResult(it) } }
        override fun onScanFailed(errorCode: Int) {
            // 1 = SCAN_FAILED_ALREADY_STARTED: a scan IS running (a second start raced the first).
            // Treating that as "not scanning" made every later call retry and log an error.
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) { scanning = true; return }
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
        }
    }

    @Synchronized
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val now = System.currentTimeMillis()
        if (scanning) {
            // Android throttles scan starts; only restart to change mode, and not too often.
            if (now - lastScanStartMs < MIN_SCAN_RESTART_MS) return
            try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (scanBoosted) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            lastScanStartMs = now
            Log.i(TAG, "Scanning (${if (scanBoosted) "low-latency" else "balanced"})")
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            scanning = false
        }
    }

    /**
     * Called when the user starts recording: scan hard for a while so nearby phones are
     * connected and secured before the recording ends.
     */
    override fun prepareForSend() {
        ensureRunning()
        if (!running) return
        if (!scanBoosted) {
            scanBoosted = true
            startScan()
        }
        boostEndRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { scanBoosted = false; if (running) startScan() }
        boostEndRunnable = r
        main.postDelayed(r, BOOST_SCAN_MS)
        // Give already-seen peers that were waiting their turn an immediate chance.
        seen.forEach { (addr, s) -> considerConnecting(addr, s, null) }
    }

    private fun handleScanResult(result: ScanResult) {
        val addr = result.device.address ?: return
        val hint = result.scanRecord?.getManufacturerSpecificData(BleProtocol.COMPANY_ID)
        val now = System.currentTimeMillis()
        val entry = seen.compute(addr) { _, old ->
            if (old == null) Seen(hint, now, now, result.device.name ?: "iTantra peer")
            else { old.lastSeenMs = now; old }
        }!!
        seen.entries.removeIf { now - it.value.lastSeenMs > STALE_SEEN_MS && !links.containsKey(it.key) }
        considerConnecting(addr, entry, result.device)
    }

    private fun hasLinkToHint(hint: ByteArray?): Boolean {
        if (hint == null) return false
        return links.values.any { l -> l.nodeId?.let { BleProtocol.nodeHint(it).contentEquals(hint) } == true }
    }

    private fun considerConnecting(addr: String, s: Seen, device: BluetoothDevice?) {
        if (links.containsKey(addr) || connecting.containsKey(addr)) return
        if (System.currentTimeMillis() - s.lastSeenMs > FRESH_SEEN_MS) return
        if (connecting.size >= MAX_PARALLEL_CONNECTS) return
        if (hasLinkToHint(s.hint)) return // already linked to this phone (it connected to us)
        val age = System.currentTimeMillis() - s.firstSeenMs
        if (!BleProtocol.shouldInitiate(selfHint(), s.hint, age)) return
        val dev = device ?: adapter?.getRemoteDevice(addr) ?: return
        connectTo(dev)
    }

    private fun connectTo(device: BluetoothDevice) {
        val addr = device.address
        if (connecting.putIfAbsent(addr, System.currentTimeMillis()) != null) return
        Log.i(TAG, "Connecting to iTantra peer $addr")
        setState(ConnectionState.CONNECTING)
        val cb = ClientCallback(addr)
        val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) { connecting.remove(addr); return }
        cb.gatt = gatt
        main.postDelayed({
            if (connecting.containsKey(addr) && !links.containsKey(addr)) {
                Log.w(TAG, "Connect to $addr timed out")
                connecting.remove(addr)
                try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
                recomputeState()
            }
        }, CONNECT_TIMEOUT_MS)
    }

    // ------------------------------------------------------------------ links

    private abstract inner class Link(val address: String, val initiator: Boolean) {
        val assembler = FrameAssembler()
        @Volatile var mtu = BleProtocol.DEFAULT_MTU
        @Volatile var nodeId: String? = null
        @Volatile var ready = false
        private val sendLock = Any()

        abstract fun writeChunk(chunk: ByteArray): Boolean
        abstract fun close()

        fun sendFrame(payload: ByteArray): Boolean = synchronized(sendLock) {
            val size = BleProtocol.payloadSize(mtu)
            BleProtocol.chunk(BleProtocol.frame(payload), size).all { writeChunk(it) }
        }

        fun onBytes(bytes: ByteArray) {
            Log.d(TAG, "rx ${bytes.size}B on ${if (initiator) "client" else "server"} link $address")
            val frames = assembler.feed(bytes)
            if (assembler.lastFeedCorrupt) Log.w(TAG, "Corrupt BLE frame from $address — buffer reset")
            frames.forEach { handleFrame(this, it) }
        }
    }

    // --- central side (we connected to a peer's GATT server) ---

    private inner class ClientLink(address: String, val gatt: BluetoothGatt, val rx: BluetoothGattCharacteristic) :
        Link(address, true) {
        private val writeDone = Semaphore(0)
        @Volatile var writeOk = false

        fun writeCompleted(ok: Boolean) { writeOk = ok; writeDone.release() }

        override fun writeChunk(chunk: ByteArray): Boolean {
            writeDone.drainPermits()
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    rx.value = chunk
                    gatt.writeCharacteristic(rx)
                }
            }
            if (!started) { Log.w(TAG, "write to $address rejected by stack (${chunk.size}B)"); return false }
            val got = writeDone.tryAcquire(IO_TIMEOUT_S, TimeUnit.SECONDS)
            if (!got) { Log.w(TAG, "write to $address: no onCharacteristicWrite within ${IO_TIMEOUT_S}s"); return false }
            if (!writeOk) Log.w(TAG, "write to $address completed with failure status")
            return writeOk
        }

        override fun close() { try { gatt.disconnect(); gatt.close() } catch (_: Exception) {} }
    }

    private inner class ClientCallback(val address: String) : BluetoothGattCallback() {
        var gatt: BluetoothGatt? = null
        private var mtu = BleProtocol.DEFAULT_MTU
        private var rx: BluetoothGattCharacteristic? = null
        private var link: ClientLink? = null

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(BleProtocol.REQUESTED_MTU)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Client link to $address closed (status=$status)")
                link?.let { l -> links.remove(address, l) }
                connecting.remove(address)
                try { g.close() } catch (_: Exception) {}
                recomputeState()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, negotiated: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = negotiated
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(BleProtocol.SERVICE_UUID)
            val rxChar = svc?.getCharacteristic(BleProtocol.RX_UUID)
            val txC = svc?.getCharacteristic(BleProtocol.TX_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rxChar == null || txC == null) {
                Log.w(TAG, "Peer $address lacks the iTantra service (status=$status)")
                connecting.remove(address)
                g.disconnect()
                return
            }
            rx = rxChar
            g.setCharacteristicNotification(txC, true)
            val cccd = txC.getDescriptor(BleProtocol.CCCD_UUID)
            if (cccd == null) { g.disconnect(); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { g.disconnect(); return }
            val l = ClientLink(address, g, rx ?: return).also { it.mtu = mtu; it.ready = true }
            link = l
            links[address] = l
            connecting.remove(address)
            Log.i(TAG, "Client link READY to $address (mtu=$mtu)")
            recomputeState()
            main.post { onLinked?.invoke(true) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            link?.writeCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION") val v = c.value
                if (v == null || link == null) Log.w(TAG, "notification on $address dropped (value=${v?.size}, link=${link != null})")
                else link?.onBytes(v)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            link?.onBytes(value)
        }
    }

    // --- peripheral side (a peer connected to OUR GATT server) ---

    private inner class ServerLink(address: String, val device: BluetoothDevice) : Link(address, false) {
        private val notifyDone = Semaphore(0)
        @Volatile var notifyOk = false
        @Volatile var subscribed = false

        fun notificationSent(ok: Boolean) { notifyOk = ok; notifyDone.release() }

        override fun writeChunk(chunk: ByteArray): Boolean {
            val server = gattServer ?: return false
            val tx = txChar ?: return false
            if (!subscribed) { Log.w(TAG, "notify to $address skipped: peer not subscribed"); return false }
            notifyDone.drainPermits()
            var rc = 0
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                rc = server.notifyCharacteristicChanged(device, tx, false, chunk)
                rc == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    tx.value = chunk
                    server.notifyCharacteristicChanged(device, tx, false)
                }
            }
            if (!started) { Log.w(TAG, "notify to $address rejected by stack (rc=$rc, ${chunk.size}B)"); return false }
            val got = notifyDone.tryAcquire(IO_TIMEOUT_S, TimeUnit.SECONDS)
            if (!got) { Log.w(TAG, "notify to $address: no onNotificationSent within ${IO_TIMEOUT_S}s"); return false }
            if (!notifyOk) Log.w(TAG, "notify to $address completed with failure status")
            return notifyOk
        }

        override fun close() { try { gattServer?.cancelConnection(device) } catch (_: Exception) {} }
    }

    private fun openGattServer() {
        try { gattServer?.close() } catch (_: Exception) {}
        val server = manager?.openGattServer(context, serverCallback) ?: throw IllegalStateException("no GATT server")
        val svc = BluetoothGattService(BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            BleProtocol.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            BleProtocol.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                BleProtocol.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(rx)
        svc.addCharacteristic(tx)
        server.addService(svc)
        gattServer = server
        txChar = tx
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Peer $addr connected to our GATT server")
                links[addr] = ServerLink(addr, device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Server link from $addr closed")
                links.remove(addr)
                recomputeState()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            links[device.address]?.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (c.uuid == BleProtocol.RX_UUID && value != null) links[device.address]?.onBytes(value)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val link = links[device.address] as? ServerLink
            if (d.uuid == BleProtocol.CCCD_UUID && link != null && value != null) {
                val on = value.isNotEmpty() && value[0].toInt() == 1
                link.subscribed = on
                if (on && !link.ready) {
                    link.ready = true
                    Log.i(TAG, "Server link READY from ${device.address} (mtu=${link.mtu})")
                    recomputeState()
                    main.post { onLinked?.invoke(false) }
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            (links[device.address] as? ServerLink)?.notificationSent(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    // ------------------------------------------------------------------ frames in / out

    private fun handleFrame(link: Link, buffer: ByteArray) {
        try {
            // Hop-level security: encrypted + authenticated with THIS peer's session key.
            // Bootstrap (SESSION_START) is skipAuth and carries only an ephemeral public key.
            val peerKey = link.nodeId?.let { PeerSessionManager.getSessionKey(it) }
            val packet = codec.decode(buffer, peerKey)
            if (packet == null) {
                Log.w(TAG, "Peer ${link.address}: frame rejected (${buffer.size}B) — auth/HMAC/format")
                return
            }
            val learned = link.nodeId != packet.senderId
            link.nodeId = packet.senderId
            if (learned) dropDuplicateLinks(link)
            val plain = if (packet.isEncrypted) {
                packet.withDecryption(peerKey ?: return)
            } else packet
            main.post { onPacket?.invoke(plain) }
        } catch (e: Exception) {
            Log.w(TAG, "Bad frame from ${link.address}", e)
        }
    }

    /**
     * Two phones can end up linked twice (one link each way) if they connect at the same moment.
     * Two links mean two competing secure handshakes, which can leave the phones with different
     * keys. Once a link learns who its peer is, keep the canonical link and close the other; both
     * phones evaluate the same rule, so they close the same one.
     */
    private fun dropDuplicateLinks(link: Link) {
        val id = link.nodeId ?: return
        val self = selfHint()
        val peer = BleProtocol.nodeHint(id)
        for (other in links.values.filter { it !== link && it.ready && it.nodeId == id }) {
            val keepNew = BleProtocol.isCanonicalLink(self, peer, link.initiator)
            val loser = if (keepNew) other else link
            Log.i(TAG, "Duplicate link to $id: closing ${if (loser.initiator) "client" else "server"} link ${loser.address}")
            links.remove(loser.address, loser)
            loser.close()
        }
        recomputeState()
    }

    /** Encrypt for this peer's key and write. Returns false when no session key exists yet. */
    private fun writeFrame(link: Link, packet: TextPacket): Boolean {
        return try {
            val bootstrap = packet.type == PacketType.SESSION_START
            val peerKey = link.nodeId?.let { PeerSessionManager.getSessionKey(it) }
            if (!bootstrap && peerKey == null) return false
            val wire = if (bootstrap) packet else packet.withEncryption(peerKey!!)
            val ok = link.sendFrame(codec.encode(wire, sessionKey = peerKey, skipAuth = bootstrap))
            if (!ok) Log.w(TAG, "frame to ${link.nodeId ?: link.address} (${packet.type}) NOT delivered")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send frame to ${link.nodeId ?: link.address}", e)
            false
        }
    }

    private fun readyLinks(): List<Link> = links.values.filter { it.ready }

    /** True only when EVERY ready link took the packet: a link still shaking hands keeps the retry alive. */
    override fun sendPacket(packet: TextPacket): Boolean {
        val ready = readyLinks()
        if (ready.isEmpty()) return false
        var anySent = false
        var allSent = true
        for (l in ready) {
            if (writeFrame(l, packet)) anySent = true else allSent = false
        }
        // SESSION_START is best-effort per link; ordinary packets must reach every peer.
        return if (packet.type == PacketType.SESSION_START) anySent else anySent && allSent
    }

    /** Handshake goes only to links whose peer has not been identified yet, i.e. the new one(s). */
    override fun sendHandshake(packet: TextPacket): Boolean {
        val fresh = readyLinks().filter { it.nodeId == null }
        if (fresh.isEmpty()) return false
        var any = false
        for (l in fresh) if (writeFrame(l, packet)) any = true
        return any
    }

    override fun sendToPeer(nodeId: String, packet: TextPacket): Boolean {
        val l = readyLinks().firstOrNull { it.nodeId == nodeId } ?: return false
        return writeFrame(l, packet)
    }

    override fun isConnected(): Boolean = readyLinks().isNotEmpty()

    /** A link exists AND its session key is established, i.e. a packet can be sent right now. */
    override fun isReadyToSend(): Boolean = readyLinks().any { l ->
        l.nodeId?.let { PeerSessionManager.getSessionKey(it) } != null
    }

    // ------------------------------------------------------------------ manual connect (UI)

    override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {
        ensureRunning()
        prepareForSend()
        main.postDelayed({
            val now = System.currentTimeMillis()
            onDevicesFound(
                seen.filter { now - it.value.lastSeenMs < STALE_SEEN_MS }.map { (addr, s) ->
                    DeviceInfo(id = addr, name = s.name, address = addr, transportType = TransportType.BLUETOOTH)
                }
            )
        }, 4_000)
    }

    override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {
        val dev = adapter?.getRemoteDevice(device.address)
        if (dev == null) { onResult(false); return }
        connectTo(dev)
        main.postDelayed({ onResult(links.containsKey(device.address)) }, 6_000)
    }

    override fun disconnect() {
        boostEndRunnable?.let { main.removeCallbacks(it) }
        stopAll()
    }

    // ------------------------------------------------------------------ state

    private fun recomputeState() {
        setState(
            when {
                readyLinks().isNotEmpty() -> ConnectionState.CONNECTED
                connecting.isNotEmpty() -> ConnectionState.CONNECTING
                else -> ConnectionState.DISCONNECTED
            }
        )
    }

    private fun setState(s: ConnectionState) {
        if (connectionState == s) return
        connectionState = s
        main.post { onState?.invoke(s) }
    }
}
