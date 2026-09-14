package com.itantra.vad

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2 endpointing tests against the REAL VadStateMachine (the engine's
 * decision core — not a replica). Deterministic monotonic times injected by the
 * test; the production engine feeds SystemClock.elapsedRealtime().
 *
 * Active detector: Adaptive Energy VAD (honest; isUsingNeuralVad()==false).
 */
class VadStateMachineTest {

    private fun machine(minSpeech: Long = 120L) = VadStateMachine(
        speechThreshold = 0.5f,
        minSpeechDurationMs = minSpeech,
        sentenceEndMs = 700L,
        longSilenceMs = 2000L,
        hangoverMs = 180L
    )

    private val silenceProb = 0.01f
    private val speechProb = 0.8f
    private val chunk = 512

    // advance: emit successive chunks with monotonic now (32ms per chunk)
    private fun feed(vad: VadStateMachine, start: Long, probs: List<Float>): List<VadEvent> {
        var t = start
        return probs.map { p ->
            val e = vad.process(t, p, chunk)
            t += 32
            e
        }
    }

    @Test
    fun testNoiseOnly_neverSpeechStart() {
        val vad = machine()
        val evs = feed(vad, 0L, List(200) { silenceProb })
        assertTrue("noise-only never emits speech", evs.none { it == VadEvent.SPEECH_START || it == VadEvent.SPEECH_CONTINUE })
        assertFalse(vad.isSpeaking)
    }

    @Test
    fun testShortNoiseBurst_belowMinimumDuration_staysShortPause() {
        val vad = machine(minSpeech = 120L)
        // Two voiced chunks (64 ms) then silence — well below the 120 ms minimum.
        // Hangover will temporarily hold isSpeaking, but SPEECH_START must never emit.
        val evs = feed(vad, 0L, listOf(silenceProb, speechProb, speechProb, silenceProb, silenceProb))
        assertTrue("blip under minimum never becomes SPEECH_START",
            evs.none { it == VadEvent.SPEECH_START })
    }

    @Test
    fun testSpeechShorterThanMinimum_neverStarts() {
        val vad = machine(minSpeech = 120L)
        // 3 speech chunks = 96ms < 120ms threshold.
        val evs = feed(vad, 0L, List(3) { speechProb } + silenceProb)
        assertTrue("96ms speech < 120ms min", evs.none { it == VadEvent.SPEECH_START })
    }

    @Test
    fun testSpeechReachingMinimum_emitsStartThenContinue() {
        val vad = machine(minSpeech = 120L)
        // 5 voiced chunks accumulate 128ms (32ms each) >= 120ms minimum.
        val evs = feed(vad, 0L, List(5) { speechProb })
        assertEquals(VadEvent.SHORT_PAUSE, evs[0])
        assertEquals(VadEvent.SHORT_PAUSE, evs[1])
        assertEquals(VadEvent.SHORT_PAUSE, evs[2])
        assertEquals(VadEvent.SHORT_PAUSE, evs[3])
        assertEquals(VadEvent.SPEECH_START, evs[4])
        assertEquals(1, evs.count { it == VadEvent.SPEECH_START })
        assertEquals(128L, vad.speechDurationMs)
    }

    @Test
    fun testContinuousSpeech_startOnceContinueAfter() {
        val vad = machine()
        val evs = feed(vad, 0L, List(20) { speechProb })
        assertEquals("exactly one SPEECH_START", 1, evs.count { it == VadEvent.SPEECH_START })
        assertTrue(evs.dropWhile { it != VadEvent.SPEECH_START }.drop(1)
            .all { it == VadEvent.SPEECH_CONTINUE })
    }

    @Test
    fun testSpeechPlusShortPause_keepsUtteranceAlive() {
        val vad = machine()
        // speech x5, silence x8 (256ms < sentenceEnd 700ms), speech x4
        val evs = feed(vad, 0L,
            List(5) { speechProb } + List(8) { silenceProb } + List(4) { speechProb })
        // Pause within a sentence keeps speaking alive: no SENTENCE_END before 700ms.
        assertEquals("short inter-speech gap does not end utterance",
            0, evs.count { it == VadEvent.SENTENCE_END || it == VadEvent.LONG_SILENCE })
        // After the gap the utterance continues (eventually SPEECH_CONTINUE).
        assertTrue(evs.any { it == VadEvent.SPEECH_CONTINUE })
    }

    @Test
    fun testSpeechEnd_afterSentenceEndMs() {
        val vad = machine()
        // 6 speech + 30 silence (960ms > sentenceEnd 700ms): SENTENCE_END fires as a
        // boundary; the utterance is NOT finalized until LONG_SILENCE.
        val evs = feed(vad, 0L, List(6) { speechProb } + List(30) { silenceProb })
        assertTrue("sentence boundary reached", evs.any { it == VadEvent.SENTENCE_END })
        assertTrue("utterance survives past SENTENCE_END (only LONG_SILENCE finalizes)", vad.isSpeaking)
        assertFalse("not enough silence for LONG_SILENCE", evs.contains(VadEvent.LONG_SILENCE))
    }

    @Test
    fun testLongUtterance_timeoutsAfterLongSilence() {
        val vad = machine()
        // 60 speech (~1.9s) then 80 silence (~2.6s). Hangover holds ~6 chunks
        // (192ms); post-hangover silence ~2368ms > longSilenceMs (2000ms):
        // SENTENCE_END boundary first, then LONG_SILENCE finalizes.
        val evs = feed(vad, 0L, List(60) { speechProb } + List(80) { silenceProb })
        assertTrue("sentence boundary precedes finalize",
            evs.indexOf(VadEvent.SENTENCE_END) < evs.indexOf(VadEvent.LONG_SILENCE))
        assertTrue("long silence terminates the utterance", evs.contains(VadEvent.LONG_SILENCE))
        assertFalse(vad.isSpeaking)
    }

    @Test
    fun testChangingNoiseFloor_floorRaisesAndClamps() {
        val vad = machine()
        // Sustained 0.05-loud silence: floor converges toward 0.05 but never
        // exceeds the clamp (0.6*speechThreshold).
        var t = 0L
        val start = vad.noiseFloor
        repeat(200) {
            vad.process(t, 0.05f, chunk)
            t += 32
        }
        assertEquals("floor converges toward observed silence", 0.05f, vad.noiseFloor, 1e-3f)
        assertTrue("floor clamped at 0.6*speechThreshold", vad.noiseFloor <= 0.3f)
        assertNotEquals(start, vad.noiseFloor)
    }

    @Test
    fun testNoiseFloorAdaptsDown_thenSpeechStillDetectable() {
        val vad = machine()
        var t = 0L
        // Moderately loud ambient (0.06) sits BELOW the dynamic threshold, so it
        // silently raises the floor toward ~0.06 without triggering speech.
        repeat(100) { vad.process(t, 0.06f, chunk); t += 32 }
        val lifted = vad.noiseFloor
        assertTrue("floor actually rose", lifted > 0.008f)
        assertFalse("ambient below threshold must not start speech", vad.isSpeaking)
        // Real speech (0.8) must still exceed the raised dynamic threshold.
        val evs = feed(vad, t, List(6) { speechProb })
        assertTrue("speech detected despite raised floor", evs.contains(VadEvent.SPEECH_START))
    }

    @Test
    fun testBigChunkFastPath_startImmediately() {
        val vad = machine()
        // One 4000-sample chunk (>=250ms) bypasses the min-duration guard.
        val ev = vad.process(0L, speechProb, 4000)
        assertEquals(VadEvent.SPEECH_START, ev)
    }
}