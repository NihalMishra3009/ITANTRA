package com.itantra.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
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
        currentTransport = bluetoothTransport

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
                        currentTransport?.disconnect()
                        currentTransport = bluetoothTransport
                        orchestrator.transport = bluetoothTransport
                        orchestrator.setupTransportListener()
                        binding.tvStatusText.text = "Transport: Bluetooth (Ready)"
                    }
                    R.id.btnTransportWifi -> {
                        currentTransport?.disconnect()
                        currentTransport = wifiDirectTransport
                        orchestrator.transport = wifiDirectTransport
                        orchestrator.setupTransportListener()
                        binding.tvStatusText.text = "Transport: Wi-Fi Direct (Ready)"
                    }
                }
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
                }
                R.id.radioContinuous -> {
                    orchestrator.startContinuousListening()
                    binding.btnPtt.visibility = View.GONE
                    Toast.makeText(this, "Continuous Mode Active: Listening with VAD", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttAndAlertButtons() {
        binding.btnPtt.setOnTouchListener { _, event ->
            if (orchestrator.operatingMode == OperatingMode.CONTINUOUS) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.ptt_active))
                    binding.btnPtt.text = getString(R.string.ptt_release_to_send)
                    orchestrator.onPttPressed(isAlert = false)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.btnPtt.setBackgroundColor(ContextCompat.getColor(this, R.color.ptt_idle))
                    binding.btnPtt.text = getString(R.string.ptt_hold_to_talk)
                    orchestrator.onPttReleased()
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
            binding.tvStatusText.text = "Scanning nearby devices..."
            transport.discoverDevices { devices ->
                runOnUiThread {
                    if (devices.isEmpty()) {
                        Toast.makeText(this, "No paired / nearby devices found", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val names = devices.map { "${it.name} (${it.address})" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Select Transceiver Peer")
                        .setItems(names) { _, which ->
                            val selectedDevice = devices[which]
                            binding.tvStatusText.text = "Connecting to ${selectedDevice.name}..."
                            transport.connect(selectedDevice) { success ->
                                if (success) {
                                    binding.tvStatusText.text = "Connected: ${selectedDevice.name} (establishing secure session…)"
                                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
                                    orchestrator.initiateSessionHandshake()
                                } else {
                                    binding.tvStatusText.text = "Connection Failed"
                                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
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
                    when (state) {
                        TransceiverState.IDLE -> {
                            binding.tvStatusText.text = "Status: Standby (Offline Ready)"
                        }
                        TransceiverState.LISTENING -> {
                            binding.tvStatusText.text = "Status: Listening (VAD Active)…"
                        }
                        TransceiverState.TRANSCRIBING -> {
                            binding.tvStatusText.text = "Status: Transcribing (Offline STT)…"
                        }
                        TransceiverState.TRANSMITTING -> {
                            binding.tvStatusText.text = "Status: Transmitting Packet…"
                        }
                        TransceiverState.RECEIVING -> {
                            binding.tvStatusText.text = "Status: Receiving Packet…"
                        }
                        TransceiverState.SYNTHESIZING -> {
                            binding.tvStatusText.text = "Status: Synthesizing Audio (TTS)…"
                        }
                        TransceiverState.PLAYING -> {
                            binding.tvStatusText.text = "Status: Playing Audio (Speaker)…"
                        }
                        TransceiverState.COLLISION_BUSY -> {
                            binding.tvStatusText.text = "Channel Busy: Incoming Audio Active"
                            Toast.makeText(this@MainActivity, "Channel Busy (Half-Duplex)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastTranscribedText.collectLatest { text ->
                if (text.isNotBlank()) {
                    binding.tvLastSttText.text = text
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastReceivedText.collectLatest { text ->
                if (text.isNotBlank()) {
                    binding.tvLastReceivedText.text = text
                }
            }
        }

        lifecycleScope.launch {
            orchestrator.lastLatencyMetrics.collectLatest { metrics ->
                metrics?.let {
                    binding.tvLatencyMetrics.text =
                        "STT: ${it.sttLatencyMs}ms (RTF ${String.format("%.2f", it.rtf)}) | Net: ${it.transportLatencyMs}ms | TTS: ${it.ttsLatencyMs}ms | E2E: ${it.totalE2eLatencyMs}ms"
                }
            }
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
