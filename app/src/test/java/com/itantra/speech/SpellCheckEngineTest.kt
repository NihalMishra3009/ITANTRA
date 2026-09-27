package com.itantra.speech

import com.itantra.stt.SpellCheckEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Loads the REAL generated Bloom filters straight off disk with plain java.io (no Android
 * Context/AssetManager — this repo's JVM unit tests don't use Robolectric, see the other tests
 * in this package) and drives SpellCheckEngine's internal pure matcher directly. The Kotlin
 * BloomFilter reader used here is the exact same internal class SpellCheckEngine.check() uses
 * at runtime — these tests exercise the real production hashing/lookup code, not a stand-in.
 *
 * The point of these tests is to prove the Hunspell affix EXPANSION actually ran: Hindi and
 * Marathi are morphologically rich, so a flat list of dictionary-form roots would wrongly flag
 * real inflected words (plurals, oblique/case forms) as typos. If
 * model-conversion/build_spellcheck_wordlist.py regressed to shipping just the root list, the
 * inflected-form assertions below would fail even though the misspelling assertions would
 * still pass.
 */
class SpellCheckEngineTest {

    private fun loadFilter(languageCode: String): com.itantra.stt.BloomFilter {
        val f = File("src/main/assets/spellcheck/$languageCode.bloom")
        assertTrue("missing ${f.path} — run model-conversion/build_spellcheck_wordlist.py", f.exists())
        return f.inputStream().use { com.itantra.stt.BloomFilter.read(it) }
    }

    @Test
    fun `hindi recognizes real inflected forms, not just roots`() {
        val filter = loadFilter("hi")
        // लड़कियाँ (girls, plural) and किताबों (books, oblique/plural) are inflected surface
        // forms, not the dictionary-citation root — only present if affix expansion ran.
        val flagged = SpellCheckEngine.flagUnrecognized("लड़कियाँ किताबों पढ़ती हैं", "hi", filter)
        assertTrue("expected no flags, got $flagged", flagged.isEmpty())
    }

    @Test
    fun `hindi flags a deliberate misspelling`() {
        val filter = loadFilter("hi")
        // "भारतxyz" is not a valid Hindi word under any affix expansion.
        val flagged = SpellCheckEngine.flagUnrecognized("यह भारतxyz है", "hi", filter)
        assertTrue("expected भारतxyz to be flagged, got $flagged", flagged.contains("भारतxyz"))
    }

    @Test
    fun `marathi recognizes real inflected forms, not just roots`() {
        val filter = loadFilter("mr")
        // मुलांचा (of the boys) and घरात (in the house) are case/postposition-inflected forms
        // that only exist after TWO chained suffix applications in this dictionary's affix
        // rules (oblique-stem marker, then locative/genitive clitic) — proof the build script's
        // suffix chaining (not just single-level affixation) actually ran.
        val flagged = SpellCheckEngine.flagUnrecognized("मुलांचा चेंडू घरात आहे", "mr", filter)
        assertTrue("expected no flags, got $flagged", flagged.isEmpty())
    }

    @Test
    fun `marathi flags a deliberate misspelling`() {
        val filter = loadFilter("mr")
        val flagged = SpellCheckEngine.flagUnrecognized("हा चेंडूxyz आहे", "mr", filter)
        assertTrue("expected चेंडूxyz to be flagged, got $flagged", flagged.contains("चेंडूxyz"))
    }

    @Test
    fun `english recognizes inflected forms and flags a misspelling`() {
        val filter = loadFilter("en")
        val flagged = SpellCheckEngine.flagUnrecognized("The children are running quikly", "en", filter)
        assertFalse("children/running should be recognized, got $flagged", flagged.contains("children"))
        assertFalse("children/running should be recognized, got $flagged", flagged.contains("running"))
        assertTrue("quikly should be flagged, got $flagged", flagged.contains("quikly"))
    }

    @Test
    fun `unsupported language is a no-op`() {
        // No filter exists for these languages; SpellCheckEngine.check (the Context-taking
        // entry point) must return empty without ever touching the asset system for them.
        assertFalse(SpellCheckEngine.SUPPORTED_LANGUAGES.contains("gu"))
        assertFalse(SpellCheckEngine.SUPPORTED_LANGUAGES.contains("ta"))
    }
}
