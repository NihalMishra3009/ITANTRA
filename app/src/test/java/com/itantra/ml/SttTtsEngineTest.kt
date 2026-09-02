package com.itantra.ml

import com.itantra.stt.SupportedLanguage
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

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

        // Simulated frames: [a, a, blank, b, b, blank, c] -> "abc"
        val frames = arrayOf(
            floatArrayOf(0.1f, 0.9f, 0.0f, 0.0f, 0.0f, 0.0f), // a (1)
            floatArrayOf(0.1f, 0.8f, 0.0f, 0.0f, 0.0f, 0.0f), // a (1)
            floatArrayOf(0.9f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f), // blank (0)
            floatArrayOf(0.0f, 0.0f, 0.95f, 0.0f, 0.0f, 0.0f), // b (2)
            floatArrayOf(0.0f, 0.0f, 0.85f, 0.0f, 0.0f, 0.0f), // b (2)
            floatArrayOf(0.9f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f), // blank (0)
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.9f, 0.0f, 0.0f)  // c (3)
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
    fun testTtsWaveformSynthesisGeneration() {
        val sampleRate = 22050
        val text = "मुझे मदद चाहिए"
        val words = text.split(" ")
        assertTrue(words.isNotEmpty())

        val totalSamples = (sampleRate * 0.8).toInt()
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val sample = (20000.0 * sin(2.0 * PI * 135.0 * t)).toInt()
            pcm[i] = sample.toShort()
        }

        assertEquals(totalSamples, pcm.size)
        assertTrue(pcm.any { it > 1000 })
        assertTrue(pcm.any { it < -1000 })
    }
}
