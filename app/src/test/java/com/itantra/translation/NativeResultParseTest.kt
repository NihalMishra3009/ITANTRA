package com.itantra.translation

import org.junit.Assert.*
import org.junit.Test

/** Phase 3: structured native result parsing — no fragile string sniffing. */
class NativeResultParseTest {

    @Test
    fun testParsesSuccessEnvelope() {
        val r = OpusMtTranslationEngine.parseNativeRaw("__MT_OK__:Where are you going?\n__mttok=100\n__mtenc=5000\n__mtdec=12000\n__mtall=18000")
        assertTrue(r is NativeTranslateResult.Success)
        val s = r as NativeTranslateResult.Success
        assertEquals("Where are you going?", s.text)
        assertEquals(18_000L, s.timing?.totalMicros)
    }

    @Test
    fun testParsesErrorEnvelope() {
        val r = OpusMtTranslationEngine.parseNativeRaw("__MT_ERR__:106|encoder/decoder session load failed")
        assertTrue(r is NativeTranslateResult.Error)
        val e = r as NativeTranslateResult.Error
        assertEquals(106, e.code)
        assertTrue(e.userMessage().contains("Encoder/decoder"))
    }

    @Test
    fun testMalformedIsRuntimeErrorNeverSuccess() {
        // A non-envelope garbage result must be an explicit runtime error, never a
        // silent blank success.
        val r = OpusMtTranslationEngine.parseNativeRaw("Where are you going?")
        assertTrue(r is NativeTranslateResult.Error)
        assertEquals(150, (r as NativeTranslateResult.Error).code)
    }

    @Test
    fun testBlankOkBodyParsesAsSuccess() {
        val r = OpusMtTranslationEngine.parseNativeRaw("__MT_OK__:")
        assertTrue(r is NativeTranslateResult.Success)
        assertEquals("", (r as NativeTranslateResult.Success).text)
        // translate() treats blank output as failure downstream.
    }
}