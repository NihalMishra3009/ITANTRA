package com.itantra.stt

import org.junit.Assert.*
import org.junit.Test

/** Guards the on-device crash: sherpa-onnx exit()s the process on a Whisper-invalid language. */
class WhisperLanguageGuardTest {
    @Test
    fun odiaIsNeverHandedToWhisper() {
        assertFalse(SttEngine.whisperSupports("or"))
        assertFalse(SttEngine.whisperSupports("OR"))
        assertEquals("hi", SttEngine.safeWhisperLanguage("or"))
    }

    @Test
    fun otherNineLanguagesAreSupported() {
        for (l in listOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "bn", "en")) {
            assertTrue(l, SttEngine.whisperSupports(l))
            assertEquals(l, SttEngine.safeWhisperLanguage(l))
        }
    }
}
