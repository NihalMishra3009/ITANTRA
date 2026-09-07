package com.itantra.translation

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline neural Hindi<->English translation via Helsinki-NLP Opus-MT exported to
 * a verified seq2seq ONNX deployment (encoder_model.onnx + decoder_model.onnx +
 * decoder_with_past_model.onnx), executed by a small native JNI adapter
 * (libitantra_mt.so) that calls the ONNX Runtime C API ALREADY LINKED by
 * sherpa-onnx — no second libonnxruntime.so is bundled (avoids the dual-ORT
 * native conflict). Fully on-device, no network. Apache-2.0.
 *
 * MODEL PACK CONTRACT (matches docs + convert_opus_mt_onnx.py):
 *   {filesDir}/models/translation/{src}-{tgt}/
 *     encoder_model.onnx
 *     decoder_model.onnx
 *     decoder_with_past_model.onnx   (optional accelerator)
 *     tokenizer/sentencepiece.model   (the real Marian SentencePiece model)
 *     vocab.json / spiece.vocab        (exact tokenizer vocab, IDs preserved)
 *     config.json                      (decoder_start_token_id, pad/eos/bos ids)
 *
 * If the model pack is not installed (or the native adapter is absent) the engine
 * returns a TRANSLATION_UNAVAILABLE result — it NEVER fabricates a translation.
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

    /** Locate the installed translation pack for [src]-[tgt] and set it as the active model dir. */
    fun ensureLoaded(sourceLanguage: String, targetLanguage: String): Boolean {
        val key = modelKey(sourceLanguage, targetLanguage)
        if (loaded.get() && loadedKey == key) return true
        val dir = File(context.filesDir, "$ROLE_DIR/$key")
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
            val translated = nnTranslate(modelDir, text)
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            if (translated.isBlank()) TranslationResult.failed(sourceLanguage, targetLanguage, "Translation produced empty output")
            else TranslationResult(translated, sourceLanguage, targetLanguage, latencyMs, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Translation inference failed", e)
            TranslationResult.failed(sourceLanguage, targetLanguage, "Offline translation model failed to load.")
        }
    }

    override fun release() {
        loadedKey = null
        loaded.set(false)
    }

    /** Native: run the full seq2seq (tokenize→encoder→greedy decode→detokenize) offline. */
    private external fun nnTranslate(modelDir: String, text: String): String
}