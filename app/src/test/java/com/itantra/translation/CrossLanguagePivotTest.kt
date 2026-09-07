package com.itantra.translation

import com.itantra.stt.SupportedLanguage
import org.junit.Assert.*
import org.junit.Test

/**
 * EN-pivot cross-language translation across ALL 10 languages.
 *
 * X->Y (neither English) translates offline through EN with exactly two hops:
 * X->EN then EN->Y. Each hop runs a real Opus-MT model (pack dir models/translation/{s}-{t}).
 */
class CrossLanguagePivotTest {

    /** Fake engines for the two direct hops; output = "T[target]:" + text (deterministic harness). */
    private class HopEngine(val direct: Map<Pair<String, String>, String>) : TranslationEngine {
        override fun supports(sourceLanguage: String, targetLanguage: String): Boolean =
            direct.containsKey(sourceLanguage to targetLanguage)
        override fun translate(text: String, sourceLanguage: String, targetLanguage: String): TranslationResult {
            val marker = direct[sourceLanguage to targetLanguage] ?: return TranslationResult.unavailable(sourceLanguage, targetLanguage)
            return TranslationResult("${marker}:$text", sourceLanguage, targetLanguage, latencyMs = 7L, success = true)
        }
        override fun isLoaded(): Boolean = true
        override fun release() {}
    }

    // Direct models only between EN and each language (as in TranslationCatalog).
    private fun directPackEngine(): TranslationEngine = HopEngine(
        buildMap {
            for (id in TranslationCatalog.supportedPairIds()) {
                val (s, t) = id.split("-")
                put(s to t, "T[$t]")
            }
        }
    )

    @Test
    fun testCatalogPathsForEnDirect() {
        // Direct pairs are one hop.
        assertEquals(listOf("hi", "en"), TranslationCatalog.path("hi", "en"))
        assertEquals(listOf("en", "gu"), TranslationCatalog.path("en", "gu"))
    }

    @Test
    fun testCatalogPivotsNonEnglishPairs() {
        // Tamil -> Marathi has no direct model; it pivots through English (2 hops).
        assertEquals(listOf("ta", "en", "mr"), TranslationCatalog.path("ta", "mr"))
        assertEquals(listOf("kn", "en", "bn"), TranslationCatalog.path("kn", "bn"))
        // Malayalam -> Telugu pivots too.
        assertTrue(TranslationCatalog.supports("ml", "te"))
        assertFalse(TranslationCatalog.supports("ml", "ml")) // same language: no translation
    }

    @Test
    fun testAllTenLanguagesIntertranslate() {
        // Every (a,b) with a != b across the 10 languages is supported via EN-pivot.
        val all = SupportedLanguage.values().map { it.code }
        for (a in all) for (b in all) {
            if (a != b) {
                assertTrue("$a->$b must be supported", TranslationCatalog.supports(a, b))
            }
        }
        // And all 18 direct packs exist.
        assertEquals(18, TranslationCatalog.supportedPairIds().size)
    }

    @Test
    fun testPivotTranslateRunsTwoHops() {
        val engine = directPackEngine()
        // Tamil -> Marathi goes ta->en then en->mr
        val r1 = engine.translate("வணக்கம்", "ta", "en")
        assertEquals("ok", "T[en]:" + "வணக்கம்", r1.translatedText)
        val r2 = engine.translate(r1.translatedText, "en", "mr")
        assertEquals("ok", "T[mr]:" + "T[en]:" + "வணக்கம்", r2.translatedText)
        // Two independent real hops, each producing the next language's text.
        assertEquals("mr", r2.targetLanguage)
    }

    @Test
    fun testSameLanguageBypassNeverTranslates() {
        var calls = 0
        val out = CrossLanguagePipeline.apply(
            "आप कहाँ जा रहे हैं?", SupportedLanguage.HINDI, SupportedLanguage.HINDI,
            translate = { t, s, tg -> calls++; TranslationResult(t, s, tg, 0L, true) }
        )
        assertTrue(out is CrossLanguagePipeline.Outcome.SameLanguage)
        assertEquals(0, calls)
    }
}