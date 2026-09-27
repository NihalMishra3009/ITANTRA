package com.itantra.stt

/**
 * Supported Language definitions and metadata for iTantra.
 */
enum class SupportedLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val phase: Int
) {
    HINDI("hi", "Hindi", "हिन्दी", 1),
    ENGLISH("en", "English", "English", 1),
    GUJARATI("gu", "Gujarati", "ગુજરાતી", 2),
    MARATHI("mr", "Marathi", "मराठी", 2),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ", 2),
    MALAYALAM("ml", "Malayalam", "മലയാളം", 3),
    TAMIL("ta", "Tamil", "தமிழ்", 3),
    TELUGU("te", "Telugu", "తెలుగు", 3),
    ODIA("or", "Odia", "ଓଡ଼ିଆ", 4),
    BENGALI("bn", "Bengali", "বাংলা", 4);

    companion object {
        fun fromCode(code: String): SupportedLanguage {
            return values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: HINDI
        }
    }
}

data class SttResult(
    val text: String,
    val languageCode: String,
    val durationMs: Long,
    val confidence: Float = 0f,
    /**
     * The language the audio was actually acoustically detected as, via sherpa-onnx's
     * Whisper-based SpokenLanguageIdentification — independent of [languageCode], which is
     * merely what the sender's UI was set to (and what Whisper was forced to decode as).
     * Falls back to [languageCode] when detection is unavailable or inconclusive, so callers
     * that only care about "the real spoken language" can always trust this field, and callers
     * that specifically want "what Whisper was asked to decode" keep using [languageCode].
     */
    val detectedLanguageCode: String = languageCode
)
