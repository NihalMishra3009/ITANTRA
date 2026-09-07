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

    private val prototypeLanguages = SupportedLanguage.values().toList()

    /** Languages shown in the dropdown: only those with BOTH STT and TTS models
     *  actually available (bundled or installed). Falls back to all languages when
     *  none qualify yet, so the transceiver stays usable with STT-only. */
    private fun dropdownLanguages(): List<SupportedLanguage> {
        val smm = orchestrator.speechModelManager
        val full = prototypeLanguages.filter { smm.sttAvailable(it.code) && smm.ttsAvailable(it.code) }
        return if (full.isEmpty()) prototypeLanguages else full
    }

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

    /** Refresh the language dropdown after returning from the Models screen —
     *  a voice installed (or deleted) there must appear/disappear immediately. */
    override fun onResume() {
        super.onResume()
        try {
            rebuildLanguageDropdown()
        } catch (_: Exception) { }
        refreshPeerState()
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
        rebuildLanguageDropdown()
    }

    /** Rebuild the language dropdown from CURRENT model availability. Called at
     *  startup and again in onResume so a voice installed from the Models screen
     *  (or deleted) reflects immediately. */
    private fun rebuildLanguageDropdown() {
        val langs = dropdownLanguages()
        if (langs.isEmpty()) return
        val previous = binding.spinnerLanguage.selectedItem as? SupportedLanguage
        val adapter = LanguageAdapter(this, langs)
        binding.spinnerLanguage.adapter = adapter
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lang = langs[position]
                if (orchestrator.currentLanguage != lang) {
                    orchestrator.currentLanguage = lang
                    // (Re)initialize STT/TTS engines for this language — loads a
                    // downloaded voice pack when present, native asset otherwise.
                    // Heavy sherpa model load runs OFF the main/UI thread.
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        orchestrator.speechModelManager.selectLanguage(lang)
                    }
                }
                syncTargetDefault(lang, previous)
                adapter.notifyDataSetChanged()
                renderLanguageMode()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val initial = langs.indexOfFirst { it == orchestrator.currentLanguage }
            .takeIf { it >= 0 } ?: langs.indexOfFirst { it == previous }.takeIf { it >= 0 } ?: 0
        binding.spinnerLanguage.setSelection(initial, false)

        // Second (TO) dropdown: the language the receiver expects.
        val targetAdapter = LanguageAdapter(this, langs)
        binding.spinnerLanguageTarget.adapter = targetAdapter
        binding.spinnerLanguageTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val lang = langs[position]
                if (orchestrator.targetLanguage != lang) {
                    orchestrator.targetLanguage = lang
                }
                targetAdapter.notifyDataSetChanged()
                renderLanguageMode()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val tInitial = langs.indexOfFirst { it == orchestrator.targetLanguage }
            .takeIf { it >= 0 } ?: langs.indexOfFirst { it == orchestrator.currentLanguage }.takeIf { it >= 0 } ?: 0
        binding.spinnerLanguageTarget.setSelection(tInitial, false)
        renderLanguageMode()
    }

    /** Keep TO sane when FROM changes: default TO to the new source (same-language) until the user picks otherwise. */
    private fun syncTargetDefault(newSource: com.itantra.stt.SupportedLanguage, previous: com.itantra.stt.SupportedLanguage?) {
        // If the user had not explicitly chosen a different TO (or it pointed at the old FROM),
        // follow the new FROM.
        val t = binding.spinnerLanguageTarget.selectedItem as? com.itantra.stt.SupportedLanguage
        if (t == null || t == previous || t == orchestrator.targetLanguage) {
            orchestrator.targetLanguage = newSource
            val idx = dropdownLanguages().indexOfFirst { it == newSource }
            if (idx >= 0) binding.spinnerLanguageTarget.setSelection(idx, false)
        }
    }

    /** Render SAME-LANGUAGE vs CROSS-LANGUAGE mode + pipeline readiness where required models are reflected. */
    private fun renderLanguageMode() {
        refreshPeerState()
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

    private val transportOptions = arrayOf("AUTO · BT + Wi-Fi", "Bluetooth", "Wi-Fi Direct")

    private fun setupTransportDropdown() {
        binding.spinnerTransport.adapter = TransportAdapter(this, transportOptions)
        binding.spinnerTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // The selector drives the ACTUAL active radios. AUTO enables both;
                // explicit choices restrict the composite to that single transport.
                (currentTransport as? com.itantra.transport.CompositeTransport)?.enabledTypes = when (position) {
                    1 -> setOf(com.itantra.transport.TransportType.BLUETOOTH)
                    2 -> setOf(com.itantra.transport.TransportType.WIFI_DIRECT)
                    else -> setOf(
                        com.itantra.transport.TransportType.BLUETOOTH,
                        com.itantra.transport.TransportType.WIFI_DIRECT
                    )
                }
                orchestrator.setupTransportListener()
                refreshPeerState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Custom transport dropdown items with per-transport icons. */
    private class TransportAdapter(context: Context, items: Array<String>) :
        ArrayAdapter<String>(context, 0, items) {

        private val icons = intArrayOf(R.drawable.ic_transport_bt, R.drawable.ic_transport_bt, R.drawable.ic_transport_wifi)

        private fun iconFor(position: Int): Int = icons[position.coerceIn(0, icons.size - 1)]

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val v = inflate(convertView, parent)
            v.findViewById<ImageView>(R.id.ivTransportIcon).backgroundTintList =
                ContextCompat.getColorStateList(context, R.color.comm_green)
            v.findViewById<ImageView>(R.id.ivTransportIcon).setImageResource(iconFor(position))
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
            icon.setImageResource(iconFor(position))
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

    private fun themedDialog(): AlertDialog.Builder = AlertDialog.Builder(this, R.style.Theme_ITantra_Dialog)

    // ---------------- PTT + SOS ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttAndAlertButtons() {
        binding.btnPtt.setOnTouchListener { _, event ->
            if (orchestrator.operatingMode == OperatingMode.CONTINUOUS) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Phase 18: gate PTT for cross-language — do not let the user discover
                    // a missing model only after speaking. Same-language always allowed.
                    val src = orchestrator.sourceLanguage
                    val tgt = orchestrator.targetLanguage
                    if (src != tgt) {
                        val ready = orchestrator.speechModelManager.pipelineReady(src, tgt)
                        if (!ready) {
                            Toast.makeText(
                                this,
                                "Required offline models missing for ${src.nativeName} → ${tgt.displayName}. Open MODELS.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@setOnTouchListener true
                        }
                    }
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
            themedDialog()
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
            themedDialog()
                .setTitle(getString(R.string.sos_confirm_title))
                .setMessage(getString(R.string.sos_confirm_body))
                .setNegativeButton(getString(R.string.sos_cancel), null)
                .setPositiveButton(getString(R.string.sos_send)) { _, _ ->
                    Toast.makeText(this, getString(R.string.sos_active), Toast.LENGTH_SHORT).show()
                    // Dedicated emergency path: no microphone, no STT required.
                    orchestrator.sendSos()
                    renderSos(orchestrator.sosState.value)
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
                    themedDialog()
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

        lifecycleScope.launch {
            orchestrator.sosState.collectLatest { state ->
                runOnUiThread { renderSos(state) }
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
        val translateLine = if (metrics.translationLatencyMs > 0) {
            "Translation     ${metrics.translationLatencyMs} ms\n"
        } else {
            ""
        }
        binding.tvLatencyMetrics.text =
            "STT              ${metrics.sttLatencyMs} ms\n" +
            translateLine +
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
        val src = orch.sourceLanguage
        val tgt = orch.targetLanguage
        val cross = src != tgt
        val modeLine = if (cross) {
            "CROSS-LANGUAGE · ${src.nativeName} → ${tgt.displayName}"
        } else {
            "SAME-LANGUAGE · ${src.nativeName}"
        }
        binding.tvNetworkStats.text =
            "Peers: ${neighbors.size}   ·   Hops: $hops   ·   Transport: $transport\n" +
            "Security: ECDH P-256 · AES-256-GCM   ·   Queue: ${queue.size}\n" +
            modeLine + if (cross) networkModeSuffix() else ""
    }

    private fun networkModeSuffix(): String {
        val src = orchestrator.sourceLanguage
        val tgt = orchestrator.targetLanguage
        return if (orchestrator.speechModelManager.pipelineReady(src, tgt)) {
            "\nOffline translation ready"
        } else {
            "\nRequired offline models missing — Open MODELS"
        }
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
            TransceiverState.TRANSLATING -> {
                binding.tvStatusText.text = getString(R.string.translating)
                binding.tvStatusText.setTextColor(amber)
                binding.tvLastSttText.text = getString(R.string.translating)
                tintRadar(amber, true)
            }
            TransceiverState.TRANSLATION_FAILED -> {
                binding.tvStatusText.text = getString(R.string.translation_failed)
                binding.tvStatusText.setTextColor(red)
                binding.tvLastSttText.text = getString(R.string.translation_unavailable)
                tintRadar(red, false)
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

    /** Render the REAL SOS propagation state on the alert control + status line. */
    private fun renderSos(state: com.itantra.orchestrator.SosState) {
        val red = ContextCompat.getColor(this, R.color.comm_red)
        val amber = ContextCompat.getColor(this, R.color.comm_amber)
        val green = ContextCompat.getColor(this, R.color.comm_green)
        val white = ContextCompat.getColor(this, R.color.text_white)

        val label = when (state) {
            com.itantra.orchestrator.SosState.READY -> getString(R.string.sos_ready)
            com.itantra.orchestrator.SosState.SENDING -> getString(R.string.sos_sending)
            com.itantra.orchestrator.SosState.RELAYING -> getString(R.string.sos_relaying)
            com.itantra.orchestrator.SosState.DELIVERED -> getString(R.string.sos_delivered)
            com.itantra.orchestrator.SosState.RETRYING -> getString(R.string.sos_retrying)
            com.itantra.orchestrator.SosState.QUEUED_NO_PEER -> getString(R.string.sos_queued_nopeer)
            com.itantra.orchestrator.SosState.FAILED -> getString(R.string.sos_failed)
        }
        val color = when (state) {
            com.itantra.orchestrator.SosState.READY -> white
            com.itantra.orchestrator.SosState.SENDING -> amber
            com.itantra.orchestrator.SosState.RELAYING -> amber
            com.itantra.orchestrator.SosState.DELIVERED -> green
            com.itantra.orchestrator.SosState.RETRYING -> amber
            com.itantra.orchestrator.SosState.QUEUED_NO_PEER -> amber
            com.itantra.orchestrator.SosState.FAILED -> red
        }
        // Surface SOS state without clobbering the main PTT status: render into the
        // connection summary line only when a packet is in flight.
        if (state != com.itantra.orchestrator.SosState.READY) {
            binding.tvConnSummary.text = "🚨 $label"
            binding.tvConnSummary.setTextColor(color)
        } else {
            refreshPeerState()
        }
        binding.tvStatusText.text = if (state != com.itantra.orchestrator.SosState.READY) label else binding.tvStatusText.text
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
        // Cancel orchestration coroutines (discovery, poller, queue worker, scope).
        try { orchestrator.release() } catch (_: Exception) {}
        audioRecorder.stopRecording()
        audioPlayer.stop()
        vadEngine.release()
        sttEngine.release()
        ttsEngine.release()
        currentTransport?.disconnect()
    }
}