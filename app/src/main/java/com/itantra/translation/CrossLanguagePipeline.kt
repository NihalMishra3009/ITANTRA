package com.itantra.translation

import com.itantra.stt.SupportedLanguage

/**
 * Pure cross-language pipeline decisions — no Android dependencies, fully
 * unit-testable in the JVM. The orchestrator delegates to this so the SAME logic
 * is verified by tests and used in production (single source of truth).
 *
 * Responsibilities:
 *  - decide whether translation is required (source == target → bypass)
 *  - compute the packet language (language of the actual transmitted text)
 *  - implement the fallback contract: never mislabel source text as target
 */
object CrossLanguagePipeline {

    /** True if translation must run (cross-language mode). */
    fun translationRequired(source: SupportedLanguage, target: SupportedLanguage): Boolean =
        source != target

    /**
     * The language of the transmitted packet text. Equal to [target] in
     * cross-language mode; equal to [source] otherwise (same-language).
     */
    fun packetLanguage(source: SupportedLanguage, target: SupportedLanguage): SupportedLanguage =
        if (translationRequired(source, target)) target else source

    /**
     * Outcome model of one send. Pure so tests can assert the exact behavior:
     *  - SAME_LANGUAGE: text passed through unchanged, no translation attempted.
     *  - TRANSLATED: source text replaced by translated target text (packet lang = target).
     *  - TRANSLATION_UNAVAILABLE: cross-language requested but no usable result —
     *    the message is NOT sent mislabeled; the caller surfaces the error.
     */
    sealed class Outcome {
        data class SameLanguage(val text: String) : Outcome()
        data class Translated(val text: String, val target: String, val latencyMs: Long) : Outcome()
        data class Unavailable(val error: String) : Outcome()
    }

    /**
     * Apply the pipeline decision. [translate] is an injectable function (in tests
     * a fake deterministic engine; in production the ONNX Opus-MT engine).
     */
    fun apply(
        sourceText: String,
        source: SupportedLanguage,
        target: SupportedLanguage,
        translate: (String, String, String) -> TranslationResult
    ): Outcome {
        if (!translationRequired(source, target)) {
            return Outcome.SameLanguage(sourceText)
        }
        if (!TranslationCatalog.supports(source.code, target.code)) {
            return Outcome.Unavailable("Cross-language communication unavailable (translation model missing)")
        }
        val res = translate(sourceText, source.code, target.code)
        if (!res.success || res.translatedText.isBlank()) {
            val msg = res.error ?: "Translation failed"
            return Outcome.Unavailable(msg)
        }
        return Outcome.Translated(res.translatedText, target.code, res.latencyMs)
    }
}