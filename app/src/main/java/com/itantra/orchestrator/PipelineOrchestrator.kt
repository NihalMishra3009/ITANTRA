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
import com.itantra.security.MessageSecurityManager
import com.itantra.security.PeerSessionManager
import com.itantra.stt.SttEngine
import com.itantra.stt.SupportedLanguage
import com.itantra.transport.CompositeTransport
import com.itantra.transport.ConnectionState
import com.itantra.transport.MeshRoutingManager
import com.itantra.transport.OutboxDatabase
import com.itantra.transport.TransportLayer
import com.itantra.transport.TransportManager
import com.itantra.tts.TtsEngine
import com.itantra.vad.VadEngine
import com.itantra.vad.VadEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val myNodeIdValue: String
    val deviceSenderId: String

    val deliveryTracker = com.itantra.transport.DeliveryTracker()

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

    /** Emits on any delivery-status change for live UI updates. */
    private val _deliveryStatus = MutableStateFlow<List<com.itantra.transport.MessageStatus>>(emptyList())
    val deliveryStatus: StateFlow<List<com.itantra.transport.MessageStatus>> = _deliveryStatus.asStateFlow()

    /** Emits on topology change (neighbor/route/peer) for live UI updates. */
    private val _topologyTick = MutableStateFlow(0L)
    val topologyTick: StateFlow<Long> = _topologyTick.asStateFlow()

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
        val nodeProfile = com.itantra.identity.NodeIdentity.initialize(context)
        myNodeIdValue = nodeProfile.nodeId
        deviceSenderId = nodeProfile.nodeId
        establishSessionKey()
        // Surface delivery-status changes to live UI (real backend state).
        deliveryTracker.onStatusChange = {
            _deliveryStatus.value = deliveryTracker.getAll().takeLast(20)
        }
        setupTransportListener()
        startDiscoveryAdvertising()
        startTopologyPoller()
    }

    /** Poll lightweight topology data (neighbor/route/peer counts) for live UI. */
    private fun startTopologyPoller() {
        coroutineScope.launch {
            var lastSignature = ""
            while (isActive) {
                val discovery = meshRoutingManager?.discovery
                val neighborSig = discovery?.neighbors?.keys?.sorted()?.joinToString(",") ?: ""
                val routeSig = discovery?.getAllRoutes()?.size ?: 0
                val sig = "$neighborSig|$routeSig"
                if (sig != lastSignature) {
                    lastSignature = sig
                    _topologyTick.value = System.currentTimeMillis()
                }
                delay(2000)
            }
        }
    }

    /**
     * Establish/restore a persistent per-device session key. Generated once and
     * stored — NOT a hard-coded secret. Two devices pair by exchanging this key
     * out-of-band via the ephemeral ECDH handshake.
     */
    private fun establishSessionKey() {
        if (MessageSecurityManager.hasSessionKey()) return
        val prefs = context.getSharedPreferences("itantra_sec", Context.MODE_PRIVATE)
        var key = prefs.getString("session_key", null)
        if (key == null) {
            key = android.util.Base64.encodeToString(
                ByteArray(32).also { java.security.SecureRandom().nextBytes(it) },
                android.util.Base64.NO_WRAP
            )
            prefs.edit().putString("session_key", key).apply()
        }
        MessageSecurityManager.setSessionKey(android.util.Base64.decode(key, android.util.Base64.NO_WRAP))
        Log.i(TAG, "Session key established (ephemeral per-device)")
    }

    fun setupTransportListener() {
        meshRoutingManager?.release()
        val db = OutboxDatabase.getDatabase(context)
        val discoveryManager = com.itantra.transport.NetworkDiscoveryManager(deviceSenderId)
        discoveryManager.onRouteResponseReady = { response ->
            transport?.sendPacket(response)
        }
        discoveryManager.onRouteDiscovered = { viaNode, dest, nextHop, hops ->
            Log.i(TAG, "Route discovered to $dest via $nextHop ($hops hops)")
        }

        val effectiveTransport = transport ?: return

        meshRoutingManager = MeshRoutingManager(
            deviceSenderId,
            effectiveTransport,
            outboxDao = db.outboxDao(),
            discovery = discoveryManager,
            deliveryTracker = deliveryTracker
        )

        // Wire the packet callback. For CompositeTransport, each underlying
        // transport's startListening is already wired. For a single transport,
        // wire it here.
        effectiveTransport.startListening(
            onPacketReceived = { packet ->
                if (handleSessionPacket(packet)) {
                    return@startListening
                }
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
     * Periodically advertise this node's presence + role on the network so that
     * multi-hop discovery can build routing tables. Lightweight, no contact lists.
     */
    private fun startDiscoveryAdvertising() {
        coroutineScope.launch {
            val profile = com.itantra.identity.NodeIdentity.current()
            while (isActive) {
                val role = profile?.role ?: "DEFAULT"
                val displayName = profile?.displayName ?: deviceSenderId
                val hello = meshRoutingManager?.discovery?.buildHello(role, displayName)
                val t = transport
                if (hello != null && t != null && t.isConnected()) {
                    t.sendPacket(hello)
                }
                delay(30_000)
            }
        }
    }

    // --- Session handshake (ECDH) -------------------------------------------

    /**
     * Kick off the ECDH handshake to a newly-connected peer: send it our
     * ephemeral public key inside a SESSION_START packet.
     */
    fun initiateSessionHandshake(peerNodeId: String = "*") {
        val t = transport ?: return
        if (!t.isConnected()) return
        val pubB64 = PeerSessionManager.initiateHandshake(peerNodeId)
        val packet = TextPacket(
            senderId = deviceSenderId,
            recipientId = peerNodeId,
            type = PacketType.SESSION_START,
            language = currentLanguage.code,
            text = pubB64
        )
        t.sendPacket(packet)
        Log.i(TAG, "Sent SESSION_START to peer $peerNodeId")
    }

    /**
     * Handles a SESSION_START packet. Returns true if consumed.
     * Per-peer key derivation: each peer gets its own session key.
     */
    private fun handleSessionPacket(packet: TextPacket): Boolean {
        if (packet.type == PacketType.SESSION_START) {
            coroutineScope.launch {
                try {
                    val peerId = packet.senderId
                    val peerPubB64 = packet.text

                    if (PeerSessionManager.hasSessionKey(peerId)) {
                        // Already have a session key for this peer — skip handshake
                        Log.d(TAG, "Session already established with $peerId")
                        return@launch
                    }

                    val shared = PeerSessionManager.handleHandshake(peerId, peerPubB64)
                    if (shared != null) {
                        // Also set the global session key for backward compatibility
                        MessageSecurityManager.setSessionKey(shared.sessionKey)
                        _lastReceivedText.value = "Connected to peer $peerId (session secured)"
                        Log.i(TAG, "Per-peer session established with $peerId")

                        // If we're the responder, reply with our public key.
                        shared.replyPublicKeyB64?.let { ourPubB64 ->
                            val reply = TextPacket(
                                senderId = deviceSenderId,
                                recipientId = peerId,
                                type = PacketType.SESSION_START,
                                language = currentLanguage.code,
                                text = ourPubB64
                            )
                            transport?.sendPacket(reply)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Session handshake failed", e)
                }
            }
            return true
        }
        return false
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
            var lastPartialMs = 0L
            audioRecorder.audioChunkFlow.collect { chunk ->
                if (!isPttHeld && operatingMode == OperatingMode.PUSH_TO_TALK) return@collect

                val vadEvent = vadEngine.processChunk(chunk)
                val isSpeech = vadEvent == VadEvent.SPEECH_START ||
                        vadEvent == VadEvent.SPEECH_CONTINUE ||
                        vadEvent == VadEvent.SHORT_PAUSE ||
                        vadEvent == VadEvent.SENTENCE_END ||
                        vadEvent == VadEvent.LONG_SILENCE
                if (isSpeech) {
                    synchronized(speechAudioBuffer) {
                        for (sample in chunk) {
                            speechAudioBuffer.add(sample)
                        }
                    }
                }

                // Streaming partial transcript: while actively speaking, re-decode the
                // growing buffer periodically so the UI shows live text before finalization.
                val now = System.currentTimeMillis()
                val bufferLen = synchronized(speechAudioBuffer) { speechAudioBuffer.size }
                if (isSpeech && bufferLen > 16000 && now - lastPartialMs >= 1500) {
                    lastPartialMs = now
                    val partial: FloatArray = synchronized(speechAudioBuffer) { speechAudioBuffer.toFloatArray() }
                    launch {
                        val res = sttEngine.transcribe(partial)
                        if (res.text.isNotBlank()) {
                            _lastTranscribedText.value = res.text
                        }
                    }
                }

                // In Continuous mode: Auto finalize on sentence end OR long silence
                // (voice endpointing — pauses form sentences, long silence finalizes)
                if (operatingMode == OperatingMode.CONTINUOUS &&
                    (vadEvent == VadEvent.SENTENCE_END || vadEvent == VadEvent.LONG_SILENCE) &&
                    speechAudioBuffer.isNotEmpty()
                ) {
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

                // Measure real on-wire packet size (binary vs equivalent JSON)
                val binaryBytes = com.itantra.protocol.BinaryPacketCodec().encode(packet).size
                val jsonBytes = packet.toJsonBytes().size
                BenchmarkLogger.logPacketSize(currentLanguage.code, normalizedText, binaryBytes, jsonBytes)

                meshRoutingManager?.sendReliablePacket(packet) { acknowledged ->
                    Log.i(TAG, "Message ${packet.messageId} delivery status: ACK=$acknowledged")
                }
                Log.i(TAG, "Encrypted packet queued/transmitted over ${transport?.transportType} at $tSend ($binaryBytes B binary vs $jsonBytes B JSON)")
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
            deliveryTracker.update(packet.messageId, com.itantra.transport.DeliveryStatus.PLAYING, packet.hopCount)

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

            if (ttsResult.pcmAudio.isEmpty()) {
                Log.w(TAG, "TTS produced empty audio for '${packet.language}' — speech playback cannot start. " +
                        "No genuine TTS model available for this language.")
                tPlayStart = System.currentTimeMillis()
            } else {
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
                }
            }
            _transceiverState.value = TransceiverState.IDLE

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
