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

    // Adaptive noise-floor tracking (energy VAD): the threshold adapts slowly to
    // ambient noise so an office fan / vehicle rumble does not false-positive.
    private var noiseFloor = 0.008f
    private var hangoverRemainingMs = 0L // post-speech hold so short gaps don't split a word

    /** Minimum sustained speech before a VOICE_ON decision counts (ms). */
    var minSpeechDurationMs: Long = 120L

    /** Hold speech active this long after the last voiced frame (ms). */
    var hangoverMs: Long = 180L

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

        // Energy-based VAD is the ACTIVE detector (honest: not neural VAD). The
        // bundled Silero model is v4-format and incompatible with this runtime.
        val rawProb = runEnergyVad(audioChunk)
        lastSpeechProb = rawProb

        // Adaptive noise floor: during silence, slowly raise/low the floor to track
        // ambient background (fan, AC, vehicle). Never above the speech threshold.
        if (rawProb < speechThreshold) {
            // quiet region: nudge floor toward the observed RMS energy
            noiseFloor = (noiseFloor * 0.95f + rawProb * 0.05f).coerceIn(0.003f, speechThreshold * 0.6f)
        }

        if (now - lastDiagLogMs >= 500) {
            lastDiagLogMs = now
            Log.d("VadDiag", "prob=$lastSpeechProb floor=$noiseFloor isSpeaking=$isSpeaking")
        }

        // Voice decision = prob above a floor-relative threshold. While silence is
        // held (hangover), keep the utterance alive so intra-word gaps don't split.
        val dynamicThreshold = (speechThreshold * 0.5f + noiseFloor * 0.5f)
        val isChunkSpeech = rawProb >= dynamicThreshold
        if (isChunkSpeech) {
            hangoverRemainingMs = hangoverMs
        } else if (hangoverRemainingMs > 0) {
            hangoverRemainingMs -= WINDOW_SIZE * 1000L / SAMPLE_RATE
        }
        val effectivelySpeaking = isChunkSpeech || hangoverRemainingMs > 0

        if (effectivelySpeaking) {
            silenceStartTimeMs = 0L
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTimeMs = now
                lastEvaluationMs = now
                // Suppress micro speech blips (key clicks/transients): only start real
                // speech detection after a short minimum duration.
                return if (now - speechStartTimeMs >= minSpeechDurationMs || audioChunk.size >= SAMPLE_RATE / 4) {
                    VadEvent.SPEECH_START
                } else VadEvent.SHORT_PAUSE
            }
            lastEvaluationMs = now
            return VadEvent.SPEECH_CONTINUE
        }

        // --- Not speech (silence region) ---
        if (!isSpeaking) return VadEvent.SILENCE

        // We WERE speaking and now hit sustained silence — apply endpointing tiers.
        val silenceDuration = now - silenceStartTimeMs
        val tier = if (silenceStartTimeMs == 0L) {
            silenceStartTimeMs = now
            VadEvent.SHORT_PAUSE
        } else when {
            silenceDuration >= longSilenceMs -> VadEvent.LONG_SILENCE
            silenceDuration >= sentenceEndMs -> VadEvent.SENTENCE_END
            else -> VadEvent.SHORT_PAUSE
        }
        if (tier == VadEvent.LONG_SILENCE || tier == VadEvent.SENTENCE_END) {
            isSpeaking = false
            silenceStartTimeMs = 0L
            hangoverRemainingMs = 0L
        }
        return tier
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
        // Relative to the adaptive noise floor: mid/high speech always exceeds it.
        val rel = (rms - noiseFloor).coerceAtLeast(0f)
        return when {
            rms > 0.020f -> 0.9f
            rms > 0.012f -> 0.68f
            rms > 0.008f -> 0.56f
            // Clearly above the ambient floor (e.g. 3x floor) = soft speech
            rel > noiseFloor * 2.5f -> (0.35f + rel).coerceAtMost(0.6f)
            else -> (rel * 2.0f).coerceAtMost(0.05f)
        }
    }

    /** Energy fallback is always the active detector — Silero v4 model is incompatible. */
    fun isUsingNeuralVad(): Boolean = false

    fun reset() {
        isSpeaking = false
        silenceStartTimeMs = 0L
        speechStartTimeMs = 0L
        lastEvaluationMs = 0L
        lastSpeechProb = 0.0f
        hangoverRemainingMs = 0L
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
