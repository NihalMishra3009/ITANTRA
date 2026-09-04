package com.itantra.speech

import com.itantra.stt.SttEngine
import com.itantra.stt.SttResult
import com.itantra.stt.SupportedLanguage

/**
 * STT manager. Concrete sub-managers (e.g. an IndicConformer backend) implement
 * [SttBackend]; SpeechModelManager dispatches to the active backend. Today the
 * active backend is the existing Whisper engine, preserved as the offline STT.
 */
interface SttBackend {
    fun initialize(lang: SupportedLanguage)
    fun transcribe(audioChunk: FloatArray, langCode: String): SttResult
    fun isLoaded(): Boolean
    fun release()
}

/** Default backend wrapping the existing sherpa-onnx Whisper engine. */
class WhisperSttBackend(private val engine: SttEngine?) : SttBackend {
    override fun initialize(lang: SupportedLanguage) { engine?.initialize(lang.code) }
    override fun transcribe(audioChunk: FloatArray, langCode: String): SttResult =
        engine?.transcribe(audioChunk, langCode) ?: SttResult("", langCode, 0)
    override fun isLoaded(): Boolean = engine?.isModelLoaded() == true
    override fun release() { engine?.release() }
}

class STTModelManager(
    backend: SttBackend
) {
    var active: SttBackend = backend
    fun transcribe(audio: FloatArray, lang: String) = active.transcribe(audio, lang)
    fun release() { active.release() }
}
