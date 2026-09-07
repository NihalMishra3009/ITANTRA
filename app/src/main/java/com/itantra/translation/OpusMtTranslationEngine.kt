package com.itantra.translation

import android.content.Context
import android.util.Log
import com.itantra.benchmark.TranslationTiming
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline neural Hindi<->English translation via Helsinki-NLP Opus-MT exported to
 * a seq2seq ONNX deployment (encoder_model.onnx + decoder_model.onnx), executed by
 * a small native JNI adapter (libitantra_mt.so) that calls the ONNX Runtime C API
 * ALREADY LINKED by sherpa-onnx — no second libonnxruntime.so is bundled. Fully
 * on-device, no network. Apache-2.0.
 *
 * MODEL PACK CONTRACT:
 *   {filesDir}/models/translation/{src}-{tgt}/
 *     encoder_model.onnx
 *     decoder_model.onnx
 *     config.json              (decoder_start/pad/eos/bos ids, vocab_size)
 *     tokenizer/sp.vocab       (exact vocab: "<id>\t<piece>")
 *     tokenizer/sentencepiece.model (the real Marian SentencePiece model)
 *
 * Sessions are cached per pair in native code (created once, reused). If the model
 * pack (or the native adapter) is absent the engine returns TRANSLATION_UNAVAILABLE
 * — it NEVER fabricates a translation.
 */
class OpusMtTranslationEngine(
    private val context: Context
) : TranslationEngine {

    companion object {
        private const val TAG = "OpusMtTranslation"
        const val ROLE_DIR = "models/translation"
        private const val NATIVE_LIB = "itantra_mt"
        private var nativeLoaded = false
        private var loadAttempted = false
    }

    private val loaded = AtomicBoolean(false)
    private var loadedKey: String? = null

    private fun ensureNativeLoaded(): Boolean {
        if (nativeLoaded) return true
        if (loadAttempted) return false
        loadAttempted = true
        return try {
            System.loadLibrary(NATIVE_LIB)
            nativeLoaded = true
            Log.i(TAG, "Native MT adapter loaded: $NATIVE_LIB")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native MT adapter not available ($NATIVE_LIB): ${e.message}")
            false
        }
    }

    override fun supports(sourceLanguage: String, targetLanguage: String): Boolean =
        TranslationCatalog.supports(sourceLanguage, targetLanguage)

    override fun isLoaded(): Boolean = loaded.get()

    fun ensureLoaded(sourceLanguage: String, targetLanguage: String): Boolean {
        val key = modelKey(sourceLanguage, targetLanguage)
        if (loaded.get() && loadedKey == key) return true
        val dir = File(context.filesDir, "$ROLE_DIR/$key")
        // Native load happens lazily in translate(); this only checks pack files.
        if (!File(dir, "encoder_model.onnx").exists() || !File(dir, "decoder_model.onnx").exists()) {
            Log.i(TAG, "Translation model not installed for $key (encoder/decoder missing)")
            release()
            return false
        }
        if (!ensureNativeLoaded()) {
            release()
            return false
        }
        loadedKey = key
        loaded.set(true)
        return true
    }

    override fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationResult {
        if (text.isBlank()) return TranslationResult("", sourceLanguage, targetLanguage, 0L, success = true)
        if (!supports(sourceLanguage, targetLanguage)) {
            return TranslationResult.unavailable(sourceLanguage, targetLanguage)
        }
        val start = System.nanoTime()
        if (!ensureLoaded(sourceLanguage, targetLanguage)) {
            return TranslationResult.unavailable(sourceLanguage, targetLanguage)
        }
        val modelDir = File(context.filesDir, "$ROLE_DIR/${modelKey(sourceLanguage, targetLanguage)}").absolutePath
        return try {
            val raw = nnTranslate(modelDir, text)
            val (translated, timing) = parseNativeResult(raw)
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            timing?.let { com.itantra.benchmark.BenchmarkLogger.logTranslationTiming(it) }
            if (translated.isBlank() || translated.startsWith("10")) {
                TranslationResult.failed(sourceLanguage, targetLanguage, nativeErrorKey(raw))
            } else {
                TranslationResult(translated, sourceLanguage, targetLanguage, latencyMs, success = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation inference failed", e)
            TranslationResult.failed(sourceLanguage, targetLanguage, "Offline translation model failed to load.")
        }
    }

    /** Parse "text\n__mt<stage>=<us>…" from native into clean text + timings. */
    internal fun parseNativeResult(raw: String): Pair<String, com.itantra.benchmark.TranslationTiming?> {
        var text = raw
        var tok = 0L; var enc = 0L; var dec = 0L; var total = 0L
        val lines = raw.split('\n')
        val clean = StringBuilder()
        for (line in lines) {
            when {
                line.startsWith("__mttok=") -> tok = line.removePrefix("__mttok=").trim().toLongOrNull() ?: 0L
                line.startsWith("__mtenc=") -> enc = line.removePrefix("__mtenc=").trim().toLongOrNull() ?: 0L
                line.startsWith("__mtdec=") -> dec = line.removePrefix("__mtdec=").trim().toLongOrNull() ?: 0L
                line.startsWith("__mtall=") -> total = line.removePrefix("__mtall=").trim().toLongOrNull() ?: 0L
                else -> if (clean.isNotEmpty() || line.isNotEmpty()) { if (clean.isNotEmpty()) clean.append('\n'); clean.append(line) }
            }
        }
        text = clean.toString()
        val timing = if (tok > 0 || enc > 0 || dec > 0 || total > 0)
            TranslationTiming(tokenizerMicros = tok, encoderMicros = enc, decoderMicros = dec, totalMicros = total)
        else null
        return text to timing
    }

    private fun nativeErrorKey(raw: String): String {
        if (raw.startsWith("10")) {
            return when (raw.substring(0, 3)) {
                "101" -> "Translation produced empty input"
                "102" -> "Offline translation model failed to load (no ONNX runtime)"
                "103" -> "Translation model pack invalid (missing config/vocab)"
                "104" -> "Offline translation model failed to load"
                "106" -> "Encoder/decoder model failed to load"
                else -> "Offline translation failed"
            }
        }
        return "Translation produced empty output"
    }

    override fun release() {
        // Release cached native sessions for the pair.
        try { if (nativeLoaded) nnRelease() } catch (_: Throwable) {}
        loadedKey = null
        loaded.set(false)
    }

    /** Native: tokenize→encode→greedy decode, returning translated text (monotonic per-stage timings appended). */
    private external fun nnTranslate(modelDir: String, text: String): String

    /** Native: drop the cached env/sessions for the current pair. */
    private external fun nnRelease()
}