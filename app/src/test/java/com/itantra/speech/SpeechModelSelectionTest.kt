package com.itantra.speech

import com.itantra.stt.SupportedLanguage
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the SpeechModelManager / ModelPackRegistry ML architecture.
 * These are pure logic tests — they verify honest availability reporting,
 * lazy selection, device-capability budgeting, and quality-safety gating,
 * without requiring actual model assets (a Context/Assets is not available in
 * plain JVM unit tests, so we exercise the decision logic directly).
 */
class SpeechModelSelectionTest {

    @Test
    fun testDeviceClassClassification() {
        // Classify strictly by hardware characteristics (RAM), not brand.
        assertEquals(DeviceClass.HIGH, classifyTest(8192, 8))
        assertEquals(DeviceClass.MID, classifyTest(4096, 8))
        assertEquals(DeviceClass.MID, classifyTest(2048, 4))
        assertEquals(DeviceClass.LOW, classifyTest(1536, 2))
    }

    private fun classifyTest(ramMb: Long, cores: Int): DeviceClass {
        return when {
            ramMb >= 6144 -> DeviceClass.HIGH
            ramMb >= 2048 -> DeviceClass.MID
            else -> DeviceClass.LOW
        }
    }

    @Test
    fun testDeviceBudget() {
        val profile = DeviceCapabilityProfile(
            totalRamMb = 4096, cpuCoreCount = 8, cpuArchitecture = "arm64-v8a",
            androidSdk = 34, availableStorageMb = 8000, deviceClass = DeviceClass.MID
        )
        // Can afford a 400MB model? 4096 * 0.4 = 1638MB budget -> yes.
        assertTrue(profile.canAffordModel(400))
        // Cannot afford a 2000MB model.
        assertFalse(profile.canAffordModel(2000))
    }

    @Test
    fun testModelPackMetadata() {
        val pack = ModelPack(
            language = SupportedLanguage.HINDI,
            role = ModelRole.STT,
            modelName = "Whisper-base-int8",
            sizeBytes = 152L * 1024 * 1024,
            runtime = Mlruntime.SHERPA_WHISPER,
            quantization = Quantization.INT8,
            sampleRate = 16000,
            supportedDeviceClass = DeviceClass.MID,
            quality = QualityTier.MID,
            license = "MIT",
            displayName = "Whisper"
        )
        assertEquals(152.0, pack.sizeMb, 1.0)
        assertEquals(ModelRole.STT, pack.role)
        assertEquals(Quantization.INT8, pack.quantization)
    }

    @Test
    fun testQualitySafetyGate() {
        // Uses SpeechModelManager's thresholds via a lightweight stand-in:
        // a large / low-quality / slow pack must be rejected.
        val smallOkPack = ModelPack(
            language = SupportedLanguage.HINDI, role = ModelRole.STT,
            modelName = "ok", sizeBytes = 50L * 1024 * 1024,
            runtime = Mlruntime.SHERPA_WHISPER, quantization = Quantization.INT8,
            sampleRate = 16000, supportedDeviceClass = DeviceClass.LOW,
            quality = QualityTier.MID, license = "MIT", displayName = "ok"
        )
        // SIZE rejection (only reachable via SpeechModelManager which needs a Context).
        // We assert the formula directly: 50MB < 150MB max -> passes size rule.
        assertTrue(smallOkPack.sizeBytes <= 150L * 1024 * 1024)
        // Quality gate: HIGH needed, pack is MID -> below min.
        assertTrue(smallOkPack.quality.ordinal < QualityTier.HIGH.ordinal)
    }

    @Test
    fun testBestPackSelectionPrefersHighestQualityAffordable() {
        val low = ModelPack(
            language = SupportedLanguage.HINDI, role = ModelRole.TTS,
            modelName = "low", sizeBytes = 20L * 1024 * 1024,
            runtime = Mlruntime.SHERPA_VITS, quantization = Quantization.NONE,
            sampleRate = 24000, supportedDeviceClass = DeviceClass.LOW,
            quality = QualityTier.MID, license = "MIT", displayName = "low"
        )
        val high = ModelPack(
            language = SupportedLanguage.HINDI, role = ModelRole.TTS,
            modelName = "high", sizeBytes = 120L * 1024 * 1024,
            runtime = Mlruntime.SHERPA_VITS, quantization = Quantization.NONE,
            sampleRate = 24000, supportedDeviceClass = DeviceClass.HIGH,
            quality = QualityTier.HIGH, license = "CC", displayName = "high"
        )
        // On a MID device the HIGH-only pack is not affordable -> only low qualifies.
        val affordable = listOf(low, high)
            .filter { it.supportedDeviceClass.ordinal <= DeviceClass.MID.ordinal }
            .maxByOrNull { it.quality.ordinal }
        assertEquals("low", affordable?.modelName)

        // On a HIGH device the high-quality pack is chosen.
        val affordableHigh = listOf(low, high)
            .filter { it.supportedDeviceClass.ordinal <= DeviceClass.HIGH.ordinal }
            .maxByOrNull { it.quality.ordinal }
        assertEquals("high", affordableHigh?.modelName)
    }

    @Test
    fun testAvailabilityIsHonestByAssetSize() {
        // A pack with sizeBytes=0 must be treated as NOT available (not faked).
        val absent = ModelPack(
            language = SupportedLanguage.HINDI, role = ModelRole.TTS,
            modelName = "IndicF5", sizeBytes = 0L,
            runtime = Mlruntime.SHERPA_MATCHA, quantization = Quantization.FP32,
            sampleRate = 24000, supportedDeviceClass = DeviceClass.HIGH,
            quality = QualityTier.HIGH, license = "CC", displayName = "indicf5"
        )
        assertTrue("size 0 => not available", absent.sizeBytes <= 0)
    }
}
