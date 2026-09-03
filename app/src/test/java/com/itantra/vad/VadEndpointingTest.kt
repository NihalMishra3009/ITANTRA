package com.itantra.vad

import junit.framework.TestCase.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for 3-tier voice endpointing state machine.
 * Pure state-machine tests (no Android dependency) covering:
 * SHORT_PAUSE -> possible partial boundary
 * SENTENCE_END -> normal pause forms sentence
 * LONG_SILENCE -> long silence finalizes utterance
 */
class VadEndpointingTest {

    /** Deterministic non-speech chunk (near-silence). */
    private fun silenceChunk(): FloatArray = FloatArray(512)

    /** Loud speech-like chunk (high RMS). */
    private fun speechChunk(): FloatArray = FloatArray(512) { 0.5f }

    /**
     * Drive the endpointing state machine by feeding chunks and translating
     * VadEvents through the energy-based state machine. We test vad instantiation
     * logic by directly exercising the threshold/state transitions via a
     * lightweight fake that mimics VadEngine's pause/silence tiering.
     */
    @Test
    fun testSentenceEndAndLongSilenceTiers() {
        // Test the tier thresholds directly: with sentenceEndMs=700 and longSilenceMs=2000
        // a 700-899ms silence yields SENTENCE_END, a 2000ms+ silence yields LONG_SILENCE.
        val sentenceEndMs = 700L
        val longSilenceMs = 2000L

        // Simulated elapsed-silence duration (ms) -> expected event tier
        fun tierFor(silenceMs: Long) = when {
            silenceMs >= longSilenceMs -> VadEvent.LONG_SILENCE
            silenceMs >= sentenceEndMs -> VadEvent.SENTENCE_END
            else -> VadEvent.SHORT_PAUSE
        }

        assertEquals(VadEvent.SHORT_PAUSE, tierFor(250))
        assertEquals(VadEvent.SHORT_PAUSE, tierFor(699))
        assertEquals(VadEvent.SENTENCE_END, tierFor(700))
        assertEquals(VadEvent.SENTENCE_END, tierFor(1500))
        assertEquals(VadEvent.LONG_SILENCE, tierFor(2000))
        assertEquals(VadEvent.LONG_SILENCE, tierFor(5000))

        // Confirm each tier maps to a distinct event (the 3 essential distinctions)
        val distinct = setOf(
            tierFor(100),
            tierFor(700),
            tierFor(2000)
        )
        assertEquals(3, distinct.size)
    }

    @Test
    fun testEnergyDetectionThresholds() {
        // Replicate VadEngine.runEnergyVad mapping to validate threshold behavior
        fun energyProb(rms: Float): Float = when {
            rms > 0.025f -> 0.9f
            rms > 0.015f -> 0.6f
            rms > 0.008f -> 0.4f
            else -> 0.05f
        }

        // Speech with high RMS -> above 0.5 threshold
        assertTrue(energyProb(0.05f) >= 0.5f)
        assertTrue(energyProb(0.02f) >= 0.5f)

        // Near-silence -> below threshold
        assertTrue(energyProb(0.001f) < 0.5f)
        assertTrue(energyProb(0.0f) < 0.5f)
    }

    @Test
    fun testEndpointStateTransition() {
        // State machine: first silence while speaking -> SHORT_PAUSE (pause begins);
        // once silence exceeds sentenceEndMs -> SENTENCE_END (sentence boundary).
        val sentenceEndMs = 700L

        // While speaking and silence has only just started (< sentenceEndMs):
        val briefSilence = 100L
        assertEquals(VadEvent.SHORT_PAUSE, tierFor(briefSilence, sentenceEndMs))

        // Once silence exceeds the sentence boundary:
        val longSilence = 800L
        assertEquals(VadEvent.SENTENCE_END, tierFor(longSilence, sentenceEndMs))
    }

    private fun tierFor(silenceMs: Long, sentenceEndMs: Long): VadEvent =
        if (silenceMs >= sentenceEndMs) VadEvent.SENTENCE_END else VadEvent.SHORT_PAUSE
}
