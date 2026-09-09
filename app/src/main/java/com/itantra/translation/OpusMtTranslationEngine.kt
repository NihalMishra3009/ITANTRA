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
/**
 * Structured native result — unambiguous, not string-sniffed. Returned by
 * OpusMtTranslationEngine.parseNativeRaw for the "__MT_OK__"/"__MT_ERR__"
 * envelopes produced by the JNI adapter.
 */
sealed class NativeTranslateResult {
    data class Success(val raw: String) : NativeTranslateResult() {
        val text: String get() = raw.substringBefore('\n')
        val timing: TranslationTiming? = parseTiming(raw)
        private fun parseTiming(raw: String): TranslationTiming? {
            var tok = 0L; var enc = 0L; var dec = 0L; var total = 0L
            for (line in raw.split('\n')) {
                when {
                    line.startsWith("__mttok=") -> tok = line.removePrefix("__mttok=").trim().toLongOrNull() ?: 0L
                    line.startsWith("__mtenc=") -> enc = line.removePrefix("__mtenc=").trim().toLongOrNull() ?: 0L
                    line.startsWith("__mtdec=") -> dec = line.removePrefix("__mtdec=").trim().toLongOrNull() ?: 0L
                    line.startsWith("__mtall=") -> total = line.removePrefix("__mtall=").trim().toLongOrNull() ?: 0L
                }
            }
            return if (tok > 0 || enc > 0 || dec > 0 || total > 0) TranslationTiming(tok, enc, dec, total) else null
        }
    }
    data class Error(val code: Int, val message: String) : NativeTranslateResult() {
        fun userMessage(): String = when (code) {
            101 -> "Translation produced empty input"
            102 -> "Offline translation model failed to load (no ONNX runtime)"
            103 -> "Translation model pack invalid"
            104 -> "Offline translation model failed to load"
            106 -> "Encoder/decoder model failed to load"
            111 -> "Tokenization failed"
            150 -> "Offline translation failed"
            else -> message.ifBlank { "Offline translation failed" }
        }
    }
}

class OpusMtTranslationEngine(
    private val context: Context
) : TranslationEngine {

    companion object {
        private const val TAG = "OpusMtTranslation"
        const val ROLE_DIR = "models/translation"
        private const val NATIVE_LIB = "itantra_mt"
        private var nativeLoaded = false
        private var loadAttempted = false

        /**
         * Parse the native envelope "__MT_OK__:<text>/__MT_ERR__:<code>|<msg>".
         * A malformed result is a runtime error, never silently a success. Pure —
         * no Context dependency, so it is JVM-unit-testable.
         */
        fun parseNativeRaw(raw: String): NativeTranslateResult =
            when {
                raw.startsWith("__MT_OK__:") -> NativeTranslateResult.Success(raw.removePrefix("__MT_OK__:"))
                raw.startsWith("__MT_ERR__:") -> {
                    val body = raw.removePrefix("__MT_ERR__:")
                    val code = body.substringBefore('|').toIntOrNull() ?: 150
                    val msg = body.substringAfter('|', missingDelimiterValue = "native error")
                    NativeTranslateResult.Error(code, msg)
                }
                else -> NativeTranslateResult.Error(150, "malformed native result")
            }
    }

    private val loaded = AtomicBoolean(false)
    private var loadedKey: String? = null
    private val selfTestDone = AtomicBoolean(false)

    /**
     * One-time off-main native self-test (load lib + resolve ORT C API), triggered
     * lazily on first model load rather than at construction — so creating an
     * engine with no installed pack never spawns a thread (Phase 11).
     * Logged so on-device ARM64 validation is observable in logcat (itan_mt).
     * Failure is non-fatal — translation still reports UNAVAILABLE.
     */
    private fun onceSelfTest() {
        if (selfTestDone.compareAndSet(false, true)) {
            try {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                    val tag = "itan_mt"
                    android.util.Log.i(tag, "nativeSelfTest -> " + nativeSelfTest())
                }
            } catch (_: Throwable) {}
        }
    }

    /** On-device native self-test: load lib, resolve ORT C API, respond OK/FAIL. */
    fun nativeSelfTest(): String {
        val loadedOk = ensureNativeLoaded()
        if (!loadedOk) return "__MT_ERR__:native-lib-unavailable"
        return try {
            val r = parseNativeRaw(nnNativeTest())
            if (r is NativeTranslateResult.Success) "NATIVE_TEST_OK" else "NATIVE_TEST_FAIL:${(r as NativeTranslateResult.Error).code}"
        } catch (e: Throwable) {
            "NATIVE_TEST_FAIL:" + (e.message ?: "err")
        }
    }

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
        onceSelfTest()
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
            val native = parseNativeRaw(nnTranslate(modelDir, text))
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            when (native) {
                is NativeTranslateResult.Success -> {
                    native.timing?.let { com.itantra.benchmark.BenchmarkLogger.logTranslationTiming(it) }
                    TranslationResult(
                        native.text, sourceLanguage, targetLanguage, latencyMs, success = true)
                }
                is NativeTranslateResult.Error -> {
                    Log.e(TAG, "Native translation error ${native.code}: ${native.message}")
                    TranslationResult.failed(sourceLanguage, targetLanguage, native.userMessage())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation inference failed", e)
            TranslationResult.failed(sourceLanguage, targetLanguage, "Offline translation model failed to load.")
        }
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

    /** Native: load lib + resolve ORT C API; returns NATIVE_TEST_OK on success. */
    private external fun nnNativeTest(): String
}