package com.itantra.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

enum class VadEvent {
    SILENCE,
    SPEECH_START,
    SPEECH_CONTINUE,
    PAUSE_DETECTED,
    SENTENCE_END
}

/**
 * High-precision Voice Activity Detector combining Silero VAD (ONNX)
 * with an adaptive energy/zero-crossing rate fallback engine.
 */
class VadEngine(
    private val context: Context,
    var speechThreshold: Float = 0.5f,
    var silenceDurationMs: Long = 800L
) {
    companion object {
        private const val TAG = "VadEngine"
        private const val MODEL_ASSET_PATH = "models/vad/silero_vad.onnx"
        private const val SAMPLE_RATE = 16000
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isSileroLoaded = false

    // State tensors for Silero VAD v4 (2, 1, 64)
    private var hState = FloatArray(2 * 1 * 64)
    private var cState = FloatArray(2 * 1 * 64)

    private var isSpeaking = false
    private var speechStartTimeMs: Long = 0
    private var silenceStartTimeMs: Long = 0

    init {
        initializeSilero()
    }

    private fun initializeSilero() {
        try {
            val modelFile = copyAssetToFile(context, MODEL_ASSET_PATH, "silero_vad.onnx")
            if (modelFile != null && modelFile.exists()) {
                ortEnvironment = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                }
                ortSession = ortEnvironment?.createSession(modelFile.absolutePath, sessionOptions)
                isSileroLoaded = true
                Log.i(TAG, "Silero VAD ONNX engine initialized successfully")
            } else {
                Log.w(TAG, "Silero VAD model file not found in assets, using adaptive energy VAD")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize Silero VAD ONNX session, falling back to energy VAD", e)
            isSileroLoaded = false
        }
    }

    @Synchronized
    fun processChunk(audioChunk: FloatArray): VadEvent {
        val now = System.currentTimeMillis()
        val speechProb = if (isSileroLoaded && ortSession != null) {
            runSileroInference(audioChunk)
        } else {
            runEnergyVad(audioChunk)
        }

        val isChunkSpeech = speechProb >= speechThreshold

        if (isChunkSpeech) {
            silenceStartTimeMs = 0L
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTimeMs = now
                return VadEvent.SPEECH_START
            }
            return VadEvent.SPEECH_CONTINUE
        } else {
            if (isSpeaking) {
                if (silenceStartTimeMs == 0L) {
                    silenceStartTimeMs = now
                    return VadEvent.PAUSE_DETECTED
                } else if (now - silenceStartTimeMs >= silenceDurationMs) {
                    // Sustained silence indicates utterance boundary
                    isSpeaking = false
                    silenceStartTimeMs = 0L
                    return VadEvent.SENTENCE_END
                }
                return VadEvent.PAUSE_DETECTED
            }
            return VadEvent.SILENCE
        }
    }

    private fun runSileroInference(audioChunk: FloatArray): Float {
        return try {
            val env = ortEnvironment ?: return runEnergyVad(audioChunk)
            val session = ortSession ?: return runEnergyVad(audioChunk)

            // Input audio tensor [1, chunk_len]
            val audioBuffer = FloatBuffer.wrap(audioChunk)
            val audioTensor = OnnxTensor.createTensor(env, audioBuffer, longArrayOf(1, audioChunk.size.toLong()))

            // SR tensor [1]
            val srTensor = OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong()))

            // H and C state tensors [2, 1, 64]
            val hBuffer = FloatBuffer.wrap(hState)
            val hTensor = OnnxTensor.createTensor(env, hBuffer, longArrayOf(2, 1, 64))

            val cBuffer = FloatBuffer.wrap(cState)
            val cTensor = OnnxTensor.createTensor(env, cBuffer, longArrayOf(2, 1, 64))

            val inputs = mapOf(
                "input" to audioTensor,
                "sr" to srTensor,
                "h" to hTensor,
                "c" to cTensor
            )

            val results = session.run(inputs)
            val outputTensor = results[0].value as Array<FloatArray>
            val prob = outputTensor[0][0]

            // Update hidden states if returned by model
            if (results.size() >= 3) {
                val newHTensor = results[1].value as Array<Array<FloatArray>>
                val newCTensor = results[2].value as Array<Array<FloatArray>>
                flatten3D(newHTensor, hState)
                flatten3D(newCTensor, cState)
            }

            results.close()
            audioTensor.close()
            srTensor.close()
            hTensor.close()
            cTensor.close()

            prob
        } catch (e: Exception) {
            runEnergyVad(audioChunk)
        }
    }

    private fun flatten3D(src: Array<Array<FloatArray>>, dst: FloatArray) {
        var idx = 0
        for (i in src.indices) {
            for (j in src[i].indices) {
                for (k in src[i][j].indices) {
                    if (idx < dst.size) {
                        dst[idx++] = src[i][j][k]
                    }
                }
            }
        }
    }

    /**
     * Highly responsive RMS energy + zero-crossing rate estimator.
     */
    private fun runEnergyVad(audioChunk: FloatArray): Float {
        if (audioChunk.isEmpty()) return 0.0f
        var sumSquares = 0.0
        var zeroCrossings = 0

        for (i in audioChunk.indices) {
            val v = audioChunk[i]
            sumSquares += v * v
            if (i > 0 && ((audioChunk[i] >= 0 && audioChunk[i - 1] < 0) || (audioChunk[i] < 0 && audioChunk[i - 1] >= 0))) {
                zeroCrossings++
            }
        }

        val rms = Math.sqrt(sumSquares / audioChunk.size).toFloat()
        val zcr = zeroCrossings.toFloat() / audioChunk.size

        // Threshold tuning for 16-bit normalized speech
        return when {
            rms > 0.025f && zcr < 0.45f -> 0.85f
            rms > 0.015f -> 0.60f
            rms > 0.008f -> 0.40f
            else -> 0.05f
        }
    }

    fun reset() {
        isSpeaking = false
        silenceStartTimeMs = 0L
        speechStartTimeMs = 0L
        hState = FloatArray(2 * 1 * 64)
        cState = FloatArray(2 * 1 * 64)
    }

    fun release() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            ortSession = null
            ortEnvironment = null
            isSileroLoaded = false
        }
    }

    private fun copyAssetToFile(context: Context, assetPath: String, targetFileName: String): File? {
        return try {
            val targetFile = File(context.filesDir, targetFileName)
            if (targetFile.exists() && targetFile.length() > 0) return targetFile

            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile
        } catch (e: Exception) {
            null
        }
    }
}
