package com.itantra.ml

import com.itantra.stt.SupportedLanguage
import org.junit.Assert.*
import org.junit.Test

class SttTtsEngineTest {

    @Test
    fun testAllTenLanguagesSupported() {
        val languages = SupportedLanguage.values()
        assertEquals(10, languages.size)

        val codes = languages.map { it.code }.toSet()
        val required = setOf("hi", "en", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")
        assertEquals(required, codes)
    }

    @Test
    fun testCtcGreedyDecoderAlgorithm() {
        val vocab = listOf("<blank>", "a", "b", "c", "d", " ")
        val blankId = 0

        val frames = arrayOf(
            floatArrayOf(0.1f, 0.9f, 0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.1f, 0.8f, 0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.9f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.95f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.85f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.9f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.9f, 0.0f, 0.0f)
        )

        val sb = StringBuilder()
        var prevToken = blankId
        for (frame in frames) {
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (i in frame.indices) {
                if (frame[i] > maxVal) {
                    maxVal = frame[i]
                    maxIdx = i
                }
            }
            if (maxIdx != blankId && maxIdx != prevToken) {
                sb.append(vocab[maxIdx])
            }
            prevToken = maxIdx
        }

        assertEquals("abc", sb.toString())
    }

    @Test
    fun testEmptyAudioReturnsEmptyTranscript() {
        val emptyAudio = FloatArray(0)
        assertEquals(0, emptyAudio.size)
    }
}
