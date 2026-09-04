package com.itantra.tts

import android.content.Context
import android.util.Log
import com.itantra.ai4bharat.Ai4BharatModelManager
import com.itantra.ai4bharat.Ai4BharatTtsAdapter
import com.itantra.ai4bharat.ModelType
import com.itantra.stt.SupportedLanguage
import java.io.File
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

        // Prefer a DOWNLOADED voice pack (verified loadable incl. Piper+espeak) so a
        // language re-select during normal use keeps the downloaded voice. Falls back
        // to the bundled asset if no downloaded pack exists.
        if (loadDownloadedVoiceIfPresent(lang)) {
            isInitialized = true
            return true
        }

        val modelAssetDir = "models/tts/vits_${lang.code}"
        val modelPath = "$modelAssetDir/model.onnx"
        val tokensPath = "$modelAssetDir/tokens.txt"

        // Load the (known-good) bundled asset VITS model if present.
        if (!assetExists(modelPath) || !assetExists(tokensPath)) {
            Log.w(TAG, "No genuine TTS model for ${lang.displayName} (missing $modelPath) — TTS unavailable")
            isInitialized = true
            return true
        }
        return loadVits(
            fileModel = modelPath,
            fileTokens = tokensPath,
            useFilePaths = false,
            lang = lang
        )
    }

    /** Load a downloaded voice pack if present; false if none exists. */
    private fun loadDownloadedVoiceIfPresent(lang: SupportedLanguage): Boolean {
        val dir = File(context.filesDir, "models/tts/${lang.code}")
        if (!File(dir, "model.onnx").exists() || !File(dir, "tokens.txt").exists()) return false
        return loadDownloadedVoice(lang.code)
    }

    /** Crash-guarded load of a downloaded (file-path) voice pack.
     *  Runs the sherpa load OFF the calling thread and, because a native SIGABRT
     *  cannot be caught, the caller should treat a returned false as a signal to
     *  keep using the bundled voice. Do not call during app startup. */
    fun loadDownloadedVoice(langCode: String): Boolean {
        val lang = SupportedLanguage.fromCode(langCode)
        val dir = File(context.filesDir, "models/tts/${lang.code}")
        val modelFile = File(dir, "model.onnx")
        val tokensFile = File(dir, "tokens.txt")
        if (!modelFile.exists() || !tokensFile.exists()) {
            Log.w(TAG, "No downloaded voice for ${lang.displayName}")
            return false
        }
        // Piper voices need the espeak-ng-data directory via dataDir.
        val espeakDir = File(dir, "espeak-ng-data")
        val hasEspeak = File(dir, ".espeak").let { it.exists() && it.readText().trim() == "1" } &&
                espeakDir.exists()
        val useFilePaths = true
        currentLanguage = lang
        release()
        hasRealModel = false
        return try {
            val model = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                lexicon = "",
                // Keep tokens ALWAYS non-empty (sherpa validates it) and ALSO set
                // dataDir for Piper voices (espeak-ng front-end) when present.
                tokens = tokensFile.absolutePath,
                dataDir = if (hasEspeak) espeakDir.absolutePath else "",
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
                debug = true,
                provider = "cpu"
            )
            val config = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 1.0f
            )
            tts = OfflineTts(assetManager = null, config = config)
            hasRealModel = true
            isInitialized = true  // prevent synthesize() from re-initializing to bundled asset
            modelManager.markLoaded(ModelType.TTS, lang.code, modelFile.length())
            Log.i(TAG, "Downloaded voice loaded for ${lang.displayName} " +
                    (if (hasEspeak) "[piper+espeak]" else "[vits]"))
            true
        } catch (t: Throwable) {
            // A rejected native config throws (catchable); a hard native abort cannot
            // be caught here, so keep this load lazy + off startup. Never crash the
            // transceiver pipeline because of a voice that can't load.
            Log.e(TAG, "Downloaded voice load failed for ${lang.displayName}", t)
            hasRealModel = false
            tts = null
            false
        }
    }

    private fun loadVits(fileModel: String, fileTokens: String, useFilePaths: Boolean, lang: SupportedLanguage): Boolean {
        return try {
            val model = OfflineTtsVitsModelConfig(
                model = fileModel,
                lexicon = "",
                tokens = fileTokens,
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
            // Downloaded packs use absolute filesystem paths (assetManager = null);
            // bundled assets use the APK asset path (assetManager = context.assets).
            tts = if (useFilePaths) {
                OfflineTts(assetManager = null, config = config)
            } else {
                OfflineTts(assetManager = context.assets, config = config)
            }
            hasRealModel = true
            modelManager.markLoaded(ModelType.TTS, lang.code, estimateModelSize(lang.code))
            Log.i(TAG, "VITS TTS loaded for ${lang.displayName}" +
                    (if (useFilePaths) " [downloaded pack]" else " [bundled asset]"))
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
