package com.itantra.vad

/**
 * Pure voice-endpointing state machine — no Android dependency, deterministic for
 * unit tests. The caller supplies a monotonic timestamp (SystemClock.elapsedRealtime
 * on device; synthetic times in tests) plus a per-chunk speech probability and the
 * chunk sample count (used only for the fast-path chunk-size heuristic).
 *
 * Semantics guaranteed:
 * - SPEECH_START is emitted exactly once per utterance, only AFTER the accumulated
 *   speech duration reaches [minSpeechDurationMs]. Until then chunks report
 *   SHORT_PAUSE (a speech blip never becomes VOICE_ON prematurely).
 * - After SPEECH_START, speech chunks report SPEECH_CONTINUE.
 * - Sustained silence emits SHORT_PAUSE -> SENTENCE_END (>= sentenceEndMs) ->
 *   LONG_SILENCE (>= longSilenceMs), ending the utterance exactly once.
 * - The noise floor adapts during silence and never rises above the speech band.
 *
 * The active detector is ADAPTIVE ENERGY VAD. This class is honest: it is not a
 * neural VAD and never claims one.
 */
class VadStateMachine(
    var speechThreshold: Float = 0.5f,
    var minSpeechDurationMs: Long = 120L,
    var shortPauseMs: Long = 250L,
    var sentenceEndMs: Long = 700L,
    var longSilenceMs: Long = 2000L,
    var hangoverMs: Long = 180L
) {
    /** Adaptive noise-floor estimate (energy RMS band). */
    var noiseFloor: Float = 0.008f
        private set

    var isSpeaking: Boolean = false
        private set

    /** Accumulated speech duration (ms) via monotonic deltas supplied by caller. */
    var speechDurationMs: Long = 0L
        private set

    private var speechStartTimeMs: Long = 0L
    private var speechStartEmitted = false
    private var silenceStartTimeMs: Long = 0L
    private var hangoverRemainingMs: Long = 0L
    private var lastProcessMs: Long = -1L
    private var sentenceEndEmitted = false

    /** Minimum samples that count as a big chunk (fast-path bypass, 250ms @ 16k). */
    private val bigChunkSamples = 4000

    /**
     * Feed one chunk. [nowMs] MUST be monotonic and increasing across calls.
     * [rawProb] is the energy/speech probability for this chunk (0..1).
     */
    fun process(nowMs: Long, rawProb: Float, chunkSampleCount: Int): VadEvent {
        adaptNoiseFloor(rawProb)

        val dynamicThreshold = speechThreshold * 0.5f + noiseFloor * 0.5f
        val chunkSpeech = rawProb >= dynamicThreshold
        if (chunkSpeech) {
            hangoverRemainingMs = hangoverMs
            sentenceEndEmitted = false // speech resumed -> next pause is a new boundary
        } else if (hangoverRemainingMs > 0) {
            hangoverRemainingMs -= 32 // 512 samples = 32ms, matching engine window
        }
        val effectivelySpeaking = chunkSpeech || hangoverRemainingMs > 0

        if (effectivelySpeaking) {
            silenceStartTimeMs = 0L
            // Accumulated speech duration counts ONLY genuinely voiced chunks —
            // hangover-held (silent) chunks must not inflate it past the minimum.
            val chunkMs = if (lastProcessMs < 0) 0L else (nowMs - lastProcessMs).coerceAtLeast(0L)
            lastProcessMs = nowMs
            if (chunkSpeech) speechDurationMs += chunkMs
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTimeMs = nowMs
                speechDurationMs = 0L
                speechStartEmitted = false
                sentenceEndEmitted = false
            }
            if (speechStartEmitted) return VadEvent.SPEECH_CONTINUE
            if (chunkSampleCount >= bigChunkSamples) {
                speechStartEmitted = true
                return VadEvent.SPEECH_START
            }
            if (speechDurationMs >= minSpeechDurationMs) {
                speechStartEmitted = true
                return VadEvent.SPEECH_START
            }
            return VadEvent.SHORT_PAUSE
        }

        lastProcessMs = nowMs

        // --- Silence region ---
        if (!isSpeaking) return VadEvent.SILENCE

        val tier = if (silenceStartTimeMs == 0L) {
            silenceStartTimeMs = nowMs
            VadEvent.SHORT_PAUSE
        } else {
            val silenceDuration = nowMs - silenceStartTimeMs
            when {
                // LONG_SILENCE finalizes the utterance. It is only reachable when
                // silence persists past SENTENCE_END — SENTENCE_END marks a boundary
                // without ending the utterance, so 700ms pauses don't hard-stop
                // mid-sentence speech.
                silenceDuration >= longSilenceMs -> VadEvent.LONG_SILENCE
                silenceDuration >= sentenceEndMs ->
                    if (sentenceEndEmitted) VadEvent.SHORT_PAUSE
                    else { sentenceEndEmitted = true; VadEvent.SENTENCE_END }
                else -> VadEvent.SHORT_PAUSE
            }
        }
        if (tier == VadEvent.LONG_SILENCE) {
            isSpeaking = false
            speechDurationMs = 0L
            silenceStartTimeMs = 0L
            hangoverRemainingMs = 0L
            speechStartEmitted = false
            sentenceEndEmitted = false
        }
        return tier
    }

    private fun adaptNoiseFloor(rawProb: Float) {
        if (rawProb < speechThreshold) {
            noiseFloor = (noiseFloor * 0.95f + rawProb * 0.05f)
                .coerceIn(0.003f, speechThreshold * 0.6f)
        }
    }

    fun reset() {
        isSpeaking = false
        speechStartEmitted = false
        speechDurationMs = 0L
        speechStartTimeMs = 0L
        silenceStartTimeMs = 0L
        hangoverRemainingMs = 0L
        lastProcessMs = -1L
        noiseFloor = 0.008f
    }
}