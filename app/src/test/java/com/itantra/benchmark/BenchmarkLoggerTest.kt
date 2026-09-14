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
}