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
        private const val ENCODER_ASSET = "models/stt/whisper-tiny-encoder.int8.onnx"
        private const val DECODER_ASSET = "models/stt/whisper-tiny-decoder.int8.onnx"
        private const val TOKENS_ASSET = "models/stt/whisper-tiny-tokens.txt"
        private const val MIN_MODEL_SIZE_BYTES = 1024 * 1024
    }

    private var currentLanguage: SupportedLanguage = SupportedLanguage.HINDI
    private var recognizer: OfflineRecognizer? = null
    private var isInitialized = false
    private var hasRealModel = false

    override fun initialize(languageCode: String): Boolean {
        val lang = SupportedLanguage.fromCode(languageCode)
        if (isInitialized && recognizer != null && currentLanguage == lang) {
            return true
        }
        currentLanguage = lang
        release()
        hasRealModel = false

        return try {
            val encoder = copyAssetToFile(context, ENCODER_ASSET, "whisper_tiny_encoder.onnx")
            val decoder = copyAssetToFile(context, DECODER_ASSET, "whisper_tiny_decoder.onnx")
            val tokens = copyAssetToFile(context, TOKENS_ASSET, "whisper_tiny_tokens.txt")

            if (encoder == null || decoder == null || tokens == null ||
                encoder.length() < MIN_MODEL_SIZE_BYTES || decoder.length() < MIN_MODEL_SIZE_BYTES
            ) {
                Log.e(TAG, "Whisper models missing or invalid for ${lang.displayName}")
                isInitialized = true
                return true
            }

            val whisperConfig = OfflineWhisperModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                language = lang.code,
                task = "transcribe",
                tailPaddings = -1,
                enableTokenTimestamps = false,
                enableSegmentTimestamps = false
            )

            val featConfig = FeatureConfig(
                sampleRate = SAMPLING_RATE,
                featureDim = 80,
                dither = 0.0f
            )

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
                tokens = tokens.absolutePath,
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

            recognizer = OfflineRecognizer(
                assetManager = null, // absolute filesystem paths -> assetManager must be null
                config = config
            )
            hasRealModel = true
            modelManager.markLoaded(
                ModelType.STT, lang.code,
                encoder.length() + decoder.length()
            )
            Log.i(TAG, "Whisper multilingual STT ready for ${lang.displayName} (all 10 languages)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Whisper STT", e)
            hasRealModel = false
            recognizer = null
            true
        } finally {
            isInitialized = true
        }
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
