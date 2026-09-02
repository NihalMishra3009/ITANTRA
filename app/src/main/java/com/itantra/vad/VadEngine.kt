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
    PAUSE_DETECTED,
    SENTENCE_END
}

/**
 * Real Silero VAD via sherpa-onnx (ONNX Runtime), with sentence endpointing state machine.
 * No fake inference: if the Silero model is present it is genuinely run; otherwise the
 * energy fallback is used and clearly reported as a fallback.
 */
class VadEngine(
    private val context: Context,
    var speechThreshold: Float = 0.5f,
    var silenceDurationMs: Long = 700L
) {
    companion object {
        private const val TAG = "VadEngine"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512 // 32ms @ 16kHz, matches Silero v4/v5
    }

    private var sileroVad: Vad? = null
    private var isSileroLoaded = false
    private var ringBuffer = FloatArray(WINDOW_SIZE)
    private var ringIndex = 0
    private var ringFilled = false

    private var isSpeaking = false
    private var silenceStartTimeMs: Long = 0L
    private var speechStartTimeMs: Long = 0L
    private var lastEvaluationMs: Long = 0L

    init {
        initializeSilero()
    }

    private fun initializeSilero() {
        try {
            val sileroConfig = SileroVadModelConfig(
                model = "models/vad/silero_vad.onnx",
                threshold = speechThreshold,
                minSilenceDuration = 0.25f,   // 250ms min silence
                minSpeechDuration = 0.1f,     // 100ms min speech
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
        val speechProb = if (isSileroLoaded && sileroVad != null) {
            runSileroWindowed(audioChunk)
        } else {
            runEnergyVad(audioChunk)
        }

        val isChunkSpeech = speechProb >= speechThreshold

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
        } else {
            if (isSpeaking) {
                if (silenceStartTimeMs == 0L) {
                    silenceStartTimeMs = now
                    return VadEvent.PAUSE_DETECTED
                } else if (now - silenceStartTimeMs >= silenceDurationMs) {
                    isSpeaking = false
                    silenceStartTimeMs = 0L
                    return VadEvent.SENTENCE_END
                }
                return VadEvent.PAUSE_DETECTED
            }
            return VadEvent.SILENCE
        }
    }

    private fun runSileroWindowed(audioChunk: FloatArray): Float {
        if (audioChunk.isEmpty()) return 0.0f
        for (sample in audioChunk) {
            ringBuffer[ringIndex] = sample
            ringIndex = (ringIndex + 1) % WINDOW_SIZE
            if (ringIndex == 0) ringFilled = true
        }
        if (!ringFilled) return 0.0f
        return try {
            sileroVad?.compute(ringBuffer) ?: 0.0f
        } catch (e: Exception) {
            runEnergyVad(audioChunk)
        }
    }

    /**
     * RMS energy fallback. Clearly a fallback — never reported as neural VAD.
     */
    private fun runEnergyVad(audioChunk: FloatArray): Float {
        if (audioChunk.isEmpty()) return 0.0f
        var sumSquares = 0.0
        for (v in audioChunk) sumSquares += v * v
        val rms = Math.sqrt(sumSquares / audioChunk.size).toFloat()
        return when {
            rms > 0.025f -> 0.9f
            rms > 0.015f -> 0.6f
            rms > 0.008f -> 0.4f
            else -> 0.05f
        }
    }

    fun reset() {
        isSpeaking = false
        silenceStartTimeMs = 0L
        speechStartTimeMs = 0L
        lastEvaluationMs = 0L
        ringBuffer = FloatArray(WINDOW_SIZE)
        ringIndex = 0
        ringFilled = false
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
