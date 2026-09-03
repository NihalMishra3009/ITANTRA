package com.itantra.ai4bharat

import android.content.Context
import android.util.Log
import com.itantra.stt.SupportedLanguage

data class LanguageCapability(
    val language: SupportedLanguage,
    val sttAvailable: Boolean,
    val ttsAvailable: Boolean,
    val sttModelPath: String,
    val ttsModelPath: String,
    val ttsModelSizeBytes: Long,
    val sttModelSizeBytes: Long,
    val verified: Boolean,
    val notes: String
)

/**
 * Centralized registry that verifies ACTUAL model availability on disk.
 * Does NOT trust README claims — checks real asset files.
 *
 * STT: ONE Whisper base int8 model covers all 10 languages.
 * TTS: Per-language VITS model; only languages with bundled model.onnx + tokens.txt are available.
 * VAD: Silero VAD (energy fallback if incompatible).
 */
object ModelCapabilityRegistry {

    private const val TAG = "ModelCapabilityRegistry"
    private const val STT_ENCODER = "models/stt/whisper-base-encoder.int8.onnx"
    private const val STT_DECODER = "models/stt/whisper-base-decoder.int8.onnx"
    private const val STT_TOKENS = "models/stt/whisper-base-tokens.txt"
    private const val VAD_MODEL = "models/vad/silero_vad.onnx"
    private const val MIN_MODEL_SIZE = 1024L * 1024L // 1MB

    private var capabilities: List<LanguageCapability> = emptyList()
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return

        val sttAvailable = validateAsset(context, STT_ENCODER, MIN_MODEL_SIZE) &&
                validateAsset(context, STT_DECODER, MIN_MODEL_SIZE) &&
                validateAsset(context, STT_TOKENS, 100)

        val vadAvailable = validateAsset(context, VAD_MODEL, 1000)
        val sttSize = estimateAssetSize(context, STT_ENCODER) + estimateAssetSize(context, STT_DECODER)

        Log.i(TAG, "STT model: ${if (sttAvailable) "VALID" else "MISSING"} (${sttSize / 1024 / 1024}MB)")
        Log.i(TAG, "VAD model: ${if (vadAvailable) "PRESENT" else "MISSING"}")

        capabilities = SupportedLanguage.values().map { lang ->
            val ttsDir = "models/tts/vits_${lang.code}"
            val ttsModelPath = "$ttsDir/model.onnx"
            val ttsTokensPath = "$ttsDir/tokens.txt"
            val ttsModelExists = validateAsset(context, ttsModelPath, 1024)
            val ttsTokensExists = validateAsset(context, ttsTokensPath, 10)
            val ttsAvailable = ttsModelExists && ttsTokensExists
            val ttsSize = if (ttsAvailable) estimateAssetSize(context, ttsModelPath) else 0L

            if (ttsAvailable) {
                Log.i(TAG, "TTS ${lang.displayName}: VALID (${ttsSize / 1024 / 1024}MB)")
            } else {
                Log.w(TAG, "TTS ${lang.displayName}: NOT AVAILABLE (missing $ttsModelPath)")
            }

            LanguageCapability(
                language = lang,
                sttAvailable = sttAvailable,
                ttsAvailable = ttsAvailable,
                sttModelPath = STT_ENCODER,
                ttsModelPath = if (ttsAvailable) ttsModelPath else "",
                ttsModelSizeBytes = ttsSize,
                sttModelSizeBytes = if (sttAvailable) sttSize else 0L,
                verified = true,
                notes = when {
                    !sttAvailable -> "STT model files missing"
                    !ttsAvailable -> "TTS model not bundled for this language"
                    else -> "Fully operational"
                }
            )
        }

        initialized = true
        Log.i(TAG, "Registry initialized: ${capabilities.count { it.sttAvailable }}/10 STT, ${capabilities.count { it.ttsAvailable }}/10 TTS")
    }

    fun getCapability(langCode: String): LanguageCapability? {
        return capabilities.firstOrNull { it.language.code == langCode }
    }

    fun getAllCapabilities(): List<LanguageCapability> = capabilities

    fun getSttLanguages(): List<SupportedLanguage> {
        return capabilities.filter { it.sttAvailable }.map { it.language }
    }

    fun getTtsLanguages(): List<SupportedLanguage> {
        return capabilities.filter { it.ttsAvailable }.map { it.language }
    }

    fun getFullCapabilityLanguages(): List<SupportedLanguage> {
        return capabilities.filter { it.sttAvailable && it.ttsAvailable }.map { it.language }
    }

    fun isFullyOperational(langCode: String): Boolean {
        val cap = getCapability(langCode) ?: return false
        return cap.sttAvailable && cap.ttsAvailable
    }

    fun getSummary(): String {
        val sttCount = capabilities.count { it.sttAvailable }
        val ttsCount = capabilities.count { it.ttsAvailable }
        val fullCount = capabilities.count { it.sttAvailable && it.ttsAvailable }
        return "Models: $sttCount/10 STT, $ttsCount/10 TTS, $fullCount/10 full capability"
    }

    fun getDiagnosticsReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== iTantra Model Diagnostics ===")
        sb.appendLine()
        for (cap in capabilities) {
            val stt = if (cap.sttAvailable) "✓" else "✗"
            val tts = if (cap.ttsAvailable) "✓" else "✗"
            val ttsSize = if (cap.ttsAvailable) " (${cap.ttsModelSizeBytes / 1024 / 1024}MB)" else ""
            sb.appendLine("${cap.language.displayName.padEnd(14)} STT $stt  TTS $tts$ttsSize  ${cap.notes}")
        }
        sb.appendLine()
        sb.appendLine("STT: Whisper base int8 (130MB encoder + 29MB decoder, shared across all languages)")
        sb.appendLine("TTS: Per-language VITS models (only bundled languages available)")
        sb.appendLine("VAD: Energy fallback active (Silero v4/v5 compatibility pending)")
        return sb.toString()
    }

    private fun validateAsset(context: Context, assetPath: String, minSize: Long): Boolean {
        return try {
            context.assets.open(assetPath).use { stream ->
                stream.available() >= minSize
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun estimateAssetSize(context: Context, assetPath: String): Long {
        return try {
            context.assets.open(assetPath).use { it.available().toLong() }
        } catch (e: Exception) {
            0L
        }
    }
}
