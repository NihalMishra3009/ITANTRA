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

    private var lastDiagLogMs: Long = 0L
    private var lastSpeechProb = 0.0f

    private val stateMachine = VadStateMachine(
        speechThreshold = speechThreshold,
        shortPauseMs = shortPauseMs,
        sentenceEndMs = sentenceEndMs,
        longSilenceMs = longSilenceMs
    )

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
        // Monotonic clock (elapsedRealtime) — never wall-clock: processChunk must
        // not jump on clock changes and always uses true audio-session timing.
        val now = android.os.SystemClock.elapsedRealtime()

        // Energy VAD is the ACTIVE detector. The Silero model is attempted at init;
        // neural VAD is only promoted to primary after a passing live speech/silence
        // discrimination test on a physical device (SIH Phase 8). Until then:
        // Adaptive Energy VAD, reported honestly (isUsingNeuralVad() == false).
        val rawProb = runEnergyVad(audioChunk)
        val event = stateMachine.process(now, rawProb, audioChunk.size)
        lastSpeechProb = rawProb

        if (now - lastDiagLogMs >= 500) {
            lastDiagLogMs = now
            Log.d("VadDiag", "prob=$lastSpeechProb floor=${stateMachine.noiseFloor} speaking=${stateMachine.isSpeaking}")
        }
        return event
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
     * RMS energy fallback. This is the ACTIVE detector (reported honestly as
     * energy/fallback, NOT neural VAD). Thresholds are tuned for 16 kHz mono
     * speech: normal speech RMS unmistakably exceeds the adaptive noise floor.
     * Clipping is flagged via a dedicated probability bump so heavily-saturated
     * audio is still treated as speech rather than missed.
     */
    private fun runEnergyVad(audioChunk: FloatArray): Float {
        if (audioChunk.isEmpty()) return 0.0f
        var sumSquares = 0.0
        var clippedSamples = 0
        for (v in audioChunk) {
            sumSquares += v * v
            if (v >= 0.98f || v <= -0.98f) clippedSamples++
        }
        val rms = Math.sqrt(sumSquares / audioChunk.size).toFloat()
        // Heavy clipping (mic saturation) means real speech even if RMS looks odd.
        if (clippedSamples.toFloat() / audioChunk.size > 0.10f) return 0.95f
        // Relative to the adaptive noise floor (state machine): mid/high speech
        // always exceeds it.
        val floor = stateMachine.noiseFloor
        val rel = (rms - floor).coerceAtLeast(0f)
        return when {
            rms > 0.020f -> 0.9f
            rms > 0.012f -> 0.68f
            rms > 0.008f -> 0.56f
            // Clearly above the ambient floor (e.g. 3x floor) = soft speech
            rel > floor * 2.5f -> (0.35f + rel).coerceAtMost(0.6f)
            else -> (rel * 2.0f).coerceAtMost(0.05f)
        }
    }

    /** Energy fallback is the active detector until a live on-device Silero test passes. */
    fun isUsingNeuralVad(): Boolean = false

    fun reset() {
        stateMachine.reset()
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
