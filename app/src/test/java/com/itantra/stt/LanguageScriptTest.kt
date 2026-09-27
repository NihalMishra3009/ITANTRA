package com.itantra.stt

import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the specific live regression: LID tagged "I need help" (pure Latin script) as Hindi.
 * scriptMatches is the cross-check that catches this without any new native API.
 */
class LanguageScriptTest {

    @Test
    fun latinTextDoesNotMatchHindiScript() {
        assertFalse(LanguageScript.scriptMatches("I need help", "hi"))
    }

    @Test
    fun devanagariTextMatchesHindiScript() {
        assertTrue(LanguageScript.scriptMatches("मुझे मदद चाहिए", "hi"))
    }

    @Test
    fun devanagariTextDoesNotMatchEnglish() {
        assertFalse(LanguageScript.scriptMatches("मुझे मदद चाहिए", "en"))
    }

    @Test
    fun latinTextMatchesEnglish() {
        assertTrue(LanguageScript.scriptMatches("I need help", "en"))
    }

    @Test
    fun malayalamTextMatchesMalayalamNotHindi() {
        val malayalam = "എനിക്ക് സഹായം വേണം"
        assertTrue(LanguageScript.scriptMatches(malayalam, "ml"))
        assertFalse(LanguageScript.scriptMatches(malayalam, "hi"))
    }

    @Test
    fun blankOrPunctuationOnlyTextHasNoOpinion() {
        // No letters at all -> not a contradiction of anything; callers must not hard-fail here.
        assertTrue(LanguageScript.scriptMatches("", "hi"))
        assertTrue(LanguageScript.scriptMatches("123 !!", "hi"))
    }

    @Test
    fun unknownLanguageCodeHasNoOpinion() {
        assertTrue(LanguageScript.scriptMatches("anything", "xx"))
    }
}
