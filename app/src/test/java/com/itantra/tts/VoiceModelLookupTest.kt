package com.itantra.tts

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression: downloaded Piper packs keep their own .onnx file name, not "model.onnx". */
class VoiceModelLookupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun findsPiperStyleNamedModel() {
        val d = tmp.newFolder("te")
        val m = java.io.File(d, "te_IN-venkatesh-medium.onnx").apply { writeText("x") }
        java.io.File(d, "tokens.txt").writeText("a 1")
        assertEquals(m, TtsEngine.findVoiceModel(d))
    }

    @Test fun prefersCanonicalModelOnnx() {
        val d = tmp.newFolder("hi")
        java.io.File(d, "other.onnx").writeText("x")
        val canonical = java.io.File(d, "model.onnx").apply { writeText("x") }
        assertEquals(canonical, TtsEngine.findVoiceModel(d))
    }

    @Test fun nullWhenNoModel() {
        val d = tmp.newFolder("empty")
        java.io.File(d, "tokens.txt").writeText("a 1")
        assertNull(TtsEngine.findVoiceModel(d))
        assertNull(TtsEngine.findVoiceModel(java.io.File(d, "missing")))
    }
}

/** Regression: a CRLF tokens.txt aborts sherpa-onnx's Android build (uncatchable process kill). */
class TokensNormalizeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun stripsCarriageReturnsIncludingDoubled() {
        val f = java.io.File(tmp.newFolder("v"), "tokens.txt")
        f.writeBytes("  3\r\r\n! 4\r\r\n\" 150\r\r\n".toByteArray())
        assertTrue(TtsEngine.normalizeTokensFile(f))
        assertEquals("  3\n! 4\n\" 150\n", f.readText())
    }

    @Test fun leavesCleanFileUntouched() {
        val f = java.io.File(tmp.newFolder("v"), "tokens.txt")
        f.writeBytes("  3\n! 4\n".toByteArray())
        assertFalse(TtsEngine.normalizeTokensFile(f))
        assertEquals("  3\n! 4\n", f.readText())
    }

    @Test fun missingFileIsHarmless() {
        assertFalse(TtsEngine.normalizeTokensFile(java.io.File(tmp.newFolder("v"), "nope.txt")))
    }
}
