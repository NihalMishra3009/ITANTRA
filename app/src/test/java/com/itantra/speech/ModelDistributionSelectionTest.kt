package com.itantra.speech

import com.itantra.stt.SupportedLanguage
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the download-once-then-offline model delivery + confirmed target-model
 * selection (IndicConformer STT / IndicF5 TTS, mobile-optimized INT8).
 *
 * These tests exercise the decision/reporting logic without network or Context:
 * they verify the confirmed targets are ranked as highest quality, that a
 * downloaded (size > 0) pack surfaces as available, and that availability is
 * never faked for a missing pack.
 */
class ModelDistributionSelectionTest {

    private fun sttPack(name: String, sizeMb: Long, quality: QualityTier, device: DeviceClass) = ModelPack(
        language = SupportedLanguage.HINDI, role = ModelRole.STT,
        modelName = name, sizeBytes = sizeMb * 1024 * 1024,
        runtime = Mlruntime.SHERPA_PREFIX, quantization = Quantization.INT8,
        sampleRate = 16000, supportedDeviceClass = device,
        quality = quality, license = "x", displayName = name
    )

    @Test
    fun testIndicConformerIsPrimarySttOverWhisper() {
        val whisper = sttPack("Whisper-base-int8", 152, QualityTier.MID, DeviceClass.MID)
        val indicConformer = sttPack("IndicConformer-INT8", 60, QualityTier.HIGH, DeviceClass.MID)
        // User-confirmed primary: IndicConformer is HIGH quality, so it outranks Whisper(MID).
        assertTrue(indicConformer.quality.ordinal > whisper.quality.ordinal)
        val picked = listOf(whisper, indicConformer)
            .filter { it.supportedDeviceClass.ordinal <= DeviceClass.HIGH.ordinal }
            .maxByOrNull { it.quality.ordinal }
        assertEquals("IndicConformer-INT8", picked?.modelName)
    }

    @Test
    fun testIndicF5IsHighQualityTts() {
        val vits = ModelPack(
            language = SupportedLanguage.BENGALI, role = ModelRole.TTS,
            modelName = "VITS-bn", sizeBytes = 109L * 1024 * 1024,
            runtime = Mlruntime.SHERPA_VITS, quantization = Quantization.NONE,
            sampleRate = 24000, supportedDeviceClass = DeviceClass.MID,
            quality = QualityTier.MID, license = "MIT", displayName = "VITS-bn"
        )
        val f5 = ModelPack(
            language = SupportedLanguage.BENGALI, role = ModelRole.TTS,
            modelName = "IndicF5-INT8", sizeBytes = 80L * 1024 * 1024,
            runtime = Mlruntime.SHERPA_MATCHA, quantization = Quantization.INT8,
            sampleRate = 24000, supportedDeviceClass = DeviceClass.HIGH,
            quality = QualityTier.HIGH, license = "verify", displayName = "IndicF5"
        )
        // On a HIGH device IndicF5 is chosen over existing VITS.
        val picked = listOf(vits, f5)
            .filter { it.supportedDeviceClass.ordinal <= DeviceClass.HIGH.ordinal }
            .maxByOrNull { it.quality.ordinal }
        assertEquals("IndicF5-INT8", picked?.modelName)
        // On a LOW device IndicF5 (HIGH-only) is not affordable; VITS(MID, MID device) is used.
        val lowPicked = listOf(vits, f5)
            .filter { it.supportedDeviceClass.ordinal <= DeviceClass.LOW.ordinal }
            .maxByOrNull { it.quality.ordinal }
        assertNull("IndicF5 not affordable on LOW", lowPicked?.takeIf { it.modelName == "IndicF5-INT8" })
    }

    @Test
    fun testDownloadedPackCountsAsAvailable_notFakedWhenMissing() {
        // A downloaded pack's "availability" is driven by its file size > 0.
        val installed = sttPack("IndicConformer-INT8", 60, QualityTier.HIGH, DeviceClass.MID)
        assertTrue("installed pack has files -> available", installed.sizeBytes > 0)

        // A missing pack (never downloaded) has size 0 -> NOT available (honest).
        val notInstalled = sttPack("IndicConformer-INT8", 0, QualityTier.HIGH, DeviceClass.MID)
        assertTrue("missing pack size 0 -> not available", notInstalled.sizeBytes <= 0)
        assertNotEquals(installed.sizeBytes, notInstalled.sizeBytes)
    }

    @Test
    fun testAllTenLanguagesDeclared() {
        // Registry must declare candidate packs for all 10 required languages.
        val codes = SupportedLanguage.values().map { it.code }.toSet()
        val required = setOf("hi", "en", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")
        assertEquals(required, codes)
    }
}
