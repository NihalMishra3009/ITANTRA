package com.itantra.stt

/**
 * Cheap, dictionary-free cross-check: does the transcribed TEXT actually contain the script a
 * given language would be written in? Whisper transcribes each of iTantra's 10 languages in
 * that language's native script (Devanagari for hi/mr, Gujarati script for gu, ...) — except
 * English, which comes out in Latin/ASCII. sherpa-onnx's SpokenLanguageIdentification runs on
 * raw AUDIO only and has no visibility into what Whisper actually decoded, so the two signals
 * are independent. A detected language whose script never shows up in the transcript is almost
 * certainly a low-confidence LID misdetection — exactly what happened live: "I need help"
 * (pure Latin script) got tagged "hi" by LID from a ~0.5s clip. This needs no new native API
 * surface — sherpa-onnx's SpokenLanguageIdentification (see the vendored
 * app/libs/sherpa-onnx-1.13.7.aar, already inspected when SttEngine's detectSpokenLanguage was
 * added in 6545cdc) exposes only the detected language code, no confidence/probability score,
 * so this text-script check is the cheapest available second opinion.
 */
object LanguageScript {

    // Inclusive Unicode block ranges for each script iTantra transcribes into. "en" is
    // deliberately absent: it's checked as "predominantly NOT one of these Indic blocks" below,
    // since Latin covers punctuation/digits that are also valid inside Indic-script text.
    private val SCRIPT_RANGES: Map<String, List<IntRange>> = mapOf(
        "hi" to listOf(0x0900..0x097F),
        "mr" to listOf(0x0900..0x097F),
        "gu" to listOf(0x0A80..0x0AFF),
        "kn" to listOf(0x0C80..0x0CFF),
        "ml" to listOf(0x0D00..0x0D7F),
        "ta" to listOf(0x0B80..0x0BFF),
        "te" to listOf(0x0C00..0x0C7F),
        "or" to listOf(0x0B00..0x0B7F),
        "bn" to listOf(0x0980..0x09FF)
    )

    private fun isIndicLetter(cp: Int): Boolean =
        SCRIPT_RANGES.values.any { ranges -> ranges.any { cp in it } }

    private fun letterCodePoints(text: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetter(cp)) out.add(cp)
        }
        return out
    }

    /**
     * True when [text] plausibly contains real letters of [langCode]'s script — or when the
     * text carries too little signal to judge either way (no letters at all: pure digits,
     * punctuation, or empty). Callers must treat "no signal" as non-contradicting rather than
     * as proof of a mismatch, so a genuinely wordless transcript never gets flagged here.
     */
    fun scriptMatches(text: String, langCode: String): Boolean {
        val letters = letterCodePoints(text)
        if (letters.isEmpty()) return true // no letters at all — no opinion either way

        return if (langCode.equals("en", ignoreCase = true)) {
            // Correctly-transcribed English should be overwhelmingly Latin script; a transcript
            // dominated by an Indic block is not English.
            val indicCount = letters.count { isIndicLetter(it) }
            indicCount.toFloat() / letters.size < 0.5f
        } else {
            val ranges = SCRIPT_RANGES[langCode.lowercase()] ?: return true // unknown code: no opinion
            letters.any { cp -> ranges.any { cp in it } }
        }
    }
}
