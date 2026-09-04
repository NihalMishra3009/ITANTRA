package com.itantra.speech

import com.itantra.vad.VadEngine
import com.itantra.vad.VadEvent

/** VAD backend — neural Silero when compatible, energy fallback otherwise. */
interface VadBackend {
    fun processChunk(chunk: FloatArray): VadEvent
    fun reset()
    fun release()
}

/** Wraps the existing VadEngine (energy detection active; Silero when compatible). */
class EngineVadBackend(private val engine: VadEngine?) : VadBackend {
    override fun processChunk(chunk: FloatArray): VadEvent =
        engine?.processChunk(chunk) ?: VadEvent.SILENCE
    override fun reset() { engine?.reset() }
    override fun release() { engine?.release() }
}

/** VAD manager — voice activity detection + endpointing entry point. */
class VADModelManager(private val backend: VadBackend = EngineVadBackend(null)) {
    var active: VadBackend = backend
    fun processChunk(chunk: FloatArray) = active.processChunk(chunk)
    fun reset() = active.reset()
    fun release() = active.release()
}
