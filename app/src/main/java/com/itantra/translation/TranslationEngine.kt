package com.itantra.translation

import com.itantra.stt.SupportedLanguage

/**
 * Result of an offline translation step. Latency uses a monotonic clock
 * (SystemClock.elapsedRealtime) and is 0 only when no measurement was taken —
 * the UI must render unavailable metrics as "—", never a fabricated "0 ms".
 */
data class TranslationResult(
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val latencyMs: Long,
    val success: Boolean,
    val error: String? = null
) {
    companion object {
        fun unavailable(source: String, target: String): TranslationResult =
            TranslationResult("", source, target, 0L, success = false,
                error = "Cross-language communication unavailable (translation model missing)")

        fun failed(source: String, target: String, reason: String): TranslationResult =
            TranslationResult("", source, target, 0L, success = false, error = reason)
    }
}

/**
 * Offline neural translation engine. Implementations run fully on-device and are
 * interchangeable so the backend (e.g. Opus-MT ONNX) can be swapped later.
 *
 * A translation must NEVER be faked. If the engine cannot produce a genuine
 * result it returns a non-success result with a precise error — the caller must
 * not mislabel source-language text as target-language.
 */
interface TranslationEngine {
    /**
     * True if this engine can translate this directed pair (model actually loadable).
     */
    fun supports(sourceLanguage: String, targetLanguage: String): Boolean

    /**
     * Translate text from [sourceLanguage] to [targetLanguage], fully offline.
     * Returns a success=false result (never throws) if the pair/model is unavailable
     * or inference fails.
     */
    fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationResult

    /** Compute the canonical model key for a pair, e.g. "hi-en". */
    fun modelKey(sourceLanguage: String, targetLanguage: String): String =
        "${sourceLanguage.lowercase()}-${targetLanguage.lowercase()}"

    fun isLoaded(): Boolean

    fun release()

    companion object {
        /** Helper mirror used by the model-pack layer. */
        fun pairId(source: SupportedLanguage, target: SupportedLanguage): String =
            "${source.code}-${target.code}"
    }
}
