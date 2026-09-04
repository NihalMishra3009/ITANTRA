package com.itantra.speech

import com.itantra.tts.TtsEngine
import com.itantra.tts.TtsResult

/** TTS backend interface — IndicF5 / lightweight VITS / existing VITS all implement it. */
interface TtsBackend {
    fun initialize(langCode: String)
    fun synthesize(text: String, langCode: String, isAlert: Boolean): TtsResult
    fun isLoaded(): Boolean
    fun release()
}

/** Default backend wrapping the existing sherpa-onnx VITS engine. */
class VitsTtsBackend(private val engine: TtsEngine?) : TtsBackend {
    override fun initialize(langCode: String) { engine?.initialize(langCode) }
    override fun synthesize(text: String, langCode: String, isAlert: Boolean): TtsResult =
        engine?.synthesize(text, langCode, isAlert) ?: TtsResult(ShortArray(0), 24000, 0, langCode)
    override fun isLoaded(): Boolean = engine?.isModelLoaded() == true
    override fun release() { engine?.release() }
}

/**
 * TTS manager. The active backend can be swapped per model-selection decisions.
 * Never returns fake audio: if no real model is loaded, synthesize returns an
 * empty buffer so the pipeline surfaces "TTS unavailable" instead of silent/beep.
 */
class TTSModelManager(
    backend: TtsBackend,
    private val fallback: TtsBackend? = null
) {
    var active: TtsBackend = backend

    fun synthesize(text: String, langCode: String, isAlert: Boolean): TtsResult {
        val first = active.synthesize(text, langCode, isAlert)
        if (first.pcmAudio.isNotEmpty() || fallback == null) return first
        // Active backend failed -> try fallback (e.g. lightweight model).
        return fallback.synthesize(text, langCode, isAlert)
    }

    fun release() {
        active.release()
        fallback?.release()
    }
}
