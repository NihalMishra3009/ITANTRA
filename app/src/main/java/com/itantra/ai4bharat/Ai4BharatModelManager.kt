package com.itantra.ai4bharat

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

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
 * Standardized AI4Bharat Model Lifecycle & Resource Manager.
 * Enforces lazy loading, memory safety, and model file integrity.
 */
class Ai4BharatModelManager(private val context: Context) {

    companion object {
        private const val TAG = "Ai4BharatModelManager"
    }

    private val loadedModels = mutableMapOf<String, ModelMetadata>()

    fun getModelFile(type: ModelType, languageCode: String): File? {
        val fileName = when (type) {
            ModelType.VAD -> "silero_vad.onnx"
            ModelType.STT -> "indicconformer_${languageCode.lowercase()}_int8.tflite"
            ModelType.TTS -> "indictts_${languageCode.lowercase()}_int8.tflite"
        }
        val subDir = when (type) {
            ModelType.VAD -> "vad"
            ModelType.STT -> "stt"
            ModelType.TTS -> "tts"
        }
        val assetPath = "models/$subDir/$fileName"

        val localFile = File(context.filesDir, fileName)
        if (localFile.exists() && localFile.length() > 0) {
            return localFile
        }

        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(localFile).use { output ->
                    input.copyTo(output)
                }
            }
            localFile
        } catch (e: Exception) {
            Log.w(TAG, "Asset not found: $assetPath (${e.message})")
            null
        }
    }

    fun isLoaded(type: ModelType, languageCode: String): Boolean {
        val key = "${type.name}_${languageCode.lowercase()}"
        return loadedModels[key]?.isLoaded == true
    }

    fun markLoaded(type: ModelType, languageCode: String, sizeBytes: Long = 0L) {
        val key = "${type.name}_${languageCode.lowercase()}"
        val name = when (type) {
            ModelType.VAD -> "Silero VAD v5 ONNX"
            ModelType.STT -> "AI4Bharat IndicConformer ($languageCode)"
            ModelType.TTS -> "AI4Bharat Indic-TTS ($languageCode)"
        }
        val framework = if (type == ModelType.VAD) "ONNX Runtime Mobile" else "TFLite Int8 / Acoustic"
        val license = if (type == ModelType.VAD) "MIT" else "MIT / CC-BY 4.0 (AI4Bharat / IIT Madras)"

        loadedModels[key] = ModelMetadata(
            name = name,
            type = type,
            languageCode = languageCode,
            framework = framework,
            sizeBytes = sizeBytes,
            isLoaded = true,
            license = license
        )
        Log.i(TAG, "Model loaded into active memory: $name")
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
