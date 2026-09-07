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
    TRANSLATING,
    TRANSLATION_FAILED,
    TRANSMITTING,
    RECEIVING,
    SYNTHESIZING,
    PLAYING,
    COLLISION_BUSY
}

/** SOS propagation state — reflects real emergency packet delivery. */
enum class SosState {
    READY,          // no emergency in flight
    SENDING,        // emergency injected + transmitted
    RELAYING,       // forwarded for multi-hop delivery
    DELIVERED,      // final ACK received
    RETRYING,       // transmission in progress but no ACK yet
    QUEUED_NO_PEER, // no peer/transport available — stored for later
    FAILED          // delivery failed / exceeded retries
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

    /** Speech ML facade — routes STT/TTS through managers for lazy loading + fallback. */
    val speechModelManager = com.itantra.speech.SpeechModelManager(
        context = context,
        sttEngine = sttEngine,
        ttsEngine = ttsEngine,
        vadEngine = vadEngine
    )

    var currentLanguage: SupportedLanguage = SupportedLanguage.HINDI
        set(value) {
            if (field != value) {
                field = value
                // Heavy model (re)initialization is deferred off the calling thread
                // (Mirrors the UI thread — sherpa load is slow and must not block the
                // main thread). speechModelManager.selectLanguage() is idempotent.
                coroutineScope.launch(Dispatchers.IO) {
                    speechModelManager.selectLanguage(value)
                    sttEngine.initialize(value.code)
                    ttsEngine.initialize(value.code)
                }
            }
        }

    /**
     * CROSS-LANGUAGE: the language spoken into the microphone (STT input) —
     * mirrors [currentLanguage] for backward compatibility.
     */
    var sourceLanguage: SupportedLanguage
        get() = currentLanguage
        set(value) { currentLanguage = value }

    /** The language the receiver expects — the transmitted text + receiver TTS language.
     *  Defaults to the source language (SAME-LANGUAGE MODE). */
    var targetLanguage: SupportedLanguage = SupportedLanguage.HINDI

    /** Active directed language pair (source → target). */
    val activeLanguagePair: com.itantra.translation.LanguagePair
        get() = com.itantra.translation.LanguagePair(currentLanguage, targetLanguage)

    /** True when cross-language mode is selected (source != target). */
    val isCrossLanguageMode: Boolean get() = sourceLanguage != targetLanguage

    var operatingMode: OperatingMode = OperatingMode.PUSH_TO_TALK
    var isLoopbackOnly = false // For single-phone testing (Checkpoint 5)

    /**
     * Peer capability cache: nodeId -> (tts languages, mt pairs) parsed from their
     * hello/announce. Used for optional AUTO target selection (Phase 34). Minimal —
     * not a contact-management system.
     */
    data class PeerCapabilities(val ttsLanguages: Set<String>, val mtPairs: Set<String>)

    private val peerCapabilities = java.util.concurrent.ConcurrentHashMap<String, PeerCapabilities>()

    /** Bounded ad-hoc map for peer -> target language override (Phase 34). */
    private val peerTargetLanguage = java.util.concurrent.ConcurrentHashMap<String, SupportedLanguage>()

    fun setPeerTargetLanguage(peerId: String, lang: SupportedLanguage) {
        peerTargetLanguage[peerId] = lang
    }

    fun peerTargetLanguage(peerId: String): SupportedLanguage? =
        peerTargetLanguage[peerId]

    /** Built capability string from ACTUALLY installed models for the hello packet. */
    fun buildCapabilityString(): String {
        val smm = speechModelManager
        val stt = SupportedLanguage.values()
            .filter { smm.sttAvailable(it.code) }
            .map { it.code }.toSet()
        val tts = SupportedLanguage.values()
            .filter { smm.ttsAvailable(it.code) }
            .map { it.code }.toSet()
        // Only advertise translation pairs whose pack files are actually installed.
        val mtInstalled = com.itantra.translation.TranslationCatalog.supportedPairIds()
            .filter { pairKey ->
                val parts = pairKey.split("-")
                val s = SupportedLanguage.fromCode(parts.getOrElse(0) { "hi" })
                val t = SupportedLanguage.fromCode(parts.getOrElse(1) { "hi" })
                smm.translationInstalled(s, t)
            }
            .toSet()
        return com.itantra.transport.CapabilityFormat.build(stt, tts, mtInstalled)
    }

    /** Parse + cache a peer's advertised capabilities from hello/announce. */
    fun recordPeerCapabilities(peerId: String, helloText: String) {
        if (peerId.isBlank() || peerId == deviceSenderId) return
        peerCapabilities[peerId] = PeerCapabilities(
            ttsLanguages = com.itantra.transport.CapabilityFormat.parseTtsLanguages(helloText),
            mtPairs = com.itantra.transport.CapabilityFormat.parseMtPairs(helloText)
        )
    }

    fun peerCapabilities(peerId: String): PeerCapabilities? = peerCapabilities[peerId]

    /**
     * Optional AUTO TARGET: when the destination peer advertises a TTS language,
     * set the target language to it (unless the user forced a manual target).
     */
    fun applyAutoTargetIfAvailable(peerId: String): Boolean {
        val caps = peerCapabilities[peerId] ?: return false
        val ttsLang = caps.ttsLanguages.firstOrNull() ?: return false
        val lang = SupportedLanguage.fromCode(ttsLang)
        if (lang.code != ttsLang) return false // unknown code advertised — ignore
        targetLanguage = lang
        return true
    }

    private val speechAudioBuffer = mutableListOf<Float>()
    private var isPttHeld = false
    private var isAlertNext = false

    private var speechStartTimestamp = 0L
    private var speechEndTimestamp = 0L

    init {
        val nodeProfile = com.itantra.identity.NodeIdentity.initialize(context)
        myNodeIdValue = nodeProfile.nodeId
        deviceSenderId = nodeProfile.nodeId
        // NOTE: no global session key. Every wire packet is encrypted + authenticated
        // per-hop with the immediate peer's session key (see PeerSessionManager +
        // transport writePeerFrame). This init only provisions the persistent identity.
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
     * Build a wire packet. Payload encryption is applied per-hop by the transport
     * boundary with the immediate peer's session key (hop-level model). Loopback
     * (single-phone test) takes the plaintext directly — no wire exists.
     */
    private fun buildPacket(text: String, language: String, isAlert: Boolean, type: PacketType): TextPacket {
        return TextPacket(
            senderId = deviceSenderId,
            recipientId = targetRecipientId,
            type = type,
            language = language,
            text = text,
            isAlert = isAlert,
            timestamp = System.currentTimeMillis()
        )
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
                // Record peer capability advertisement for cross-language AUTO target.
                if (packet.type == PacketType.NODE_HELLO || packet.type == PacketType.NODE_ANNOUNCE) {
                    recordPeerCapabilities(packet.senderId, packet.text)
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
                val caps = buildCapabilityString()
                val hello = meshRoutingManager?.discovery?.buildHello(role, displayName, caps)
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
     * Per-peer key derivation: each peer gets its own session key. Normal DATA
     * packets are encrypted with the DIRECT peer's key at the transport boundary;
     * this handshake only establishes that per-peer key material.
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
        speechStartTimestamp = BenchmarkLogger.nowMs()

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
                        val res = speechModelManager.transcribe(partial)
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
        speechEndTimestamp = BenchmarkLogger.nowMs()
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
            speechEndTimestamp = BenchmarkLogger.nowMs()
        }

        coroutineScope.launch {
            _transceiverState.value = TransceiverState.TRANSCRIBING
            val tSttStart = BenchmarkLogger.nowMs()
            val sttResult = speechModelManager.transcribe(audioData)
            val tSttEnd = BenchmarkLogger.nowMs()

            val normalizedText = IndicTextNormalizer.normalize(sttResult.text, currentLanguage.code)
            _lastTranscribedText.value = normalizedText

            if (normalizedText.isBlank()) {
                _transceiverState.value = TransceiverState.IDLE
                return@launch
            }

            // CROSS-LANGUAGE: translate BEFORE encryption (sender-side).
            // SAME-LANGUAGE MODE (source == target) skips translation entirely.
            val targetLang = targetLanguage
            var translationResult: com.itantra.translation.TranslationResult? = null
            val packetText: String
            val packetLanguage: String

            val outcome = com.itantra.translation.CrossLanguagePipeline.apply(
                sourceText = normalizedText,
                source = sourceLanguage,
                target = targetLang,
                translate = { txt, s, t ->
                    speechModelManager.translate(txt, s, t)
                }
            )
            when {
                outcome is com.itantra.translation.CrossLanguagePipeline.Outcome.SameLanguage -> {
                    packetText = outcome.text
                    packetLanguage = sourceLanguage.code
                }
                outcome is com.itantra.translation.CrossLanguagePipeline.Outcome.Translated -> {
                    _transceiverState.value = TransceiverState.TRANSLATING
                    translationResult = TranslationResultFor(outcome)
                    packetText = outcome.text
                    packetLanguage = outcome.target
                }
                outcome is com.itantra.translation.CrossLanguagePipeline.Outcome.Unavailable -> {
                    // NEVER send untranslated text mislabeled as the target language.
                    Log.w(TAG, "Cross-language translation unavailable: ${outcome.error}")
                    _lastReceivedText.value = "Cross-language unavailable: ${outcome.error}"
                    _transceiverState.value = TransceiverState.TRANSLATION_FAILED
                    return@launch
                }
                else -> return@launch
            }

            val packet = buildPacket(
                text = packetText,
                language = packetLanguage,
                isAlert = isAlertNext,
                type = if (isAlertNext) PacketType.EMERGENCY else PacketType.DATA
            )
            isAlertNext = false

            if (isLoopbackOnly || transport == null || !transport!!.isConnected()) {
                // Loopback / Standalone single phone test or offline outbox store
                Log.i(TAG, "Dispatching packet via loopback / local pipeline")
                handleIncomingPacket(
                    packet,
                    tSpeechStart = speechStartTimestamp, tSpeechEnd = speechEndTimestamp,
                    tSttStart = tSttStart, tSttEnd = tSttEnd,
                    tSend = BenchmarkLogger.nowMs(),
                    tTransStart = translationResult?.latencyMs?.let { translateStartFrom(tSttEnd, it) } ?: 0L,
                    tTransEnd = translationResult?.let { tSttEnd } ?: 0L,
                    translationLatency = translationResult?.latencyMs ?: 0L
                )
            } else {
                _transceiverState.value = TransceiverState.TRANSMITTING
                val tSend = BenchmarkLogger.nowMs()

                // Measure real on-wire packet size (binary vs equivalent JSON) using a
                // hop-encrypted packet so the size reflects the authenticated wire form.
                val peerKey = PeerSessionManager.activePeerIds().firstOrNull()
                    ?.let { PeerSessionManager.getSessionKey(it) }
                val binaryBytes = if (peerKey != null) {
                    com.itantra.protocol.BinaryPacketCodec().encode(packet.withEncryption(peerKey), peerKey).size
                } else {
                    // No established peer yet: report the plaintext binary size — the wire
                    // form would be larger; never fabricate an authenticated size we didn't make.
                    com.itantra.protocol.BinaryPacketCodec().encode(packet, skipAuth = true).size
                }
                val jsonBytes = packet.toJsonBytes().size
                BenchmarkLogger.logPacketSize(packetLanguage, packetText, binaryBytes, jsonBytes)
                // Packet-size comparison: source vs translated (both real measurements).
                BenchmarkLogger.logTranslationPacketSize(
                    language = sourceLanguage.code,
                    sourceText = normalizedText,
                    targetText = packetText,
                    packetBytes = binaryBytes
                )

                meshRoutingManager?.sendReliablePacket(packet) { acknowledged ->
                    Log.i(TAG, "Message ${packet.messageId} delivery status: ACK=$acknowledged")
                }
                Log.i(TAG, "Encrypted packet ($packetLanguage) queued/transmitted at $tSend ($binaryBytes B wire vs $jsonBytes B JSON)")
                _transceiverState.value = TransceiverState.IDLE
            }
        }
    }

    private fun translateStartFrom(transEnd: Long, latency: Long): Long = transEnd - latency

    /** Reconstruct a TranslationResult from a successful pipeline outcome for benchmark logging. */
    private fun TranslationResultFor(outcome: com.itantra.translation.CrossLanguagePipeline.Outcome.Translated): com.itantra.translation.TranslationResult =
        com.itantra.translation.TranslationResult(
            translatedText = outcome.text,
            sourceLanguage = sourceLanguage.code,
            targetLanguage = outcome.target,
            latencyMs = outcome.latencyMs,
            success = true
        )

    /**
     * Fallback for typing text directly when speech/STT is unavailable or user chooses typing.
     * Cross-language: transcription/typed text in [sourceLanguage] is translated to
     * [targetLanguage] before transmission (SOS/alert bypasses translation).
     */
    fun sendDirectTextMessage(text: String, isAlert: Boolean = false) {
        val clean = IndicTextNormalizer.normalize(text, currentLanguage.code)
        if (clean.isBlank()) return

        coroutineScope.launch {
            _lastTranscribedText.value = "[Typed] $clean"
            // SOS / alert never goes through translation.
            val type = if (isAlert) PacketType.EMERGENCY else PacketType.DATA
            val lang = if (isAlert) sourceLanguage.code else targetLanguage.code
            val outText = if (isAlert || sourceLanguage == targetLanguage) clean else {
                val res = speechModelManager.translate(clean, sourceLanguage.code, targetLanguage.code)
                if (!res.success || res.translatedText.isBlank()) {
                    _lastReceivedText.value = "Cross-language unavailable: ${res.error}"
                    _transceiverState.value = TransceiverState.TRANSLATION_FAILED
                    return@launch
                }
                res.translatedText
            }
            val packet = buildPacket(text = outText, language = lang, isAlert = isAlert, type = type)

            if (isLoopbackOnly || transport == null || !transport!!.isConnected()) {
                handleIncomingPacket(packet, tSpeechStart = 0L, tSpeechEnd = 0L, tSttStart = 0L, tSttEnd = 0L, tSend = BenchmarkLogger.nowMs())
            } else {
                meshRoutingManager?.sendReliablePacket(packet) { ack ->
                    Log.i(TAG, "Direct text message ${packet.messageId} ACK=$ack")
                }
            }
        }
    }

    // ---------------- SOS / Emergency pipeline ----------------

    /** Current SOS propagation state — driven by real backend status. */
    private val _sosState = MutableStateFlow(SosState.READY)
    val sosState: StateFlow<SosState> = _sosState.asStateFlow()

    /** Current emergency message id being tracked (for dedupe of UI updates). */
    private val _activeSosMessageId = MutableStateFlow<String?>(null)
    val activeSosMessageId: StateFlow<String?> = _activeSosMessageId.asStateFlow()

    /**
     * Dedicated emergency pipeline — NEVER depends on the microphone or STT.
     *
     * 1. Build an EMERGENCY packet immediately.
     * 2. Inject directly into the mesh with front-of-queue priority.
     * 3. Request ACK when a peer is reachable; persist for store-and-forward.
     * 4. Surface SOS_* state through [sosState]; never silently fails.
     *
     * @return the emergency messageId (for UI to track).
     */
    fun sendSos(
        message: String = "SOS — Emergency assistance required",
        recipientId: String? = null
    ): String {
        val clean = message.trim().ifBlank { "SOS — Emergency assistance required" }
        val packet = TextPacket(
            senderId = deviceSenderId,
            recipientId = recipientId ?: targetRecipientId,
            type = PacketType.EMERGENCY,
            language = currentLanguage.code,
            text = clean,
            isAlert = true,
            isPriority = true,
            timestamp = System.currentTimeMillis(),
            ttlMs = 60_000L, // emergency lifetime
            maxHops = 5       // emergency is allowed to travel farther
        )
        _activeSosMessageId.value = packet.messageId

        coroutineScope.launch {
            val t = transport
            if (isLoopbackOnly || t == null || !t.isConnected()) {
                // No peer: keep the emergency queued for later transmission (store &
                // forward) if a mesh is present, otherwise surface QUEUED/NO PEER.
                val mesh = meshRoutingManager
                if (mesh == null || t == null) {
                    _sosState.value = SosState.QUEUED_NO_PEER
                    Log.w(TAG, "SOS queued: no peer / no transport available (${packet.messageId})")
                    return@launch
                }
                _sosState.value = SosState.SENDING
                mesh.sendReliablePacket(packet) { ack ->
                    runOnMain { _sosState.value = if (ack) SosState.DELIVERED else SosState.FAILED }
                }
                return@launch
            }

            _sosState.value = SosState.SENDING
            meshRoutingManager?.sendReliablePacket(packet) { ack ->
                Log.i(TAG, "SOS ${packet.messageId} delivery: ACK=$ack")
                runOnMain {
                    _sosState.value = if (ack) SosState.DELIVERED else SosState.RETRYING
                }
            }
        }
        return packet.messageId
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) { block() }
    }

    /** Track SOS status from delivery/relay events (called by mesh on ACK paths). */
    fun markSosDelivered(messageId: String) {
        if (messageId == _activeSosMessageId.value) {
            runOnMain { _sosState.value = SosState.DELIVERED }
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
        tSend: Long = 0L,
        tTransStart: Long = 0L,
        tTransEnd: Long = 0L,
        translationLatency: Long = 0L
    ) {
        coroutineScope.launch {
            val tReceive = BenchmarkLogger.nowMs()
            _transceiverState.value = TransceiverState.RECEIVING

            // Emergency recognition (dedicated path — no mic, no STT required).
            val isEmergency = packet.type == PacketType.EMERGENCY || packet.isAlert
            _lastReceivedText.value = if (isEmergency)
                "🚨 [${packet.senderId}] " + packet.text
            else
                "[${packet.senderId}] " + packet.text
            deliveryTracker.update(packet.messageId, com.itantra.transport.DeliveryStatus.PLAYING, packet.hopCount)

            // Switch TTS model to packet language if needed (no synthesize() probe —
            // explicit state inspection instead).
            if (!ttsEngine.isLoadedFor(packet.language)) {
                ttsEngine.initialize(packet.language)
            }

            _transceiverState.value = TransceiverState.SYNTHESIZING
            val tTtsStart = BenchmarkLogger.nowMs()
            val ttsResult = speechModelManager.synthesize(text = packet.text, langCode = packet.language, isAlert = packet.isAlert)
            val tTtsEnd = BenchmarkLogger.nowMs()

            _transceiverState.value = TransceiverState.PLAYING
            var tPlayStart = BenchmarkLogger.nowMs()

            if (ttsResult.pcmAudio.isEmpty()) {
                Log.w(TAG, "TTS produced empty audio for '${packet.language}' — speech playback cannot start. " +
                        "No genuine TTS model available for this language.")
                tPlayStart = BenchmarkLogger.nowMs()
            } else {
                audioFocusManager.requestFocus(packet.isAlert)
                try {
                    audioPlayer.playPcm(
                        pcmData = ttsResult.pcmAudio,
                        sampleRate = ttsResult.sampleRate,
                        isAlert = packet.isAlert,
                        onPlaybackStarted = {
                            tPlayStart = BenchmarkLogger.nowMs()
                        }
                    )
                } finally {
                    audioFocusManager.abandonFocus()
                }
            }
            _transceiverState.value = TransceiverState.IDLE

            // Telemetry & Benchmark logging — NO fabricated timestamps.
            // Values only appear when they were actually measured. End-to-end across
            // two phones uses each device's local clock (packet.timestamp is sender
            // wall-clock), which the full pipeline reconstructs; local segments
            // (STT, TTS, playback) use the monotonic clock.
            val record = BenchmarkLogger.logInteraction(
                messageId = packet.messageId,
                language = packet.language,
                isAlert = packet.isAlert,
                tSpeechStart = tSpeechStart,
                tSpeechEnd = tSpeechEnd,
                tSttStart = tSttStart,
                tSttEnd = tSttEnd,
                tSend = tSend,
                tReceive = tReceive,
                tTtsStart = tTtsStart,
                tTtsEnd = tTtsEnd,
                tPlayStart = tPlayStart,
                translationLatencyMs = translationLatency
            )
            // Only surface a latency record to the UI if it contains at least one
            // real measurement (never show a fabricated 0ms E2E).
            if (record.hasAnyMeasurement()) {
                _lastLatencyMetrics.value = record
            } else {
                _lastLatencyMetrics.value = null
            }
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

    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Clean shutdown: cancels the orchestration coroutine scope, queue worker,
     * discovery advertising, topology poller, mesh routing and transport listeners.
     * Called from activity onDestroy / app shutdown. Does NOT tear down the model
     * engines (they are lazy + shared) — only stops active background work.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        Log.i(TAG, "PipelineOrchestrator.release(): cancelling background jobs")
        try { coroutineScope.cancel() } catch (_: Exception) {}
        try { meshRoutingManager?.release() } catch (_: Exception) {}
        try { transport?.disconnect() } catch (_: Exception) {}
        // Release the shared STT/TTS native engines so no native handle outlives the
        // process usage. (Idempotent — each engine release() is guarded.)
        try { sttEngine.release() } catch (_: Exception) {}
        try { ttsEngine.release() } catch (_: Exception) {}
        try { vadEngine.release() } catch (_: Exception) {}
    }
}
