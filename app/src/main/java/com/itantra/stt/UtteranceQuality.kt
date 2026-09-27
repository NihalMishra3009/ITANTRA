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

    private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

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
        return maxRepeatStreak(w) >= REPEAT_STREAK_HARD_REJECT
    }
}
