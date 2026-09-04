package com.itantra.speech

import com.itantra.stt.SupportedLanguage

/**
 * Lifecycle status of a model pack. Reflected from ACTUAL filesystem/model state —
 * never inferred from a UI boolean.
 *
 *   NOT_INSTALLED   -> DOWNLOADING -> VERIFYING -> INSTALLED -> LOADING -> LOADED
 *   DOWNLOADING/VERIFYING -> FAILED / CORRUPTED (on error)
 *   INSTALLED       -> UPDATE_AVAILABLE (when a newer version exists)
 */
enum class PackStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    LOADING,
    LOADED,
    FAILED,
    CORRUPTED,
    UPDATE_AVAILABLE
}

/**
 * Metadata for one installable model pack (STT or TTS for a language).
 *
 * This is a *catalog/physical* description:
 *  - id: stable pack id, e.g. "stt_hi", "tts_hi"
 *  - downloadUrl: optional remote source (only used for explicit user install)
 *  - sizeBytes: the REAL artifact size (measured / known from the actual checkpoint)
 *  - isMultilingualShared: true when this language's pack is the shared multilingual
 *    checkpoint (e.g. IndicConformer/IndicF5). When true, installing one language
 *    installs the shared weights — we do NOT claim per-language separation.
 */
data class LanguageModelPack(
    val id: String,
    val language: SupportedLanguage,
    val role: ModelRole,
    val modelName: String,           // e.g. "IndicConformer-600m-multilingual", "IndicF5"
    val version: String,
    val sizeBytes: Long,             // real size (from HF model card / file sizes)
    val checksumSha256: String,      // expected checksum for integrity
    val license: String,             // documented license (MIT for both AI4Bharat models)
    val runtime: Mlruntime,
    val quantization: Quantization,
    val sampleRate: Int,
    val supportedDeviceClass: DeviceClass,
    val downloadUrl: String?,        // null when not offered for download
    val isArchive: Boolean = false,  // true when downloadUrl is a .tar.bz2 containing model.onnx+tokens.txt
    val isMultilingualShared: Boolean, // true = shared checkpoint, not per-language
    val supportsLanguage: Boolean,   // whether this model genuinely supports the language
    val notes: String
) {
    val sizeMb: Double get() = sizeBytes / (1024.0 * 1024.0)
}

/** Maps the 10 required iTantra languages. */
val ALL_LANGUAGES: List<SupportedLanguage> = SupportedLanguage.values().toList()