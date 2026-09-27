package com.itantra.stt

/**
 * Heuristic-only signal derived from the transcribed TEXT and its audio duration.
 *
 * sherpa-onnx's OfflineRecognizer (the vendored AAR) exposes no logprob or score field over
 * its JNI boundary for greedy-search Whisper decoding — text/tokens/timestamps/lang only. A
 * real model-probability confidence would require patching sherpa-onnx's own C++ source and
 * rebuilding the AAR. Everything here is a proxy computed from what IS available, and is
 * deliberately conservative: it should only catch decode failures obvious from the text
 * itself (degenerate word repetition, an implausible word rate for the audio length), never
 * flag ordinary language variation. It works the same way for all 10 languages since it never
 * looks at a dictionary.
 */
object UtteranceQuality {
    private const val REPEAT_STREAK_HARD_REJECT = 4
    private const val REPEAT_STREAK_SOFT_PENALTY = 3
    private const val IMPLAUSIBLE_WORDS_PER_SECOND = 8f

    /**
     * Below this word count, "every word is a single codepoint" becomes a plausible signal of
     * a Whisper hallucination on near-silent/very short audio (isolated matras/diacritics,
     * sometimes mixed with a lone consonant — see [isGarbage]). A single one-codepoint word
     * alone is NOT rejected by this rule (e.g. a lone "I") — only 2-or-more such words together,
     * which is the actual pattern observed live ("ो ो", "े म").
     */
    private const val MIN_WORDS_FOR_SINGLE_CHAR_REJECT = 2

    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun maxRepeatStreak(words: List<String>): Int {
        var streak = 1
        var max = 1
        for (i in 1 until words.size) {
            streak = if (words[i].equals(words[i - 1], ignoreCase = true)) streak + 1 else 1
            if (streak > max) max = streak
        }
        return max
    }

    /** 0f..1f proxy confidence; NOT a real decoder probability — see class doc. */
    fun heuristicConfidence(text: String, durationMs: Long): Float {
        val w = words(text)
        if (w.isEmpty()) return 0f
        var score = 1f
        val streak = maxRepeatStreak(w)
        if (streak >= REPEAT_STREAK_HARD_REJECT) score -= 0.6f
        else if (streak >= REPEAT_STREAK_SOFT_PENALTY) score -= 0.25f
        if (durationMs > 0) {
            val wordsPerSecond = w.size / (durationMs / 1000f)
            if (wordsPerSecond > IMPLAUSIBLE_WORDS_PER_SECOND) score -= 0.3f
        }
        return score.coerceIn(0f, 1f)
    }

    /** True when the transcript is very likely a decode failure (empty or degenerate repeat). */
    fun isGarbage(text: String): Boolean {
        val w = words(text)
        if (w.isEmpty()) return true
        if (maxRepeatStreak(w) >= REPEAT_STREAK_HARD_REJECT) return true

        // Whisper hallucination on near-silent/very short audio characteristically emits one or
        // more isolated single-codepoint "words" — bare combining marks (matras/diacritics,
        // which by definition cannot stand alone as a word in any script since they attach to a
        // base letter), sometimes mixed with a lone bare consonant. A genuinely short REAL word
        // ("help", or "हाँ" — a consonant plus its vowel sign/anusvara, 3 codepoints) is always
        // more than a single codepoint once you count its own marks, so neither rule below can
        // fire on it. A single lone one-codepoint word (e.g. "I") is deliberately NOT rejected —
        // only the multi-word all-single-codepoint pattern actually observed live is.
        if (isDiacriticOnlyUtterance(w)) return true
        if (w.size >= MIN_WORDS_FOR_SINGLE_CHAR_REJECT && w.all { it.codePointCount(0, it.length) == 1 }) {
            return true
        }

        return false
    }

    /** True when EVERY word is made up entirely of combining marks — no base letter anywhere. */
    private fun isDiacriticOnlyUtterance(words: List<String>): Boolean = words.all { isDiacriticOnlyWord(it) }

    private fun isDiacriticOnlyWord(word: String): Boolean {
        var sawMark = false
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetter(cp)) return false // has a real base letter -> not diacritic-only
            when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK ->
                    sawMark = true
            }
        }
        return sawMark
    }
}
