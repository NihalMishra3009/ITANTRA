package com.itantra.ai4bharat

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * AI4Bharat Indic Text Normalizer for Indic Scripts.
 * Implements Unicode normalization (NFC), zero-width character cleaning,
 * punctuation standardization, and script preservation across 10 Indian languages.
 */
object IndicTextNormalizer {

    private val ZERO_WIDTH_CHARS = Pattern.compile("[\u200B\u200C\u200D\uFEFF]")
    private val MULTIPLE_WHITESPACE = Pattern.compile("\\s+")
    private val REPEATED_PUNCTUATION = Pattern.compile("([।!?,.])\\1+")

    /**
     * Normalizes text transcribed by AI4Bharat IndicConformer or input by user.
     */
    fun normalize(text: String, langCode: String): String {
        if (text.isBlank()) return ""

        // 1. Unicode Canonical Decomposition followed by Canonical Composition (NFC)
        var normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)

        // 2. Remove invisible zero-width spaces/joiners that can disrupt tokenization
        normalized = ZERO_WIDTH_CHARS.matcher(normalized).replaceAll("")

        // 3. Collapse multiple whitespace into a single space
        normalized = MULTIPLE_WHITESPACE.matcher(normalized).replaceAll(" ")

        // 4. Standardize Indic Danda (। - \u0964) and punctuation for Devanagari/Eastern Indic
        normalized = REPEATED_PUNCTUATION.matcher(normalized).replaceAll("$1")

        // 5. Language-specific cleanup (e.g. Nukta standardization in Hindi, Marathi, Bengali)
        normalized = applyLanguageSpecificRules(normalized, langCode)

        return normalized.trim()
    }

    private fun applyLanguageSpecificRules(text: String, langCode: String): String {
        return when (langCode.lowercase()) {
            "hi", "mr" -> {
                // Devanagari standardizations (e.g. combining character normalization)
                text.replace("\u0958", "\u0915\u093C") // q -> ka + nukta
                    .replace("\u0959", "\u0916\u093C") // kh -> kha + nukta
                    .replace("\u095A", "\u0917\u093C") // gh -> ga + nukta
                    .replace("\u095B", "\u091C\u093C") // z -> ja + nukta
                    .replace("\u095C", "\u0921\u093C") // ddd -> dda + nukta
                    .replace("\u095D", "\u0922\u093C") // rh -> ddha + nukta
                    .replace("\u095E", "\u092B\u093C") // f -> pha + nukta
            }
            "bn" -> {
                // Bengali specific normalizations
                text.replace("\u09DC", "\u09A1\u09BC") // rra
                    .replace("\u09DD", "\u09A2\u09BC") // rha
                    .replace("\u09DF", "\u09AF\u09BC") // yya
            }
            "ta" -> {
                // Tamil normalizations
                text.replace("\u0B94", "\u0B92\u0BD7") // au length mark
            }
            else -> text
        }
    }
}
