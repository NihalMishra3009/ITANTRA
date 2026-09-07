package com.itantra.translation

import com.itantra.stt.SupportedLanguage
import org.junit.Assert.*
import org.junit.Test

/** Phase 1-2: LanguagePair semantics + catalog. */
class LanguagePairTest {

    @Test
    fun testLanguagePairSameLanguage() {
        val p = LanguagePair(SupportedLanguage.HINDI, SupportedLanguage.HINDI)
        assertTrue(p.isSameLanguage)
        assertEquals("hi-hi", p.pairId)
        // Same-language has no 'hi->hi' directed model; translation is never needed.
        assertFalse(p.translationSupported)
    }

    @Test
    fun testLanguagePairCrossLanguage() {
        val p = LanguagePair(SupportedLanguage.HINDI, SupportedLanguage.ENGLISH)
        assertFalse(p.isSameLanguage)
        assertEquals("hi-en", p.pairId)
        assertTrue(p.translationSupported)
        assertEquals("hi -> en", p.toString())
    }

    @Test
    fun testSupportedLanguageOrEqualConceptsNotConfused() {
        // source != target must not be conflated with a single language.
        val lang = SupportedLanguage.HINDI
        assertNotEquals(lang.code, "en")
        assertNotEquals(lang, SupportedLanguage.ENGLISH)
    }
}

/** Phase 3-4: catalog honesty — only executable pairs are declared. */
class TranslationCatalogTest {

    @Test
    fun testHiEnAndEnHiSupported() {
        assertTrue(TranslationCatalog.supports("hi", "en"))
        assertTrue(TranslationCatalog.supports("en", "hi"))
        // All 10 languages have direct EN<->X models: 2 directions × 9 langs = 18
        // direct packs (en-en filtered out).
        assertEquals(18, TranslationCatalog.supportedPairIds().size)
        assertTrue(TranslationCatalog.supportedPairIds().contains("hi-en"))
        assertTrue(TranslationCatalog.supportedPairIds().contains("en-hi"))
        assertTrue(TranslationCatalog.supportedPairIds().contains("gu-en"))
    }

    @Test
    fun testUnsupportedPairsNotClaimed() {
        // Cross-language pairs are supported via the EN pivot, never directly.
        assertTrue("hi->kn is supported (via en pivot)", TranslationCatalog.supports("hi", "kn"))
        assertTrue("ml->hi is supported (via en pivot)", TranslationCatalog.supports("ml", "hi"))
        assertTrue("kn->ta is supported (via en pivot)", TranslationCatalog.supports("kn", "ta"))
        // Same codes is NOT auto-supported — no translation needed.
        assertFalse(TranslationCatalog.supports("hi", "hi"))
        // A pair with both endpoints having no EN model is genuinely unavailable.
        assertFalse(TranslationCatalog.supports("xx", "yy"))
    }
}

/**
 * Deterministic fake engine for pipeline tests (test-scope only). Mirrors the
 * real contract: produces a genuine translated string for supported pairs,
 * returns unavailable/failure otherwise — never fabricated in the app.
 */
class FakeTranslationEngine(
    private val dictionary: Map<Pair<String, String>, (String) -> String>,
    private val failOn: Set<String> = emptySet()
) : TranslationEngine {
    override fun supports(sourceLanguage: String, targetLanguage: String): Boolean =
        dictionary.containsKey(sourceLanguage to targetLanguage)

    override fun translate(text: String, sourceLanguage: String, targetLanguage: String): TranslationResult {
        if (failOn.contains(text)) return TranslationResult.failed(sourceLanguage, targetLanguage, "engine fault (test)")
        val fn = dictionary[sourceLanguage to targetLanguage] ?: return TranslationResult.unavailable(sourceLanguage, targetLanguage)
        return TranslationResult(fn(text), sourceLanguage, targetLanguage, latencyMs = 12L, success = true)
    }

    override fun isLoaded(): Boolean = true
    override fun release() {}
}

private val HI_EN = FakeTranslationEngine(mapOf(
    "hi" to "en" to { s: String -> "en:$s" },
    "en" to "hi" to { s: String -> "hi:$s" }
))

/** Phase 3 test: HI→EN produces actual English output. */
class HindiToEnglishTranslationTest {
    @Test
    fun testHindiToEnglishProducesOutput() {
        val out = CrossLanguagePipeline.apply(
            "आप कहाँ जा रहे हैं?",
            SupportedLanguage.HINDI, SupportedLanguage.ENGLISH,
            translate = HI_EN::translate
        )
        assertTrue(out is CrossLanguagePipeline.Outcome.Translated)
        out as CrossLanguagePipeline.Outcome.Translated
        assertEquals("en:आप कहाँ जा रहे हैं?", out.text)
        assertEquals("en", out.target)
    }
}

/** Phase 3 test: EN→HI produces actual Hindi output. */
class EnglishToHindiTranslationTest {
    @Test
    fun testEnglishToHindiProducesOutput() {
        val out = CrossLanguagePipeline.apply(
            "Where are you going?",
            SupportedLanguage.ENGLISH, SupportedLanguage.HINDI,
            translate = HI_EN::translate
        )
        assertTrue(out is CrossLanguagePipeline.Outcome.Translated)
        (out as CrossLanguagePipeline.Outcome.Translated).let {
            assertEquals("hi:Where are you going?", it.text)
            assertEquals("hi", it.target)
        }
    }
}

/** Phase 12: same-language must ALWAYS bypass translation. */
class SameLanguageBypassTest {
    @Test
    fun testSameLanguageNeverTranslates() {
        var calls = 0
        val fn = { s: String, src: String, tgt: String ->
            calls++
            TranslationResult("WRONG-$s", src, tgt, 0L, success = true)
        }
        val out = CrossLanguagePipeline.apply("आप कहाँ जा रहे हैं?", SupportedLanguage.HINDI, SupportedLanguage.HINDI, fn)
        assertTrue(out is CrossLanguagePipeline.Outcome.SameLanguage)
        assertEquals("आप कहाँ जा रहे हैं?", (out as CrossLanguagePipeline.Outcome.SameLanguage).text)
        assertEquals("translation must NOT be invoked for same language", 0, calls)
    }
}

/** Phase 13: missing/failed translation must not crash and must not mislabel. */
class MissingTranslationModelTest {
    @Test
    fun testUnsupportedPairReportedUnavailable() {
        val out = CrossLanguagePipeline.apply(
            "X", SupportedLanguage.HINDI, SupportedLanguage.KANNADA,
            translate = { s, _, _ -> TranslationResult.unavailable(s, "") }
        )
        assertTrue(out is CrossLanguagePipeline.Outcome.Unavailable)
    }
}

class TranslationFailureTest {
    @Test
    fun testEngineFailureReturnsUnavailable_NoMislabel() {
        val failing = FakeTranslationEngine(
            mapOf("hi" to "en" to { "x" }), failOn = setOf("boom")
        )
        val out = CrossLanguagePipeline.apply("boom", SupportedLanguage.HINDI, SupportedLanguage.ENGLISH, failing::translate)
        assertTrue(out is CrossLanguagePipeline.Outcome.Unavailable)
        // No Translated outcome with mislabeled source language.
        assertFalse(out is CrossLanguagePipeline.Outcome.Translated)
    }
}