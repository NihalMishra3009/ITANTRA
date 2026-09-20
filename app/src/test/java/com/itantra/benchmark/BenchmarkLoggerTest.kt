package com.itantra.benchmark

import org.junit.Assert.*
import org.junit.Test

/** Phase 3: benchmark math is real — percentiles and honest "no measurement" handling. */
class BenchmarkLoggerTest {

    private val logger = BenchmarkLogger

    @Test
    fun testPercentile_p50P95Correct() {
        val samples = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
        assertEquals(5L, logger.percentile(samples, 50.0))   // index 4 -> 5
        assertEquals(9L, logger.percentile(samples, 90.0))   // index 8 -> 9
        assertEquals(10L, logger.percentile(samples, 100.0)) // index 9 -> 10
    }

    @Test
    fun testPercentile_singleAndEmpty() {
        assertEquals(0L, logger.percentile(emptyList(), 50.0))
        assertEquals(42L, logger.percentile(listOf(42L), 95.0))
    }

    @Test
    fun testHasAnyMeasurement_rejectsFabricatedAllZero() {
        val zero = LatencyRecord("id", "hi", isAlert = false, 0, 0, 0, 0, 0, 0, 0, 0f)
        assertFalse("all-zero record = not a real measurement", zero.hasAnyMeasurement())

        val real = LatencyRecord("id", "hi", isAlert = false, 1200, 300, 0, 40, 200, 0, 600, 0.25f)
        assertTrue(real.hasAnyMeasurement())
    }

    @Test
    fun testNoMeasurementMeansNotMeasured_notZero() {
        // Legacy metrics where no measurement exists must never be reported as "0 ms";
        // the export uses "NOT MEASURED" (strings), so a zero here is the honest sentinel
        // that the export treats as absent.
        val r = LatencyRecord("id", "hi", isAlert = false, 0, 0, 0, 0, 0, 0, 0, 0f)
        assertFalse(r.hasAnyMeasurement())
    }

    @Test
    fun testReceivingPhoneDoesNotInventLatencyFromUnknownStartTimes() {
        // The receiving phone never saw the sender's speech/send times (0 = unknown). Subtracting
        // them produced the phone's uptime as an "E2E latency" (5,980,669,308 ms on a real device).
        val uptimeMs = 5_980_000_000L
        val r = logger.logInteraction(
            messageId = "rx", language = "en", isAlert = false,
            tSpeechStart = 0L, tSpeechEnd = 0L, tSttStart = 0L, tSttEnd = 0L,
            tSend = 0L, tReceive = uptimeMs - 3_000, tTtsStart = uptimeMs - 2_000,
            tTtsEnd = uptimeMs - 1_800, tPlayStart = uptimeMs - 1_700
        )
        assertEquals("E2E unknown -> not measured", 0L, r.totalE2eLatencyMs)
        assertEquals("transport unknown -> not measured", 0L, r.transportLatencyMs)
        assertEquals("STT unknown -> not measured", 0L, r.sttLatencyMs)
        // Locally measured segments are still reported.
        assertEquals(200L, r.ttsLatencyMs)
        assertEquals(100L, r.playbackLatencyMs)
    }

    @Test
    fun testFullyLocalMeasurementStillWorks() {
        val r = logger.logInteraction(
            messageId = "tx", language = "en", isAlert = false,
            tSpeechStart = 1_000L, tSpeechEnd = 3_000L, tSttStart = 3_000L, tSttEnd = 5_500L,
            tSend = 5_600L, tReceive = 5_900L, tTtsStart = 6_000L, tTtsEnd = 6_200L, tPlayStart = 6_300L
        )
        assertEquals(2_000L, r.speechDurationMs)
        assertEquals(2_500L, r.sttLatencyMs)
        assertEquals(300L, r.transportLatencyMs)
        assertEquals(3_300L, r.totalE2eLatencyMs)
    }
}
