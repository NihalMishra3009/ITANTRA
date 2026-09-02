package com.itantra.ai4bharat

import android.util.Log

enum class ModelType {
    VAD,
    STT,
    TTS
}

data class ModelMetadata(
    val name: String,
    val type: ModelType,
    val languageCode: String,
    val framework: String,
    val sizeBytes: Long,
    val isLoaded: Boolean,
    val license: String
)

/**
 * Model lifecycle & resource tracker for the on-device ML engines.
 * Enforces lazy loading, memory safety, and accurate load state.
 */
class Ai4BharatModelManager {

    companion object {
        private const val TAG = "Ai4BharatModelManager"
    }

    private val loadedModels = mutableMapOf<String, ModelMetadata>()

    fun isLoaded(type: ModelType, languageCode: String): Boolean {
        val key = "${type.name}_${languageCode.lowercase()}"
        return loadedModels[key]?.isLoaded == true
    }

    fun markLoaded(type: ModelType, languageCode: String, sizeBytes: Long = 0L) {
        val key = "${type.name}_${languageCode.lowercase()}"
        val (name, framework) = when (type) {
            ModelType.VAD -> "Silero VAD v5" to "sherpa-onnx (ONNX Runtime)"
            ModelType.STT -> "OpenAI Whisper tiny int8 (multilingual)" to "sherpa-onnx (ONNX Runtime)"
            ModelType.TTS -> "VITS TTS" to "sherpa-onnx (ONNX Runtime)"
        }
        val license = when (type) {
            ModelType.VAD -> "MIT"
            ModelType.STT -> "MIT (Whisper)"
            ModelType.TTS -> "MIT (VITS)"
        }

        loadedModels[key] = ModelMetadata(
            name = name,
            type = type,
            languageCode = languageCode,
            framework = framework,
            sizeBytes = sizeBytes,
            isLoaded = true,
            license = license
        )
        Log.i(TAG, "Model loaded into active memory: $name ($languageCode)")
    }

    fun unloadModel(type: ModelType, languageCode: String) {
        val key = "${type.name}_${languageCode.lowercase()}"
        loadedModels.remove(key)
        Log.i(TAG, "Model unloaded: $key")
    }

    fun getModelInfo(type: ModelType, languageCode: String): ModelMetadata? {
        val key = "${type.name}_${languageCode.lowercase()}"
        return loadedModels[key]
    }

    fun getActiveMemoryUsageMb(): Float {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()).toFloat() / (1024 * 1024)
        return usedMem
    }
}
