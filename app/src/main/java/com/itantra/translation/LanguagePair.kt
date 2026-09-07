package com.itantra.translation

import com.itantra.stt.SupportedLanguage

/**
 * A directed language pair: [source] is the language spoken into the microphone
 * (STT input), [target] is the language the receiver expects (packet text + TTS).
 *
 * Kept as a separate concept from [SupportedLanguage] — never overload a single
 * "language" to mean both source and target.
 */
data class LanguagePair(
    val source: SupportedLanguage,
    val target: SupportedLanguage
) {
    val isSameLanguage: Boolean get() = source == target
    val translationSupported: Boolean
        get() = TranslationCatalog.supports(source.code, target.code)

    /** Stable pack/route id, e.g. "hi-en" (canonical lowercase codes). */
    val pairId: String get() = "${source.code}-${target.code}"

    override fun toString(): String =
        if (isSameLanguage) source.code
        else "${source.code} -> ${target.code}"
}

/**
 * Central catalog of OFFLINE translation language pairs the executable engine
 * genuinely supports. Pairs with a real, licensed, loadable model are DIRECT.
 *
 * Architecture: each of the 10 iTantra languages has a real Opus-MT model with
 * ENGLISH (opus-mt-{x}-en and opus-mt-en-{x}). Any OTHER directed pair (X->Y)
 * translates through ENGLISH as a pivot with exactly two offline hops:
 *     X -> EN -> Y
 * This makes all 10 languages mutually cross-translatable, fully offline, using
 * only the per-language EN<->X packs. Same-language pairs are never translated.
 *
 * A pair is "direct" only when a real executable model exists for it. Pivoted
 * pairs are explicitly reported as such (never silently claimed as a single
 * direct model).
 */
object TranslationCatalog {

    private const val EN = "en"

    /** All languages that have a real EN<->X Opus-MT model. */
    private val pivotLangs: Set<String> = setOf("hi", "en", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")

    private fun norm(code: String): String = code.lowercase()

    /** True if a DIRECT model exists for this (source,target) pair. */
    fun isDirect(sourceCode: String, targetCode: String): Boolean {
        val s = norm(sourceCode); val t = norm(targetCode)
        return s != t && (s == EN && t in pivotLangs || t == EN && s in pivotLangs)
    }

    /**
     * True if translation is possible (direct OR via EN pivot). Same-language
     * returns false (no translation needed).
     */
    fun supports(sourceCode: String, targetCode: String): Boolean {
        val s = norm(sourceCode); val t = norm(targetCode)
        if (s == t) return false
        return isDirect(s, t) || (s in pivotLangs && t in pivotLangs)
    }

    /**
     * The hop route: [source, ..., target].
     * Direct => [s, t]. Non-English cross pair => [s, EN, t].
     */
    fun path(sourceCode: String, targetCode: String): List<String>? {
        val s = norm(sourceCode); val t = norm(targetCode)
        if (s == t) return null
        return if (isDirect(s, t)) listOf(s, t) else listOf(s, EN, t)
    }

    /** IDs of ALL direct model pairs (each maps to one downloadable pack). */
    fun supportedPairIds(): Set<String> =
        pivotLangs.flatMap { lang ->
            listOf("$EN-$lang", "$lang-$EN")
        }.filter { it.split("-")[0] != it.split("-")[1] }.toSet()
}
