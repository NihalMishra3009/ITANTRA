package com.itantra.stt

import android.content.Context
import android.util.Log
import com.itantra.ai4bharat.Ai4BharatModelManager
import com.itantra.ai4bharat.Ai4BharatSttAdapter
import com.itantra.ai4bharat.IndicTextNormalizer
import com.itantra.ai4bharat.ModelType
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Genuine offline multilingual STT via sherpa-onnx (ONNX Runtime) + OpenAI Whisper tiny int8.
 * ONE Whisper model covers all 10 iTantra languages (hi, en, gu, mr, kn, ml, ta, te, or, bn).
 * Runs entirely on-device, no network.
 */
class SttEngine(
    private val context: Context,
    private val modelManager: Ai4BharatModelManager = Ai4BharatModelManager()
) : Ai4BharatSttAdapter {

    companion object {
        private const val TAG = "WhisperSttEngine"
        private const val SAMPLING_RATE = 16000
        // Whisper-base int8: notably more accurate than tiny on Indian languages.
        private const val ENCODER_ASSET = "models/stt/whisper-base-encoder.int8.onnx"
        private const val DECODER_ASSET = "models/stt/whisper-base-decoder.int8.onnx"
        private const val TOKENS_ASSET = "models/stt/whisper-base-tokens.txt"
        private const val MIN_MODEL_SIZE_BYTES = 1024 * 1024
    }

    private var currentLanguage: SupportedLanguage = SupportedLanguage.HINDI
    private var recognizer: OfflineRecognizer? = null
    private var isInitialized = false
    private var hasRealModel = false

    override fun initialize(languageCode: String): Boolean {
        val lang = SupportedLanguage.fromCode(languageCode)
        // ONE Whisper model covers all 10 languages — do not reload the 160MB
        // model just because language changed. Reload only on first init.
        if (isInitialized && recognizer != null) {
            currentLanguage = lang
            return true
        }
        currentLanguage = lang
        release()
        hasRealModel = false

        // Extract the model ONCE (160MB) then reuse — never re-copy per language.
        extractModelsAndBuildRecognizer(lang)
        return true
    }

    @Synchronized
    override fun transcribe(audioChunk: FloatArray, languageCode: String): SttResult {
        val startTime = System.currentTimeMillis()
        val targetLang = if (languageCode.isNotBlank()) SupportedLanguage.fromCode(languageCode) else currentLanguage

        if (!isInitialized || recognizer == null || currentLanguage != targetLang) {
            initialize(targetLang.code)
        }
        val rec = recognizer ?: return SttResult("", targetLang.code, 0)

        if (audioChunk.isEmpty()) {
            return SttResult("", targetLang.code, 0)
        }
        if (!hasRealModel) {
            Log.w(TAG, "STT model not available — returning empty transcript")
            return SttResult("", targetLang.code, System.currentTimeMillis() - startTime)
        }

        return try {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(audioChunk, SAMPLING_RATE)
                rec.decode(stream)
                val result = rec.getResult(stream)
                val rawText = result.text.trim()
                val normalizedText = IndicTextNormalizer.normalize(rawText, targetLang.code)
                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "Whisper [${targetLang.code}] ${duration}ms: \"$normalizedText\"")
                SttResult(normalizedText, targetLang.code, duration, 0.9f)
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Whisper inference failed", e)
            SttResult("", targetLang.code, System.currentTimeMillis() - startTime)
        }
    }

    override fun isModelLoaded(): Boolean = hasRealModel

    /** Force a rebuild from the current best source (downloaded engine if installed). */
    fun reload(languageCode: String) {
        release()
        initialize(languageCode)
    }

    /** Extract model assets once and build the recognizer. Returns true on success. */
    private fun extractModelsAndBuildRecognizer(lang: SupportedLanguage) {
        try {
            // Prefer an installed downloaded STT ENGINE pack (Whisper small); else bundled base.
            val files = downloadedWhisperFiles() ?: bundledWhisperFiles()
            if (files == null) {
                Log.e(TAG, "Whisper models missing or invalid for ${lang.displayName}")
                isInitialized = true
                return
            }
            if (files.first.length() < MIN_MODEL_SIZE_BYTES || files.second.length() < MIN_MODEL_SIZE_BYTES) {
                Log.e(TAG, "Whisper models invalid (too small) for ${lang.displayName}")
                isInitialized = true
                return
            }

            buildRecognizer(lang, files.first.absolutePath, files.second.absolutePath, files.third.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Whisper STT", e)
            hasRealModel = false
            recognizer = null
        } finally {
            isInitialized = true
        }
    }

    /** Downloaded shared STT engine files (encoder, decoder, tokens) or null. */
    private fun downloadedWhisperFiles(): Triple<File, File, File>? {
        val engineDir = File(context.filesDir, "models/stt_engine/stt_engine_whisper_small")
        val tokens = File(engineDir, "tokens.txt")
        if (!tokens.exists()) return null
        val onnx = engineDir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.toList() ?: return null
        val encoder = onnx.filter { it.name.contains("encoder", ignoreCase = true) }
            .minByOrNull { if (it.name.contains("int8", ignoreCase = true)) 0 else 1 } ?: return null
        val decoder = onnx.filter { it.name.contains("decoder", ignoreCase = true) }
            .minByOrNull { if (it.name.contains("int8", ignoreCase = true)) 0 else 1 } ?: return null
        return Triple(encoder, decoder, tokens)
    }

    /** Bundled Whisper base int8 files (copied to filesDir once). */
    private fun bundledWhisperFiles(): Triple<File, File, File>? {
        val encoder = copyAssetToFile(context, ENCODER_ASSET, "whisper_base_encoder.onnx")
        val decoder = copyAssetToFile(context, DECODER_ASSET, "whisper_base_decoder.onnx")
        val tokens = copyAssetToFile(context, TOKENS_ASSET, "whisper_base_tokens.txt")
        if (encoder == null || decoder == null || tokens == null) return null
        return Triple(encoder, decoder, tokens)
    }

    private fun buildRecognizer(lang: SupportedLanguage, encoderPath: String, decoderPath: String, tokensPath: String) {
        try {
            val whisperConfig = OfflineWhisperModelConfig(
                encoder = encoderPath,
                decoder = decoderPath,
                language = lang.code,
                task = "transcribe",
                tailPaddings = -1,
                enableTokenTimestamps = false,
                enableSegmentTimestamps = false
            )

            val featConfig = FeatureConfig(sampleRate = SAMPLING_RATE, featureDim = 80, dither = 0.0f)

            val modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(),
                paraformer = OfflineParaformerModelConfig(),
                whisper = whisperConfig,
                fireRedAsr = OfflineFireRedAsrModelConfig(),
                moonshine = OfflineMoonshineModelConfig(),
                nemo = OfflineNemoEncDecCtcModelConfig(),
                senseVoice = OfflineSenseVoiceModelConfig(),
                dolphin = OfflineDolphinModelConfig(),
                zipformerCtc = OfflineZipformerCtcModelConfig(),
                wenetCtc = OfflineWenetCtcModelConfig(),
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(),
                medasr = OfflineMedAsrCtcModelConfig(),
                funasrNano = OfflineFunAsrNanoModelConfig(),
                qwen3Asr = OfflineQwen3AsrModelConfig(),
                fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(),
                canary = OfflineCanaryModelConfig(),
                cohereTranscribe = OfflineCohereTranscribeModelConfig(),
                teleSpeech = "",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "",
                tokens = tokensPath,
                modelingUnit = "",
                bpeVocab = ""
            )

            val config = OfflineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                hr = HomophoneReplacerConfig(),
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
                hotwordsFile = "",
                hotwordsScore = 1.5f,
                ruleFsts = "",
                ruleFars = "",
                blankPenalty = 0.0f
            )

            recognizer = OfflineRecognizer(assetManager = null, config = config)
            hasRealModel = true
            val engineSuffix = if (File(encoderPath).parentFile.name.startsWith("stt_engine")) " (downloaded engine)" else ""
            modelManager.markLoaded(
                ModelType.STT, lang.code,
                File(encoderPath).length() + File(decoderPath).length()
            )
            Log.i(TAG, "Whisper multilingual STT ready for ${lang.displayName}$engineSuffix (all 10 languages)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Whisper STT", e)
            hasRealModel = false
            recognizer = null
        }
    }

    override fun release() {
        try {
            recognizer?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            recognizer = null
            hasRealModel = false
            isInitialized = false
        }
    }

    private fun copyAssetToFile(context: Context, assetPath: String, targetName: String): File? {
        return try {
            val target = File(context.filesDir, targetName)
            if (target.exists() && target.length() > 0) return target
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetPath", e)
            null
        }
    }
}
