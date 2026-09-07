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
 * genuinely supports. Only pairs with a real, licensed, loadable model are listed.
 *
 * The runtime engine is Helsinki-NLP Opus-MT (Apache-2.0) exported to ONNX and
 * executed via the bundled ONNX Runtime. Hindi <-> English is the first verified pair.
 *
 * Do NOT add a pair here until an executable model actually exists for it.
 */
object TranslationCatalog {

    /** Genuinely supported directed pairs (source,target). */
    private val supportedPairs: Set<Pair<String, String>> = setOf(
        "hi" to "en",
        "en" to "hi"
    )

    fun supports(sourceCode: String, targetCode: String): Boolean =
        sourceCode.lowercase() to targetCode.lowercase() in supportedPairs

    fun supportedPairIds(): Set<String> = supportedPairs.map { "${it.first}-${it.second}" }.toSet()
}
