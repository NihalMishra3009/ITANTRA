package com.itantra.ai4bharat

import com.itantra.tts.TtsResult

/**
 * Standardized Adapter Interface for AI4Bharat Text-to-Speech Engines.
 */
interface Ai4BharatTtsAdapter {
    fun initialize(languageCode: String): Boolean
    fun synthesize(text: String, languageCode: String = "", isAlert: Boolean = false): TtsResult
    fun isModelLoaded(): Boolean
    /** Language code the engine is currently initialized for ("" when none). */
    fun currentLanguageCode(): String = ""
    /** True when the engine is initialized and loaded for this exact language. */
    fun isLoadedFor(languageCode: String): Boolean = isModelLoaded()
    fun release()
}
