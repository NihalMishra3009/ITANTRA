package com.itantra.speech

import com.itantra.stt.SupportedLanguage

/** Runtime backend used to execute a model. */
enum class Mlruntime {
    SHERPA_WHISPER,   // existing Whisper STT via sherpa-onnx
    SHERPA_PREFIX,    // Paraformer-family (IndicConformer) via sherpa-onnx — placeholder when weights absent
    SHERPA_VITS,      // existing VITS TTS via sherpa-onnx
    SHERPA_KOKORO,    // lightweight Kokoro TTS (placeholder)
    SHERPA_MATCHA,    // Matcha TTS (placeholder)
    ENERGY            // energy/fallback (non-ML), e.g. VAD fallback
}

/** Quantization/precision tier of a model asset. */
enum class Quantization {
    INT8, FP32, FP16, NONE
}

/** Quality tier — used by language-aware selection and quality-safety thresholds. */
enum class QualityTier {
    LOW, MID, HIGH
}

/**
 * Metadata for one model pack (a deployable ML asset for a specific language + role).
 * Fields describe the ARTIFACT, not assumed capability. `available` is only true when
 * the referenced asset actually exists on device.
 */
data class ModelPack(
    val language: SupportedLanguage,
    val role: ModelRole,            // STT / TTS / VAD
    val modelName: String,          // e.g. "IndicConformer-INT8", "vits_bn"
    val sizeBytes: Long,
    val runtime: Mlruntime,
    val quantization: Quantization,
    val sampleRate: Int,
    val supportedDeviceClass: DeviceClass,
    val quality: QualityTier,
    val license: String,
    val displayName: String
) {
    val sizeMb: Double get() = sizeBytes / (1024.0 * 1024.0)
}

enum class ModelRole { STT, TTS, VAD }

/**
 * Language-aware model pack registry.
 *
 * Determines ACTUAL availability by checking real asset files on disk — it never
 * reports a model as available when the asset is missing (the SIH requirement).
 * Also gives the candidate model (IndicConformer / IndicF5 / lightweight VITS /
 * existing VITS) per language, so the selection engine can pick the best one present.
 */
class ModelPackRegistry(
    private val context: android.content.Context,
    private val distribution: ModelDistributionManager? = null
) {

    private val assetExists: (String) -> Boolean = { path ->
        try { context.assets.open(path).use { it.available() >= 0 } } catch (e: Exception) { false }
    }

    /** Asset size in bytes, or 0 if missing (so availability is never faked). */
    private fun assetSize(path: String): Long = try {
        context.assets.open(path).use { it.available().toLong() }
    } catch (e: Exception) { 0L }

    /** Whisper STT covers all 10 languages (single model). */
    val whisperSttStaysBytes: Long = 152L * 1024 * 1024

    /** True availability for the hand-rolled / existing models. */
    private val packs: List<ModelPack> = buildList {
        // ---- STT: existing Whisper multilingual (all 10 langs) ----
        for (lang in SupportedLanguage.values()) {
            val available = assetExists("models/stt/whisper-base-encoder.int8.onnx") &&
                    assetExists("models/stt/whisper-base-decoder.int8.onnx")
            add(
                ModelPack(
                    language = lang,
                    role = ModelRole.STT,
                    modelName = "Whisper-base-int8",
                    sizeBytes = if (available) whisperSttStaysBytes else 0,
                    runtime = Mlruntime.SHERPA_WHISPER,
                    quantization = Quantization.INT8,
                    sampleRate = 16000,
                    supportedDeviceClass = DeviceClass.MID,
                    quality = QualityTier.MID,
                    license = "MIT (Whisper)",
                    displayName = "Whisper base int8 (existing)"
                )
            )
            // Primary STT candidate (user-confirmed): IndicConformer, INT8-optimized
            // for mobile. Only reported available when its ONNX asset is present.
            add(
                ModelPack(
                    language = lang,
                    role = ModelRole.STT,
                    modelName = "IndicConformer-INT8",
                    sizeBytes = assetSize("models/stt/indic_conformer_${lang.code}/model.onnx"),
                    runtime = Mlruntime.SHERPA_PREFIX,
                    quantization = Quantization.INT8,
                    sampleRate = 16000,
                    supportedDeviceClass = DeviceClass.MID,
                    quality = QualityTier.HIGH,
                    license = "CC-BY-NC (verify)",
                    displayName = "IndicConformer INT8 (primary, mobile-optimized)"
                )
            )
        }
        // ---- TTS: existing VITS per-language; only present langs are available ----
        for (lang in SupportedLanguage.values()) {
            val dir = "models/tts/vits_${lang.code}"
            val modelOk = assetExists("$dir/model.onnx") && assetExists("$dir/tokens.txt")
            val size = if (modelOk) assetSize("$dir/model.onnx") else 0L
            add(
                ModelPack(
                    language = lang,
                    role = ModelRole.TTS,
                    modelName = "VITS-${lang.code}",
                    sizeBytes = size,
                    runtime = Mlruntime.SHERPA_VITS,
                    quantization = Quantization.NONE,
                    sampleRate = 24000,
                    supportedDeviceClass = DeviceClass.MID,
                    quality = QualityTier.MID,
                    license = "MIT (VITS)",
                    displayName = if (modelOk) "VITS ${lang.displayName} (existing)" else "VITS ${lang.displayName} (NOT INSTALLED)"
                )
            )
            // Primary high-quality TTS candidate (user-confirmed): IndicF5, INT8/mobile
            // optimized. Only available when its ONNX asset is present.
            add(
                ModelPack(
                    language = lang,
                    role = ModelRole.TTS,
                    modelName = "IndicF5-INT8",
                    sizeBytes = assetSize("models/tts/indicf5_${lang.code}/model.onnx"),
                    runtime = Mlruntime.SHERPA_MATCHA, // indicative backend; INT8-optimized when weights present
                    quantization = Quantization.INT8,
                    sampleRate = 24000,
                    supportedDeviceClass = DeviceClass.HIGH,
                    quality = QualityTier.HIGH,
                    license = "CC-BY-NC (verify)",
                    displayName = "IndicF5 INT8 (high-quality, mobile-optimized)"
                )
            )
        }
        // ---- VAD ----
        val sileroOk = assetExists("models/vad/silero_vad.onnx")
        val silentFsSize = if (sileroOk) assetSize("models/vad/silero_vad.onnx") else 0L
        add(
            ModelPack(
                language = SupportedLanguage.HINDI,
                role = ModelRole.VAD,
                modelName = "Silero-VAD",
                sizeBytes = silentFsSize,
                runtime = Mlruntime.ENERGY, // actually energy fallback runs; Silero asset v4 incompatible
                quantization = Quantization.NONE,
                sampleRate = 16000,
                supportedDeviceClass = DeviceClass.MID,
                quality = QualityTier.MID,
                license = "MIT (Silero)",
                displayName = "Silero VAD (energy fallback active)"
            )
        )
    }

    /** All packs for a role/language. */
    fun forRole(role: ModelRole): List<ModelPack> = packs.filter { it.role == role }

    /**
     * Packs for a language+role, counting BOTH bundled assets and downloaded
     * (install-once) packs stored in app-private storage — availability is never
     * faked; a pack is only present when its file actually exists.
     */
    fun forLanguage(lang: String, role: ModelRole): List<ModelPack> {
        val code = lang.lowercase()
        val bundled = packs.filter { it.language.code == code && it.role == role }
        val downloaded = distribution?.installedModels(code, role)?.let { files ->
            if (files.isEmpty()) emptyList() else {
                val total = files.sumOf { it.length() }
                val isStt = role == ModelRole.STT
                listOf(
                    ModelPack(
                        language = SupportedLanguage.fromCode(code),
                        role = role,
                        // Confirmed primary targets: IndicConformer (STT) / IndicF5 (TTS)
                        modelName = if (isStt) "IndicConformer-INT8" else "IndicF5-INT8",
                        sizeBytes = total,
                        runtime = if (isStt) Mlruntime.SHERPA_PREFIX else Mlruntime.SHERPA_MATCHA,
                        quantization = Quantization.INT8,
                        sampleRate = if (isStt) 16000 else 24000,
                        supportedDeviceClass = if (isStt) DeviceClass.MID else DeviceClass.HIGH,
                        quality = QualityTier.HIGH,
                        license = "user-installed (verify)",
                        displayName = "${SupportedLanguage.fromCode(code).displayName} " +
                                "${if (isStt) "IndicConformer INT8" else "IndicF5 INT8"} (installed)"
                    )
                )
            }
        } ?: emptyList()
        return bundled + downloaded
    }

    /** True if ANY pack for this language+role is actually available (bundled or downloaded). */
    fun isAvailable(lang: String, role: ModelRole): Boolean {
        return forLanguage(lang, role).any { it.sizeBytes > 0 }
    }

    /** Best available pack (highest quality the device class can afford). */
    fun bestAvailable(lang: String, role: ModelRole, deviceClass: DeviceClass): ModelPack? {
        return forLanguage(lang, role)
            .filter { it.sizeBytes > 0 && it.supportedDeviceClass.ordinal <= deviceClass.ordinal }
            .maxByOrNull { it.quality.ordinal }
    }

    /** Summary of STT/TTS availability per language for the diagnostics UI. */
    fun capabilitySummary(): String {
        val sb = StringBuilder()
        for (lang in SupportedLanguage.values()) {
            val stt = if (isAvailable(lang.code, ModelRole.STT)) "STT ✓" else "STT ✗"
            val tts = if (isAvailable(lang.code, ModelRole.TTS)) "TTS ✓" else "TTS ✗"
            sb.appendLine("${lang.displayName.padEnd(12)} $stt  $tts")
        }
        return sb.toString()
    }
}
