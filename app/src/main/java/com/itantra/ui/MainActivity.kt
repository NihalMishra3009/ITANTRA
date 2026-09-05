package com.itantra.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.content.Context
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.itantra.R
import com.itantra.audio.AudioFocusManager
import com.itantra.audio.AudioPlayer
import com.itantra.audio.AudioRecorder
import com.itantra.databinding.ActivityMainBinding
import com.itantra.identity.NodeIdentity
import com.itantra.orchestrator.OperatingMode
import com.itantra.orchestrator.PipelineOrchestrator
import com.itantra.orchestrator.TransceiverState
import com.itantra.stt.SttEngine
import com.itantra.stt.SupportedLanguage
import com.itantra.transport.BluetoothTransport
import com.itantra.transport.CompositeTransport
import com.itantra.transport.RouteEntry
import com.itantra.transport.TransportLayer
import com.itantra.transport.WifiDirectTransport
import com.itantra.tts.TtsEngine
import com.itantra.vad.VadEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var vadEngine: VadEngine
    private lateinit var sttEngine: SttEngine
    private lateinit var ttsEngine: TtsEngine

    private var bluetoothTransport: BluetoothTransport? = null
    private var wifiDirectTransport: WifiDirectTransport? = null
    private var currentTransport: TransportLayer? = null

    private lateinit var orchestrator: PipelineOrchestrator

    private var isPulsing = false

    private var lastIncomingMessage: String = ""
    private var lastSttMessage: String = ""

    private val prototypeLanguages = listOf(SupportedLanguage.HINDI, SupportedLanguage.ENGLISH)

    private val requiredPermissions by lazy {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        list.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val recordGranted = perms[Manifest.permission.RECORD_AUDIO] == true
        if (recordGranted) {
            Toast.makeText(this, "Microphone & Local Radio Permissions Granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Record audio permission is required for iTantra", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initEngines()
        setupLanguageDropdown()
        setupTransportDropdown()
        setupPttAndAlertButtons()
        setupDeviceConnection()
        setupNavigation()
        observeOrchestratorState()
        refreshPeerState()

        renderStatus(TransceiverState.IDLE)
        renderLatency(null)

        checkAndRequestPermissions()
    }

    private fun initEngines() {
        audioRecorder = AudioRecorder()
        audioPlayer = AudioPlayer()
        audioFocusManager = AudioFocusManager(this)
        vadEngine = VadEngine(this)
        sttEngine = SttEngine(this)
        ttsEngine = TtsEngine(this)

        bluetoothTransport = BluetoothTransport(this)
        wifiDirectTransport = WifiDirectTransport(this)
        // Composite transport enables multi-peer relay (A↔R1 via BT, R1↔R2 via WiFi)
        currentTransport = CompositeTransport(listOf(bluetoothTransport!!, wifiDirectTransport!!))

        orchestrator = PipelineOrchestrator(
            context = this,
            audioRecorder = audioRecorder,
            audioPlayer = audioPlayer,
            audioFocusManager = audioFocusManager,
            vadEngine = vadEngine,
            sttEngine = sttEngine,
            ttsEngine = ttsEngine,
            transport = currentTransport
        )
        (application as com.itantra.iTantraApp).orchestrator = orchestrator
        orchestrator.speechModelManager.selectLanguage(orchestrator.currentLanguage)

        // Real persistent node identity.
        val profile = NodeIdentity.current()
        binding.tvNodeId.text = profile?.nodeId ?: getString(R.string.node_unknown)
    }

    // ---------------- Language dropdown ----------------

    private fun setupLanguageDropdown() {
        val adapter = LanguageAdapter(this, prototypeLanguages)
        binding.spinnerLanguage.adapter = adapter
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lang = prototypeLanguages[position]
                if (orchestrator.currentLanguage != lang) orchestrator.currentLanguage = lang
                adapter.notifyDataSetChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val initial = prototypeLanguages.indexOfFirst { it == orchestrator.currentLanguage }.coerceAtLeast(0)
        binding.spinnerLanguage.setSelection(initial)
    }

    /** Custom language dropdown: shows real STT/TTS availability per language. */
    private inner class LanguageAdapter(context: Context, items: List<SupportedLanguage>) :
        ArrayAdapter<SupportedLanguage>(context, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            bind(convertView, parent, position, dropdown = false)

        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            bind(convertView, parent, position, dropdown = true)

        private fun bind(convertView: View?, parent: android.view.ViewGroup, position: Int, dropdown: Boolean): View {
            val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_language, parent, false)
            val lang = getItem(position)!!
            val smm = orchestrator.speechModelManager
            val stt = smm.sttAvailable(lang.code)
            val tts = smm.ttsAvailable(lang.code)

            v.findViewById<TextView>(R.id.tvLangName).apply {
                text = lang.nativeName
                setTextColor(ContextCompat.getColor(context, if (dropdown) R.color.text_white else R.color.comm_green))
            }
            v.findViewById<TextView>(R.id.tvLangStatus).apply {
                text = when {
                    stt && tts -> "STT ✓  TTS ✓"
                    stt -> "STT ✓  TTS ✗"
                    tts -> "STT ✗  TTS ✓"
                    else -> "STT ✗  TTS ✗"
                }
                setTextColor(
                    ContextCompat.getColor(context, if (stt && tts) R.color.comm_green else R.color.comm_amber)
                )
            }
            return v
        }
    }

    // ---------------- Transport dropdown ----------------

    private val transportOptions = arrayOf("Bluetooth", "Wi-Fi Direct")

    private fun setupTransportDropdown() {
        binding.spinnerTransport.adapter = TransportAdapter(this, transportOptions)
        binding.spinnerTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                orchestrator.setupTransportListener()
                refreshPeerState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Custom transport dropdown items with per-transport icons. */
    private class TransportAdapter(context: Context, items: Array<String>) :
        ArrayAdapter<String>(context, 0, items) {

        private val icons = intArrayOf(R.drawable.ic_transport_bt, R.drawable.ic_transport_wifi)

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val v = inflate(convertView, parent)
            v.findViewById<ImageView>(R.id.ivTransportIcon).backgroundTintList =
                ContextCompat.getColorStateList(context, R.color.comm_green)
            v.findViewById<ImageView>(R.id.ivTransportIcon).setImageResource(icons[position])
            v.findViewById<ImageView>(R.id.ivTransportIcon).imageTintList =
                ContextCompat.getColorStateList(context, R.color.comm_green)
            val tv = v.findViewById<TextView>(R.id.tvTransportLabel)
            tv.text = getItem(position)
            tv.setTextColor(ContextCompat.getColor(context, R.color.comm_green))
            v.background = null
            return v
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val v = inflate(convertView, parent)
            val icon = v.findViewById<ImageView>(R.id.ivTransportIcon)
            icon.setImageResource(icons[position])
            icon.imageTintList = ContextCompat.getColorStateList(context, R.color.comm_green)
            val tv = v.findViewById<TextView>(R.id.tvTransportLabel)
            tv.text = getItem(position)
            tv.setTextColor(ContextCompat.getColor(context, R.color.text_white))
            return v
        }

        private fun inflate(convertView: View?, parent: android.view.ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context).inflate(
                R.layout.item_transport, parent, false
            )
            return v
        }
    }

    // ---------------- PTT + SOS ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttAndAlertButtons() {
        binding.btnPtt.setOnTouchListener { _, event ->
            if (orchestrator.operatingMode == OperatingMode.CONTINUOUS) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.btnPtt.backgroundTintList = ContextCompat.getColorStateList(this, R.color.comm_red)
                    binding.btnPtt.text = getString(R.string.ptt_release_to_send)
                    orchestrator.onPttPressed(isAlert = false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.btnPtt.text = getString(R.string.ptt_hold_to_talk)
                    orchestrator.onPttReleased()
                    renderStatus(orchestrator.transceiverState.value)
                    true
                }
                else -> false
            }
        }

        binding.btnInfo.setOnClickListener {
            // Incoming circle (history icon): shows real messages history.
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.messages_history))
                .setMessage(messageHistoryText())
                .setPositiveButton("OK", null)
                .show()
        }

        binding.btnSttInfo.setOnClickListener {
            // Second card (incoming message details), toggled by the bottom-left info circle.
            val expanded = binding.tvIncomingDetails.visibility == View.GONE
            binding.tvIncomingDetails.visibility = if (expanded) View.VISIBLE else View.GONE
            renderIncomingDetails()
        }

        binding.btnAlert.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.sos_confirm_title))
                .setMessage(getString(R.string.sos_confirm_body))
                .setNegativeButton(getString(R.string.sos_cancel), null)
                .setPositiveButton(getString(R.string.sos_send)) { _, _ ->
                    Toast.makeText(this, getString(R.string.sos_active), Toast.LENGTH_SHORT).show()
                    orchestrator.onPttPressed(isAlert = true)
                    binding.root.postDelayed({
                        orchestrator.onPttReleased()
                    }, 1000)
                }
                .show()
        }
    }

    // ---------------- Connectivity ----------------

    private fun setupDeviceConnection() {
        val label = binding.btnScanConnect
        label.setOnClickListener {
            if (currentTransport?.isConnected() == true) {
                startActivity(android.content.Intent(this, NetworkActivity::class.java))
                return@setOnClickListener
            }
            val transport = currentTransport ?: return@setOnClickListener
            transport.discoverDevices { devices ->
                runOnUiThread {
                    if (devices.isEmpty()) {
                        Toast.makeText(this, "No nearby devices found", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val names = devices.map { "${it.name} (${it.address})" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Select Transceiver Peer")
                        .setItems(names) { _, which ->
                            val selectedDevice = devices[which]
                            transport.connect(selectedDevice) { success ->
                                runOnUiThread {
                                    if (success) {
                                        orchestrator.initiateSessionHandshake()
                                        refreshPeerState()
                                    } else {
                                        Toast.makeText(this, getString(R.string.connection_failed_ui), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun setupNavigation() {
        binding.btnOpenModels.setOnClickListener {
            startActivity(android.content.Intent(this, LanguageModelsActivity::class.java))
        }
    }

    // ---------------- Observers ----------------

    private fun observeOrchestratorState() {
        lifecycleScope.launch {
            orchestrator.transceiverState.collectLatest { state ->
                runOnUiThread { renderStatus(state) }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastTranscribedText.collectLatest { text ->
                if (text.isNotBlank()) {
                    lastSttMessage = text
                    binding.tvLastSttText.text = text
                    binding.tvLastSttText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastReceivedText.collectLatest { text ->
                if (text.isNotBlank()) {
                    lastIncomingMessage = text
                    renderIncomingDetails()
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastLatencyMetrics.collectLatest { metrics ->
                runOnUiThread { renderLatency(metrics) }
            }
        }

        lifecycleScope.launch {
            orchestrator.topologyTick.collectLatest {
                runOnUiThread { refreshPeerState() }
            }
        }

        binding.perfChip.setOnClickListener {
            val expanded = binding.tvLatencyMetrics.visibility == View.GONE
            binding.tvLatencyMetrics.visibility = if (expanded) View.VISIBLE else View.GONE
            renderLatency(orchestrator.lastLatencyMetrics.value)
        }
    }

    /** E2E + breakdown from REAL measured latencies. "—" when none exist. */
    private fun renderLatency(metrics: com.itantra.benchmark.LatencyRecord?) {
        if (metrics == null) {
            binding.tvPerfSummary.text = getString(R.string.latency_placeholder)
            binding.tvLatencyMetrics.text = getString(R.string.latency_placeholder)
            return
        }
        binding.tvPerfSummary.text = getString(R.string.latency_ms, metrics.totalE2eLatencyMs)
        binding.tvLatencyMetrics.text =
            "STT              ${metrics.sttLatencyMs} ms\n" +
            "Transport        ${metrics.transportLatencyMs} ms\n" +
            "TTS              ${metrics.ttsLatencyMs} ms\n" +
            "RTF              ${String.format("%.2f", metrics.rtf)}\n" +
            "────────────────────────\n" +
            "E2E              ${metrics.totalE2eLatencyMs} ms"
        if (binding.tvLatencyMetrics.visibility == View.VISIBLE) {
            binding.tvLatencyMetrics.visibility = View.VISIBLE
        }
    }

    private fun renderIncomingDetails() {
        if (binding.tvIncomingDetails.visibility != View.VISIBLE) return
        binding.tvIncomingDetails.text = if (lastIncomingMessage.isBlank()) {
            getString(R.string.no_message)
        } else {
            "${getString(R.string.incoming)} · ${orchestrator.speechModelManager.currentLanguage().displayName}\n\u201C$lastIncomingMessage\u201D"
        }
    }

    /** Real messages history (own STT + received + delivery tracker). Never fabricated. */
    private fun messageHistoryText(): String {
        val sb = StringBuilder()
        if (lastSttMessage.isNotBlank()) {
            sb.append(getString(R.string.your_voice)).append("\n\u201C").append(lastSttMessage).append("\u201D\n\n")
        }
        if (lastIncomingMessage.isNotBlank()) {
            sb.append(getString(R.string.incoming)).append("\n\u201C").append(lastIncomingMessage).append("\u201D\n\n")
        }
        val statuses = orchestrator.deliveryTracker.getAll().takeLast(5)
        for (s in statuses) {
            sb.append("• ").append(s.recipientId).append(" [").append(s.recipientMode).append("]  ")
                .append("hops=").append(s.hopCount).append("  ").append(s.status).append("\n")
        }
        return if (sb.isBlank()) getString(R.string.no_message) else sb.toString().trimEnd()
    }

    /** Live peer/route/network state from Backend. Never fabricated. */
    private fun refreshPeerState() {
        val orch = orchestrator
        val connected = currentTransport?.isConnected() == true
        val neighbors = orch.meshRoutingManager?.discovery?.neighbors?.values ?: emptyList()
        val routes: List<RouteEntry> = orch.meshRoutingManager?.discovery?.getAllRoutes() ?: emptyList()
        val transport = binding.spinnerTransport.selectedItem?.toString() ?: "Bluetooth"

        // THIS DEVICE card: connection + real peer identity.
        if (connected && neighbors.isNotEmpty()) {
            val n = neighbors.maxByOrNull { it.lastSeenMs }!!
            binding.tvConnSummary.text = "● CONNECTED — ${n.nodeId}\nSecure session · ${n.displayName} · ${n.transportType}"
            binding.tvConnSummary.setTextColor(ContextCompat.getColor(this, R.color.comm_green))
            binding.btnScanConnect.text = getString(R.string.manage_connection)
        } else if (connected) {
            binding.tvConnSummary.text = getString(R.string.cta_online)
            binding.tvConnSummary.setTextColor(ContextCompat.getColor(this, R.color.comm_green))
            binding.btnScanConnect.text = getString(R.string.manage_connection)
        } else {
            binding.tvConnSummary.text = getString(R.string.cta_offline)
            binding.tvConnSummary.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.btnScanConnect.text = getString(R.string.connect_label)
        }

        // NETWORK mini-stats — only values that actually exist.
        val hops = routes.minOfOrNull { it.hopCount }?.toString() ?: "—"
        val queue = orch.deliveryTracker.getAll()
        binding.tvNetworkStats.text =
            "Peers: ${neighbors.size}   ·   Hops: $hops   ·   Transport: $transport\n" +
            "Security: ECDH P-256 · AES-256-GCM   ·   Queue: ${queue.size}"
    }

    // ---------------- Status rendering ----------------

    private fun renderStatus(state: TransceiverState) {
        val green = ContextCompat.getColor(this, R.color.comm_green)
        val amber = ContextCompat.getColor(this, R.color.comm_amber)
        val red = ContextCompat.getColor(this, R.color.comm_red)
        val white = ContextCompat.getColor(this, R.color.text_white)

        when (state) {
            TransceiverState.IDLE -> {
                binding.tvStatusText.text = getString(R.string.standby_ready)
                binding.tvStatusText.setTextColor(white)
                binding.btnPtt.text = getString(R.string.ptt_hold_to_talk)
                tintRadar(green, false)
            }
            TransceiverState.LISTENING -> {
                binding.tvStatusText.text = getString(R.string.listening_vad)
                binding.tvStatusText.setTextColor(green)
                tintRadar(green, true)
            }
            TransceiverState.TRANSCRIBING -> {
                binding.tvStatusText.text = getString(R.string.processing_voice)
                binding.tvStatusText.setTextColor(amber)
                binding.tvLastSttText.text = getString(R.string.processing_voice)
                tintRadar(amber, true)
            }
            TransceiverState.TRANSMITTING -> {
                binding.tvStatusText.text = getString(R.string.transmitting)
                binding.tvStatusText.setTextColor(amber)
                tintRadar(amber, true)
            }
            TransceiverState.RECEIVING -> {
                binding.tvStatusText.text = getString(R.string.receiving)
                binding.tvStatusText.setTextColor(green)
                tintRadar(green, true)
            }
            TransceiverState.SYNTHESIZING -> {
                binding.tvStatusText.text = getString(R.string.generating_voice)
                binding.tvStatusText.setTextColor(green)
                tintRadar(green, true)
            }
            TransceiverState.PLAYING -> {
                binding.tvStatusText.text = getString(R.string.playing)
                binding.tvStatusText.setTextColor(green)
                tintRadar(green, true)
            }
            TransceiverState.COLLISION_BUSY -> {
                binding.tvStatusText.text = getString(R.string.channel_busy)
                binding.tvStatusText.setTextColor(white)
                tintRadar(red, true)
            }
        }
    }

    /** Apply color + subtle pulse animation to the radar visual. */
    private fun tintRadar(color: Int, pulse: Boolean) {
        try {
            binding.btnPtt.backgroundTintList = ContextCompat.getColorStateList(this, color)
            val glow = ContextCompat.getDrawable(this, R.drawable.status_node)?.mutate() ?: return
            glow.setTint(color)
            binding.statusNodeGlow.background = glow

            if (pulse && !isPulsing) {
                isPulsing = true
                val scalePulse = ScaleAnimation(
                    1.0f, 1.06f, 1.0f, 1.06f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 900
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }
                binding.radarContainer.startAnimation(scalePulse)
            } else if (!pulse) {
                isPulsing = false
                binding.radarContainer.clearAnimation()
            }
        } catch (e: Exception) {
            // visual nicety only — never break the app
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.stopRecording()
        audioPlayer.stop()
        vadEngine.release()
        sttEngine.release()
        ttsEngine.release()
        currentTransport?.disconnect()
    }
}