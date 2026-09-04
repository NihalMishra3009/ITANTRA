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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
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
import com.itantra.orchestrator.OperatingMode
import com.itantra.orchestrator.PipelineOrchestrator
import com.itantra.orchestrator.TransceiverState
import com.itantra.stt.SttEngine
import com.itantra.stt.SupportedLanguage
import com.itantra.transport.BluetoothTransport
import com.itantra.transport.CompositeTransport
import com.itantra.transport.ConnectionState
import com.itantra.transport.DeviceInfo
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
        setupLanguageSpinner()
        setupTransportToggle()
        setupModeSelector()
        setupPttAndAlertButtons()
        setupDeviceConnection()
        observeOrchestratorState()

        binding.btnOpenNetwork.setOnClickListener {
            startActivity(android.content.Intent(this, NetworkActivity::class.java))
        }

        renderStatus(TransceiverState.IDLE)

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
    }

    private fun setupLanguageSpinner() {
        val languages = SupportedLanguage.values()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.map { "${it.displayName} (${it.nativeName})" }
        )
        binding.spinnerLanguage.adapter = adapter
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = languages[position]
                orchestrator.currentLanguage = selected
                Toast.makeText(this@MainActivity, "Language: ${selected.displayName}", Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTransportToggle() {
        binding.toggleTransport.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTransportBt -> {
                        binding.tvStatusDetail.text = "Bluetooth"
                    }
                    R.id.btnTransportWifi -> {
                        binding.tvStatusDetail.text = "Wi-Fi Direct"
                    }
                }
                // Re-setup transport listener so MeshRoutingManager uses the composite
                orchestrator.setupTransportListener()
            }
        }
    }

    private fun setupModeSelector() {
        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioPtt -> {
                    orchestrator.stopContinuousListening()
                    binding.btnPtt.visibility = View.VISIBLE
                    binding.btnPtt.text = getString(R.string.ptt_hold_to_talk)
                    renderStatus(orchestrator.transceiverState.value)
                }
                R.id.radioContinuous -> {
                    orchestrator.startContinuousListening()
                    // Keep the central circle visible as the status node; the touch
                    // handler already ignores presses in continuous mode.
                    binding.btnPtt.visibility = View.VISIBLE
                    binding.btnPtt.text = getString(R.string.listening_active)
                    renderStatus(orchestrator.transceiverState.value)
                    Toast.makeText(this, "Continuous Mode Active: Listening with VAD", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttAndAlertButtons() {
        val activeRed = ContextCompat.getColor(this, R.color.comm_red)

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
                    // Restore the state-driven color (renderStatus will re-tint on next state emit).
                    binding.btnPtt.text = getString(R.string.ptt_hold_to_talk)
                    orchestrator.onPttReleased()
                    renderStatus(orchestrator.transceiverState.value)
                    true
                }
                else -> false
            }
        }

        binding.btnAlert.setOnClickListener {
            Toast.makeText(this, "Broadcasting SOS ALERT Message...", Toast.LENGTH_SHORT).show()
            orchestrator.onPttPressed(isAlert = true)
            binding.root.postDelayed({
                orchestrator.onPttReleased()
            }, 1000)
        }
    }

    private fun setupDeviceConnection() {
        binding.btnScanConnect.setOnClickListener {
            val transport = currentTransport ?: return@setOnClickListener
            binding.tvStatusDetail.text = getString(R.string.nearby_devices_scan)
            renderConnection(ConnectionState.CONNECTING)
            transport.discoverDevices { devices ->
                runOnUiThread {
                    if (devices.isEmpty()) {
                        binding.tvStatusDetail.text = getString(R.string.no_device)
                        Toast.makeText(this, "No nearby devices found", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val names = devices.map { "${it.name} (${it.address})" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Select Transceiver Peer")
                        .setItems(names) { _, which ->
                            val selectedDevice = devices[which]
                            binding.tvStatusDetail.text = "Connecting to ${selectedDevice.name}…"
                            renderConnection(ConnectionState.CONNECTING)
                            transport.connect(selectedDevice) { success ->
                                if (success) {
                                    binding.tvStatusText.text = getString(R.string.connected_indicator)
                                    binding.tvStatusDetail.text = selectedDevice.name
                                    renderConnection(ConnectionState.CONNECTED)
                                    orchestrator.initiateSessionHandshake()
                                } else {
                                    binding.tvStatusText.text = getString(R.string.connection_failed_ui)
                                    binding.tvStatusDetail.text = getString(R.string.connection_failed_ui)
                                    renderConnection(ConnectionState.ERROR)
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun observeOrchestratorState() {
        lifecycleScope.launch {
            orchestrator.transceiverState.collectLatest { state ->
                runOnUiThread {
                    renderStatus(state)
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastTranscribedText.collectLatest { text ->
                if (text.isNotBlank()) {
                    binding.tvLastSttText.text = text
                    binding.tvLastSttText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastReceivedText.collectLatest { text ->
                if (text.isNotBlank()) {
                    binding.tvLastReceivedText.text = text
                    binding.tvLastReceivedText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastLatencyMetrics.collectLatest { metrics ->
                metrics?.let {
                    binding.tvLatencyMetrics.text =
                        "STT: ${it.sttLatencyMs} ms    RTF: ${String.format("%.2f", it.rtf)}    " +
                        "NET: ${it.transportLatencyMs} ms    TTS: ${it.ttsLatencyMs} ms    " +
                        "E2E: ${it.totalE2eLatencyMs} ms"
                    binding.tvPerfSummary.text = "${it.totalE2eLatencyMs} ms  E2E"
                }
            }
        }

        binding.perfChip.setOnClickListener {
            val expanded = binding.tvLatencyMetrics.visibility == View.GONE
            binding.tvLatencyMetrics.visibility = if (expanded) View.VISIBLE else View.GONE
        }
    }

    /** Map existing TransceiverState to the central status visual. */
    private fun renderStatus(state: TransceiverState) {
        val green = ContextCompat.getColor(this, R.color.comm_green)
        val amber = ContextCompat.getColor(this, R.color.comm_amber)
        val red = ContextCompat.getColor(this, R.color.comm_red)
        val white = ContextCompat.getColor(this, R.color.text_white)

        when (state) {
            TransceiverState.IDLE -> {
                binding.tvStatusText.text = getString(R.string.standby_ready)
                binding.tvStatusText.setTextColor(white)
                tintRadar(green, false)
            }
            TransceiverState.LISTENING -> {
                binding.tvStatusText.text = getString(R.string.listening_active)
                binding.tvStatusText.setTextColor(green)
                tintRadar(green, true)
            }
            TransceiverState.TRANSCRIBING -> {
                binding.tvStatusText.text = getString(R.string.processing_voice)
                binding.tvStatusText.setTextColor(amber)
                tintRadar(amber, true)
            }
            TransceiverState.TRANSMITTING -> {
                binding.tvStatusText.text = getString(R.string.transmitting)
                binding.tvStatusText.setTextColor(amber)
                tintRadar(amber, true)
            }
            TransceiverState.RECEIVING -> {
                binding.tvStatusText.text = getString(R.string.receiving)
                binding.tvStatusText.setTextColor(amber)
                tintRadar(amber, true)
            }
            TransceiverState.SYNTHESIZING -> {
                binding.tvStatusText.text = getString(R.string.generating_voice)
                binding.tvStatusText.setTextColor(amber)
                tintRadar(amber, true)
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
                Toast.makeText(this, "Channel Busy (Half-Duplex)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderConnection(state: ConnectionState) {
        when (state) {
            ConnectionState.CONNECTED -> {
                binding.tvHeaderConn.text = getString(R.string.connected_indicator)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.comm_green)
            }
            ConnectionState.CONNECTING -> {
                binding.tvHeaderConn.text = getString(R.string.listening_active)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.comm_amber)
            }
            ConnectionState.DISCONNECTED -> {
                binding.tvHeaderConn.text = getString(R.string.offline_ready_indicator)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.comm_amber)
            }
            ConnectionState.ERROR -> {
                binding.tvHeaderConn.text = getString(R.string.disconnected_indicator)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.comm_red)
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
                val pulse = ScaleAnimation(
                    1.0f, 1.08f, 1.0f, 1.08f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 900
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }
                binding.radarContainer.startAnimation(pulse)
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
