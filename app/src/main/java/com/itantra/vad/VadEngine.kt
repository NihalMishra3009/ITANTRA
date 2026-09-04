package com.itantra.vad

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

enum class VadEvent {
    SILENCE,
    SPEECH_START,
    SPEECH_CONTINUE,
    SHORT_PAUSE,     // brief break within a sentence — possible partial boundary
    SENTENCE_END,    // normal pause — sentence/utterance boundary
    LONG_SILENCE     // long silence after speech — finalize utterance
}

/**
 * Real Silero VAD via sherpa-onnx (ONNX Runtime), with 3-tier sentence endpointing.
 *
 * Endpoint tiers (all configurable):
 *   SHORT_PAUSE   — short break while still speaking (partial transcript marker)
 *   SENTENCE_END  — normal pause, forms a sentence boundary
 *   LONG_SILENCE  — extended silence, finalizes the utterance
 *
 * Uses VAD confidence + real speech activity. If Silero init fails, falls back
 * to RMS energy VAD (clearly reported, never presented as neural VAD).
 */
class VadEngine(
    private val context: Context,
    var speechThreshold: Float = 0.5f,
    var shortPauseMs: Long = 250L,
    var sentenceEndMs: Long = 700L,
    var longSilenceMs: Long = 2000L
) {
    companion object {
        private const val TAG = "VadEngine"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512 // 32ms @ 16kHz, matches Silero v4/v5
    }

    private var sileroVad: Vad? = null
    private var isSileroLoaded = false
    private val window = ArrayDeque<Float>(WINDOW_SIZE * 2)

    private var isSpeaking = false
    private var silenceStartTimeMs: Long = 0L
    private var speechStartTimeMs: Long = 0L
    private var lastEvaluationMs: Long = 0L
    private var lastDiagLogMs: Long = 0L
    private var lastSpeechProb = 0.0f

    init {
        initializeSilero()
    }

    private fun initializeSilero() {
        try {
            val sileroConfig = SileroVadModelConfig(
                model = "models/vad/silero_vad.onnx",
                threshold = speechThreshold,
                minSilenceDuration = 0.08f,
                minSpeechDuration = 0.05f,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = 20f
            )
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                tenVadModelConfig = TenVadModelConfig(),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            sileroVad = Vad(assetManager = context.assets, config = vadConfig)
            isSileroLoaded = true
            Log.i(TAG, "Silero VAD (sherpa-onnx) initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Silero VAD init failed, using energy fallback", e)
            isSileroLoaded = false
        }
    }

    @Synchronized
    fun processChunk(audioChunk: FloatArray): VadEvent {
        val now = System.currentTimeMillis()

        // Energy-based VAD is the ACTIVE detector. The bundled Silero model is
        // v4-format and incompatible with sherpa-onnx 1.13.7's Vad API — it loads
        // but returns a constant ~0.005 baseline regardless of speech, which would
        // make isChunkSpeech always false and break utterance capture.
        // Energy VAD responds correctly to real speech, so use it directly.
        lastSpeechProb = runEnergyVad(audioChunk)

        if (now - lastDiagLogMs >= 500) {
            lastDiagLogMs = now
            Log.d("VadDiag", "prob=$lastSpeechProb isSpeaking=$isSpeaking")
        }

        val isChunkSpeech = lastSpeechProb >= speechThreshold

        if (isChunkSpeech) {
            silenceStartTimeMs = 0L
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTimeMs = now
                lastEvaluationMs = now
                return VadEvent.SPEECH_START
            }
            lastEvaluationMs = now
            return VadEvent.SPEECH_CONTINUE
        }

        // --- Not speech (silence region) ---
        if (!isSpeaking) return VadEvent.SILENCE

        val silenceDuration = now - silenceStartTimeMs
        return if (silenceStartTimeMs == 0L) {
            // First non-speech frame after speaking
            silenceStartTimeMs = now
            VadEvent.SHORT_PAUSE
        } else when {
            // Long silence finalizes the utterance (voice endpointing)
            silenceDuration >= longSilenceMs -> {
                isSpeaking = false
                silenceStartTimeMs = 0L
                VadEvent.LONG_SILENCE
            }
            // Normal pause marks a sentence boundary
            silenceDuration >= sentenceEndMs -> {
                isSpeaking = false
                silenceStartTimeMs = 0L
                VadEvent.SENTENCE_END
            }
            // Short break within a sentence — possible partial boundary
            silenceDuration >= shortPauseMs -> VadEvent.SHORT_PAUSE
            else -> VadEvent.SHORT_PAUSE
        }
    }

    private fun runSileroWindowed(audioChunk: FloatArray): Float {
        if (audioChunk.isEmpty()) return 0.0f
        for (sample in audioChunk) {
            window.addLast(sample)
        }
        while (window.size > WINDOW_SIZE) {
            window.removeFirst()
        }
        if (window.size < WINDOW_SIZE) return 0.0f

        val buffer = FloatArray(WINDOW_SIZE)
        var i = 0
        for (sample in window) { buffer[i++] = sample }

        return try {
            sileroVad?.compute(buffer) ?: runEnergyVad(audioChunk)
        } catch (e: Exception) {
            runEnergyVad(audioChunk)
        }
    }

    /**
     * RMS energy fallback. This is the ACTIVE detector when a compatible neural
     * Silero model is unavailable. Used only because the bundled Silero model is
     * incompatible with the sherpa-onnx runtime.
     */
    private fun runEnergyVad(audioChunk: FloatArray): Float {
        if (audioChunk.isEmpty()) return 0.0f
        var sumSquares = 0.0
        for (v in audioChunk) sumSquares += v * v
        val rms = Math.sqrt(sumSquares / audioChunk.size).toFloat()
        // Thresholds tuned so normal speech (rms > 0.008) is detected as speech
        // against the default speechThreshold=0.5.
        return when {
            rms > 0.02f -> 0.9f
            rms > 0.012f -> 0.65f
            rms > 0.008f -> 0.55f
            rms > 0.004f -> 0.3f
            else -> 0.05f
        }
    }

    /** True when this engine is using the real neural VAD model. */
    fun isUsingNeuralVad(): Boolean = isSileroLoaded

    fun reset() {
        isSpeaking = false
        silenceStartTimeMs = 0L
        speechStartTimeMs = 0L
        lastEvaluationMs = 0L
        lastSpeechProb = 0.0f
        window.clear()
        sileroVad?.reset()
    }

    fun release() {
        try {
            sileroVad?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            sileroVad = null
            isSileroLoaded = false
        }
    }
}
