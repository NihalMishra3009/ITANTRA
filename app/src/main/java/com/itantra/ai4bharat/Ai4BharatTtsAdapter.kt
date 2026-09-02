package com.itantra.ai4bharat

import com.itantra.tts.TtsResult

/**
 * Standardized Adapter Interface for AI4Bharat Text-to-Speech Engines.
 */
interface Ai4BharatTtsAdapter {
    fun initialize(languageCode: String): Boolean
    fun synthesize(text: String, languageCode: String = "", isAlert: Boolean = false): TtsResult
    fun isModelLoaded(): Boolean
    fun release()
}
