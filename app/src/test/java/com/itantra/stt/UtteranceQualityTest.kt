package com.itantra.stt

import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the two Whisper-hallucination patterns seen live on near-silent/very short audio
 * ("ो ो", "े म" — isolated Devanagari matras/diacritics, sometimes mixed with a bare consonant)
 * while proving genuinely short REAL utterances in English and Hindi still pass through.
 */
class UtteranceQualityTest {

    // ---- must reject: real hallucination patterns observed live ----

    @Test
    fun rejectsRepeatedBareMatras() {
        assertTrue(UtteranceQuality.isGarbage("ो ो"))
    }

    @Test
    fun rejectsMatraPlusBareConsonant() {
        assertTrue(UtteranceQuality.isGarbage("े म"))
    }

    @Test
    fun rejectsSingleBareMatraAlone() {
        assertTrue(UtteranceQuality.isGarbage("ो"))
    }

    @Test
    fun rejectsEmptyText() {
        assertTrue(UtteranceQuality.isGarbage(""))
        assertTrue(UtteranceQuality.isGarbage("   "))
    }

    @Test
    fun rejectsDegenerateWordRepeatStreak() {
        assertTrue(UtteranceQuality.isGarbage("the the the the"))
    }

    // ---- must NOT reject: genuine short utterances ----

    @Test
    fun doesNotRejectShortEnglishWord() {
        assertFalse(UtteranceQuality.isGarbage("help"))
    }

    @Test
    fun doesNotRejectEnglishSentence() {
        assertFalse(UtteranceQuality.isGarbage("I need help"))
    }

    @Test
    fun doesNotRejectHindiYes() {
        // हाँ = consonant + vowel-sign + anusvara/chandrabindu — a real, complete short word.
        assertFalse(UtteranceQuality.isGarbage("हाँ"))
    }

    @Test
    fun doesNotRejectHindiSentence() {
        assertFalse(UtteranceQuality.isGarbage("मुझे मदद चाहिए"))
    }

    @Test
    fun doesNotRejectLoneSingleLetterWord() {
        // A single one-codepoint word alone (not paired with another) is not, by itself,
        // evidence of hallucination — only the multi-word all-single-codepoint pattern is.
        assertFalse(UtteranceQuality.isGarbage("I"))
    }

    @Test
    fun doesNotRejectTwoRealShortWords() {
        assertFalse(UtteranceQuality.isGarbage("I am"))
    }
}
