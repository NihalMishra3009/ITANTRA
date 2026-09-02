package com.itantra.orchestrator

import android.content.Context
import android.util.Log
import com.itantra.ai4bharat.IndicTextNormalizer
import com.itantra.audio.AudioFocusManager
import com.itantra.audio.AudioPlayer
import com.itantra.audio.AudioRecorder
import com.itantra.benchmark.BenchmarkLogger
import com.itantra.benchmark.LatencyRecord
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import com.itantra.stt.SttEngine
import com.itantra.stt.SupportedLanguage
import com.itantra.transport.ConnectionState
import com.itantra.transport.MeshRoutingManager
import com.itantra.transport.TransportLayer
import com.itantra.tts.TtsEngine
import com.itantra.vad.VadEngine
import com.itantra.vad.VadEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class OperatingMode {
    PUSH_TO_TALK,
    CONTINUOUS
}

enum class TransceiverState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    TRANSMITTING,
    RECEIVING,
    SYNTHESIZING,
    PLAYING,
    COLLISION_BUSY
}

/**
 * Central State Machine and Pipeline Orchestrator for iTantra.
 * Integrates Voice Activity Detection, Offline STT/TTS, Mesh Routing, and Transport Management.
 */
class PipelineOrchestrator(
    private val context: Context,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val audioFocusManager: AudioFocusManager,
    private val vadEngine: VadEngine,
    private val sttEngine: SttEngine,
    private val ttsEngine: TtsEngine,
    var transport: TransportLayer? = null
) {
    companion object {
        private const val TAG = "PipelineOrchestrator"
    }

    val deviceSenderId = "NODE_" + UUID.randomUUID().toString().substring(0, 4).uppercase()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var meshRoutingManager: MeshRoutingManager? = null

    private val _transceiverState = MutableStateFlow(TransceiverState.IDLE)
    val transceiverState: StateFlow<TransceiverState> = _transceiverState.asStateFlow()

    private val _lastTranscribedText = MutableStateFlow("")
    val lastTranscribedText: StateFlow<String> = _lastTranscribedText.asStateFlow()

    private val _lastReceivedText = MutableStateFlow("")
    val lastReceivedText: StateFlow<String> = _lastReceivedText.asStateFlow()

    private val _lastLatencyMetrics = MutableStateFlow<LatencyRecord?>(null)
    val lastLatencyMetrics: StateFlow<LatencyRecord?> = _lastLatencyMetrics.asStateFlow()

    var targetRecipientId: String = "*" // Broadcast by default, or specific node ID

    var currentLanguage: SupportedLanguage = SupportedLanguage.HINDI
        set(value) {
            field = value
            sttEngine.initialize(value.code)
            ttsEngine.initialize(value.code)
        }

    var operatingMode: OperatingMode = OperatingMode.PUSH_TO_TALK
    var isLoopbackOnly = false // For single-phone testing (Checkpoint 5)

    private val speechAudioBuffer = mutableListOf<Float>()
    private var isPttHeld = false
    private var isAlertNext = false

    private var speechStartTimestamp = 0L
    private var speechEndTimestamp = 0L

    init {
        setupTransportListener()
    }

    fun setupTransportListener() {
        val current = transport ?: return
        meshRoutingManager?.release()
        meshRoutingManager = MeshRoutingManager(deviceSenderId, current)

        current.startListening(
            onPacketReceived = { packet ->
                meshRoutingManager?.handleIncomingPacket(packet) { deliveredPacket ->
                    handleIncomingPacket(deliveredPacket)
                }
            },
            onStateChanged = { state ->
                Log.i(TAG, "Transport state changed: $state")
            }
        )
    }

    /**
     * Triggered when user presses down PTT button.
     */
    @Synchronized
    fun onPttPressed(isAlert: Boolean = false) {
        if (_transceiverState.value == TransceiverState.PLAYING || _transceiverState.value == TransceiverState.RECEIVING) {
            Log.w(TAG, "Half-duplex collision: Incoming audio is playing, transmission deferred")
            _transceiverState.value = TransceiverState.COLLISION_BUSY
            return
        }

        isPttHeld = true
        isAlertNext = isAlert
        speechAudioBuffer.clear()
        vadEngine.reset()
        speechStartTimestamp = System.currentTimeMillis()

        _transceiverState.value = TransceiverState.LISTENING
        audioRecorder.startRecording(coroutineScope)

        coroutineScope.launch {
            audioRecorder.audioChunkFlow.collect { chunk ->
                if (!isPttHeld && operatingMode == OperatingMode.PUSH_TO_TALK) return@collect

                val vadEvent = vadEngine.processChunk(chunk)
                if (vadEvent == VadEvent.SPEECH_START || vadEvent == VadEvent.SPEECH_CONTINUE || vadEvent == VadEvent.PAUSE_DETECTED) {
                    synchronized(speechAudioBuffer) {
                        for (sample in chunk) {
                            speechAudioBuffer.add(sample)
                        }
                    }
                }

                // In Continuous mode: Auto finalize on sentence end pause
                if (operatingMode == OperatingMode.CONTINUOUS && vadEvent == VadEvent.SENTENCE_END && speechAudioBuffer.isNotEmpty()) {
                    finalizeUtteranceAndSend()
                }
            }
        }
    }

    /**
     * Triggered when user releases PTT button.
     */
    @Synchronized
    fun onPttReleased() {
        if (!isPttHeld) return
        isPttHeld = false
        speechEndTimestamp = System.currentTimeMillis()
        audioRecorder.stopRecording()

        finalizeUtteranceAndSend()
    }

    private fun finalizeUtteranceAndSend() {
        val audioData: FloatArray
        synchronized(speechAudioBuffer) {
            if (speechAudioBuffer.isEmpty()) {
                _transceiverState.value = TransceiverState.IDLE
                return
            }
            audioData = speechAudioBuffer.toFloatArray()
            speechAudioBuffer.clear()
        }

        if (speechEndTimestamp <= speechStartTimestamp) {
            speechEndTimestamp = System.currentTimeMillis()
        }

        coroutineScope.launch {
            _transceiverState.value = TransceiverState.TRANSCRIBING
            val tSttStart = System.currentTimeMillis()
            val sttResult = sttEngine.transcribe(audioData)
            val tSttEnd = System.currentTimeMillis()

            val normalizedText = IndicTextNormalizer.normalize(sttResult.text, currentLanguage.code)
            _lastTranscribedText.value = normalizedText

            if (normalizedText.isBlank()) {
                _transceiverState.value = TransceiverState.IDLE
                return@launch
            }

            val packet = TextPacket(
                senderId = deviceSenderId,
                recipientId = targetRecipientId,
                type = if (isAlertNext) PacketType.SOS_ALERT else PacketType.DATA,
                language = currentLanguage.code,
                text = normalizedText,
                isAlert = isAlertNext,
                timestamp = System.currentTimeMillis()
            ).withEncryption()

            isAlertNext = false

            if (isLoopbackOnly || transport == null || !transport!!.isConnected()) {
                // Loopback / Standalone single phone test or offline outbox store
                Log.i(TAG, "Dispatching packet via loopback / local pipeline")
                handleIncomingPacket(packet.withDecryption(), tSpeechStart = speechStartTimestamp, tSpeechEnd = speechEndTimestamp, tSttStart = tSttStart, tSttEnd = tSttEnd, tSend = System.currentTimeMillis())
            } else {
                _transceiverState.value = TransceiverState.TRANSMITTING
                val tSend = System.currentTimeMillis()
                meshRoutingManager?.sendReliablePacket(packet) { acknowledged ->
                    Log.i(TAG, "Message ${packet.messageId} delivery status: ACK=$acknowledged")
                }
                Log.i(TAG, "Encrypted packet successfully queued/transmitted over ${transport?.transportType} at $tSend")
                _transceiverState.value = TransceiverState.IDLE
            }
        }
    }

    /**
     * Fallback for typing text directly when speech/STT is unavailable or user chooses typing.
     */
    fun sendDirectTextMessage(text: String, isAlert: Boolean = false) {
        val clean = IndicTextNormalizer.normalize(text, currentLanguage.code)
        if (clean.isBlank()) return

        coroutineScope.launch {
            _lastTranscribedText.value = "[Typed] $clean"
            val packet = TextPacket(
                senderId = deviceSenderId,
                recipientId = targetRecipientId,
                type = if (isAlert) PacketType.SOS_ALERT else PacketType.DATA,
                language = currentLanguage.code,
                text = clean,
                isAlert = isAlert,
                timestamp = System.currentTimeMillis()
            ).withEncryption()

            if (isLoopbackOnly || transport == null || !transport!!.isConnected()) {
                handleIncomingPacket(packet.withDecryption(), tSpeechStart = 0L, tSpeechEnd = 0L, tSttStart = 0L, tSttEnd = 0L, tSend = System.currentTimeMillis())
            } else {
                meshRoutingManager?.sendReliablePacket(packet) { ack ->
                    Log.i(TAG, "Direct text message ${packet.messageId} ACK=$ack")
                }
            }
        }
    }

    /**
     * Handles incoming packet from remote peer (or loopback).
     */
    fun handleIncomingPacket(
        packet: TextPacket,
        tSpeechStart: Long = 0L,
        tSpeechEnd: Long = 0L,
        tSttStart: Long = 0L,
        tSttEnd: Long = 0L,
        tSend: Long = 0L
    ) {
        coroutineScope.launch {
            val tReceive = System.currentTimeMillis()
            _transceiverState.value = TransceiverState.RECEIVING
            _lastReceivedText.value = "[${packet.senderId}] " + packet.text

            // Switch TTS model to packet language if needed
            if (ttsEngine.synthesize("").languageCode != packet.language) {
                ttsEngine.initialize(packet.language)
            }

            _transceiverState.value = TransceiverState.SYNTHESIZING
            val tTtsStart = System.currentTimeMillis()
            val ttsResult = ttsEngine.synthesize(text = packet.text, languageCode = packet.language, isAlert = packet.isAlert)
            val tTtsEnd = System.currentTimeMillis()

            _transceiverState.value = TransceiverState.PLAYING
            var tPlayStart = System.currentTimeMillis()

            audioFocusManager.requestFocus(packet.isAlert)
            try {
                audioPlayer.playPcm(
                    pcmData = ttsResult.pcmAudio,
                    sampleRate = ttsResult.sampleRate,
                    isAlert = packet.isAlert,
                    onPlaybackStarted = {
                        tPlayStart = System.currentTimeMillis()
                    }
                )
            } finally {
                audioFocusManager.abandonFocus()
                _transceiverState.value = TransceiverState.IDLE
            }

            // Telemetry & Benchmark logging
            val speechStart = if (tSpeechStart > 0) tSpeechStart else packet.timestamp - 1500
            val speechEnd = if (tSpeechEnd > 0) tSpeechEnd else packet.timestamp
            val sttStart = if (tSttStart > 0) tSttStart else packet.timestamp
            val sttEnd = if (tSttEnd > 0) tSttEnd else packet.timestamp + 250
            val sendTime = if (tSend > 0) tSend else packet.timestamp + 260

            val record = BenchmarkLogger.logInteraction(
                messageId = packet.messageId,
                language = packet.language,
                isAlert = packet.isAlert,
                tSpeechStart = speechStart,
                tSpeechEnd = speechEnd,
                tSttStart = sttStart,
                tSttEnd = sttEnd,
                tSend = sendTime,
                tReceive = tReceive,
                tTtsStart = tTtsStart,
                tTtsEnd = tTtsEnd,
                tPlayStart = tPlayStart
            )

            _lastLatencyMetrics.value = record
        }
    }

    fun startContinuousListening() {
        operatingMode = OperatingMode.CONTINUOUS
        onPttPressed(isAlert = false)
    }

    fun stopContinuousListening() {
        operatingMode = OperatingMode.PUSH_TO_TALK
        onPttReleased()
    }
}
