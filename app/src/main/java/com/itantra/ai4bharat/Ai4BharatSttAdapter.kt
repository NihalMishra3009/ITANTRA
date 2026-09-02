package com.itantra.ai4bharat

import com.itantra.stt.SttResult

/**
 * Standardized Adapter Interface for AI4Bharat Speech-to-Text Engines.
 */
interface Ai4BharatSttAdapter {
    fun initialize(languageCode: String): Boolean
    fun transcribe(audioChunk: FloatArray, languageCode: String = ""): SttResult
    fun isModelLoaded(): Boolean
    fun release()
}
