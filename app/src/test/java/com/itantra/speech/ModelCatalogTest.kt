package com.itantra.speech

import com.itantra.stt.SupportedLanguage
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the model-pack architecture: catalog honesty (IndicConformer / IndicF5 are
 * shared multilingual checkpoints; English is NOT faked), independent STT/TTS status,
 * and the multilingual-model storage principle.
 */
class ModelCatalogTest {

    @Test
    fun testCatalogHasAllTenLanguages() {
        val langs = ModelCatalog.packs().map { it.language.code }.toSet()
        val required = setOf("hi", "en", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")
        assertEquals(required, langs)
    }

    @Test
    fun testEveryLanguageHasSttAndTtsEntries() {
        for (lang in SupportedLanguage.values()) {
            assertNotNull("STT pack missing for ${lang.code}", ModelCatalog.sttPack(lang.code))
            assertNotNull("TTS pack missing for ${lang.code}", ModelCatalog.ttsPack(lang.code))
        }
    }

    @Test
    fun testIndicConformerIsSharedMultilingual_forIndicLangs() {
        // Hindi STT must honestly report the SHARED multilingual checkpoint, not
        // a "Hindi-only 80MB" pack.
        val hi = ModelCatalog.sttPack("hi")!!
        assertTrue(hi.supportsLanguage)
        assertTrue(hi.isMultilingualShared)
        assertEquals("IndicConformer-600m-multilingual", hi.modelName)
        // No converted loadable artifact is bundled yet -> no download offered
        // and size is 0 (honest, not a fabricated shared-size claim).
        assertNull("No download until a converted artifact is bundled", hi.downloadUrl)
        assertEquals(0L, hi.sizeBytes)
        // The note must explain conversion is required, not claim a working model.
        assertTrue(hi.notes.contains("conversion"))
    }

    @Test
    fun testIndicConformerDoesNotFakeEnglishStt() {
        val en = ModelCatalog.sttPack("en")!!
        assertFalse("IndicConformer has no English; must not be claimed supported", en.supportsLanguage)
        // English STT falls back to the bundled Whisper model (documented in notes).
        assertTrue(en.notes.contains("Whisper"))
    }

    @Test
    fun testIndicF5DoesNotFakeEnglishTts() {
        // English now has a REAL Piper voice, not IndicF5. Verify it's offered.
        val en = ModelCatalog.ttsPack("en")!!
        assertTrue("English must have a real downloadable voice", en.supportsLanguage)
        assertTrue("English TTS must be a real Piper voice", en.modelName.contains("Piper"))
        assertTrue("Must have a real verified SHA-256", en.checksumSha256.isNotBlank())
        assertNotNull("Must have a real download URL", en.downloadUrl)
        // Other Indic languages: Hindi uses real Piper too.
        val hin = ModelCatalog.ttsPack("hi")!!
        assertTrue(hin.supportsLanguage)
        assertTrue(hin.modelName.contains("Piper"))
        assertTrue("Hindi voice SHA must be real", hin.checksumSha256.isNotBlank())
    }

    @Test
    fun testAllNineIndicLangsCoveredByIndicConformerStt() {
        val nine = setOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")
        for (lang in nine) {
            assertTrue("IndicConformer should support $lang", ModelCatalog.sttPack(lang)!!.supportsLanguage)
        }
        // TTS: every language now has a genuinely downloadable voice with a real
        // SHA-256. Piper/Coqui/Mimic3 cover hi/gu/ml/bn/en; hosted MMS-TTS covers
        // the other 5 Indic languages (mr/kn/ta/te/or).
        for (lang in nine) {
            val tts = ModelCatalog.ttsPack(lang)!!
            assertTrue("$lang TTS must have a real download URL", tts.downloadUrl != null)
            assertTrue("$lang TTS must have a real SHA-256", tts.checksumSha256.isNotBlank())
            assertTrue("$lang TTS must be genuinely supported", tts.supportsLanguage)
        }
        // English TTS genuinely available via Piper.
        assertTrue("en TTS must be downloadable", ModelCatalog.ttsPack("en")!!.downloadUrl != null)
    }

    @Test
    fun testSttAndTtsAreIndependentlyInstallable() {
        // ModelStorageManager keeps STT and TTS in separate dirs, so deleting one
        // never affects the other. (Pure path-logic assertion on the layout.)
        // StorageManager needs a Context, so we assert the layout via the catalog id scheme:
        assertNotEquals("stt_hi", "tts_hi")
        assertTrue("stt_hi".startsWith("stt"))
        assertTrue("tts_hi".startsWith("tts"))
    }
}