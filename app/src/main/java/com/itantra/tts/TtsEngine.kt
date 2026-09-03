package com.itantra.tts

import android.content.Context
import android.util.Log
import com.itantra.ai4bharat.Ai4BharatModelManager
import com.itantra.ai4bharat.Ai4BharatTtsAdapter
import com.itantra.ai4bharat.ModelType
import com.itantra.stt.SupportedLanguage
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig

/**
 * Genuine offline neural TTS via sherpa-onnx (ONNX Runtime) + VITS models.
 *
 * Model layout in assets: `models/tts/vits_<lang>/model.onnx` + `tokens.txt`.
 * Real VITS models are bundled per language. If a genuine model is not present
 * for a language, the engine is NOT marked loaded and synthesize() returns empty
 * audio — it never emits fake/synthetic speech.
 */
class TtsEngine(
    private val context: Context,
    private val modelManager: Ai4BharatModelManager = Ai4BharatModelManager()
) : Ai4BharatTtsAdapter {

    companion object {
        private const val TAG = "TtsEngine"
        private const val SAMPLE_RATE = 24000
    }

    private var currentLanguage: SupportedLanguage = SupportedLanguage.HINDI
    private var tts: OfflineTts? = null
    private var isInitialized = false
    private var hasRealModel = false

    override fun initialize(languageCode: String): Boolean {
        val lang = SupportedLanguage.fromCode(languageCode)
        if (isInitialized && tts != null && currentLanguage == lang) {
            return true
        }
        currentLanguage = lang
        release()
        hasRealModel = false

        val modelAssetDir = "models/tts/vits_${lang.code}"
        val modelPath = "$modelAssetDir/model.onnx"
        val tokensPath = "$modelAssetDir/tokens.txt"

        // Critical: check the model assets exist BEFORE constructing OfflineTts.
        // sherpa-onnx aborts (native SIGABRT, uncatchable) if a model file is missing.
        if (!assetExists(modelPath) || !assetExists(tokensPath)) {
            Log.w(TAG, "No genuine TTS model for ${lang.displayName} (missing $modelPath) — TTS unavailable")
            isInitialized = true
            return true
        }

        return try {
            val model = OfflineTtsVitsModelConfig(
                model = modelPath,
                lexicon = "",
                tokens = tokensPath,
                dataDir = "",
                dictDir = "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = model,
                matcha = OfflineTtsMatchaModelConfig(),
                kokoro = OfflineTtsKokoroModelConfig(),
                zipvoice = OfflineTtsZipVoiceModelConfig(),
                kitten = OfflineTtsKittenModelConfig(),
                pocket = OfflineTtsPocketModelConfig(),
                supertonic = OfflineTtsSupertonicModelConfig(),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val config = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 1.0f
            )
            tts = OfflineTts(assetManager = context.assets, config = config)
            hasRealModel = true
            modelManager.markLoaded(ModelType.TTS, lang.code, estimateModelSize(lang.code))
            Log.i(TAG, "VITS TTS loaded for ${lang.displayName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "No genuine TTS model for ${lang.displayName} — TTS unavailable", e)
            hasRealModel = false
            tts = null
            true
        } finally {
            isInitialized = true
        }
    }

    private fun assetExists(assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use { it.available() >= 0 } // true if open succeeds
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    override fun synthesize(text: String, languageCode: String, isAlert: Boolean): TtsResult {
        val startTime = System.currentTimeMillis()
        val targetLang = if (languageCode.isNotBlank()) SupportedLanguage.fromCode(languageCode) else currentLanguage

        if (!isInitialized || currentLanguage != targetLang) {
            initialize(targetLang.code)
        }
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return TtsResult(ShortArray(0), SAMPLE_RATE, 0, targetLang.code)
        }
        val engine = tts
        if (!hasRealModel || engine == null) {
            Log.w(TAG, "TTS model not available for ${targetLang.code} — cannot synthesize speech")
            return TtsResult(ShortArray(0), SAMPLE_RATE, System.currentTimeMillis() - startTime, targetLang.code)
        }

        return try {
            val generated = engine.generate(cleanText, sid = 0, speed = 1.0f)
            val samples = generated.samples
            val sr = generated.sampleRate
            val pcm = ShortArray(samples.size) { (samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "TTS [${targetLang.code}] ${duration}ms (${pcm.size} samples @ ${sr}Hz)")
            TtsResult(pcm, sr, duration, targetLang.code)
        } catch (e: Exception) {
            Log.e(TAG, "TTS synthesis failed for ${targetLang.code}", e)
            TtsResult(ShortArray(0), SAMPLE_RATE, System.currentTimeMillis() - startTime, targetLang.code)
        }
    }

    override fun isModelLoaded(): Boolean = hasRealModel

    override fun release() {
        try {
            tts?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            tts = null
            hasRealModel = false
            isInitialized = false
        }
    }

    private fun estimateModelSize(langCode: String): Long {
        return try {
            context.assets.open("models/tts/vits_$langCode/model.onnx").use { it.available().toLong() }
        } catch (e: Exception) {
            0L
        }
    }
}
