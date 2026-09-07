package com.itantra.speech

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Manages the app's model storage directory layout, existence, size, deletion,
 * checksum, and atomic installation. Centralizes all path logic so paths are not
 * hard-coded throughout the application.
 *
 * Layout:
 *   {filesDir}/models/stt/<lang>/      <- per-language STT packs (shared weights for multilingual models)
 *   {filesDir}/models/tts/<lang>/      <- per-language TTS packs
 *   {filesDir}/models/vad/             <- VAD assets
 */
class ModelStorageManager(private val context: Context) {

    val modelsDir: File = File(context.filesDir, MODELS_DIR)

    fun sttDir(lang: String): File = File(modelsDir, "stt/${lang.lowercase()}")
    fun ttsDir(lang: String): File = File(modelsDir, "tts/${lang.lowercase()}")
    fun translationDir(pairKey: String): File = File(modelsDir, "translation/${pairKey.lowercase()}")
    fun roleDir(role: ModelRole, lang: String): File =
        if (role == ModelRole.STT) sttDir(lang)
        else if (role == ModelRole.TRANSLATION) translationDir(lang)
        else ttsDir(lang)

    fun tmpDir(role: ModelRole, lang: String): File = File(roleDir(role, lang), TMP_DIR)

    /** Staging directory for an in-flight install (never visible as installed). */
    fun stagingDir(role: ModelRole, lang: String): File =
        File(File(modelsDir, STAGING_DIR), "${roleSubdir(role)}/${lang.lowercase()}")

    private fun roleSubdir(role: ModelRole): String = when (role) {
        ModelRole.STT -> "stt"
        ModelRole.TRANSLATION -> "translation"
        else -> "tts"
    }

    /**
     * True iff this staged/installed pack passes REQUIRED-file validation for its
     * role. A TTS voice needs model.onnx + tokens.txt; an STT engine needs at least
     * one .onnx + tokens.txt. Never reports partial/missing packs as installed.
     */
    fun isCompletePack(dir: File, role: ModelRole): Boolean =
        if (role == ModelRole.TRANSLATION) isCompleteTranslationPack(dir)
        else Companion.isCompletePackFiles(dir)

    /** Translation packs need encoder + decoder ONNX + config + SP model/vocab. */
    private fun isCompleteTranslationPack(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val hasEnc = File(dir, "encoder_model.onnx").exists()
        val hasDec = File(dir, "decoder_model.onnx").exists()
        val hasCfg = File(dir, "config.json").exists()
        val hasTok = File(File(dir, "tokenizer"), "sentencepiece.model").exists()
        return hasEnc && hasDec && hasCfg && hasTok
    }

    companion object {
        private const val MODELS_DIR = "models"

        /** Directory when a model is mid-download/verify (atomic, not visible as installed). */
        const val TMP_DIR = ".tmp"

        /** Staging root for atomic installs — never visible as an installed pack. */
        const val STAGING_DIR = ".staging"

        /** Required artifact inside a TTS pack. */
        const val TTS_MODEL_FILE = "model.onnx"

        /** Required artifact inside a TTS pack. */
        const val TTS_TOKENS_FILE = "tokens.txt"

        /** Version file inside an installed pack directory. */
        const val VERSION_FILE = "version.txt"

        /** Manif stores a checksum sidecar for integrity re-verification after install. */
        const val CHECKSUM_FILE = "checksum.sha256"

        /** Pure (Context-free) required-file validation — unit-testable in JVM. */
        fun isCompletePackFiles(dir: File): Boolean {
            if (!dir.isDirectory) return false
            val files = dir.listFiles { f -> f.isFile }?.toList() ?: return false
            if (files.isEmpty()) return false
            val hasTokens = files.any { it.name == TTS_TOKENS_FILE || it.name.equals("tokens.txt", true) }
            val hasOnnx = files.any { it.name.endsWith(".onnx", ignoreCase = true) }
            return hasTokens && hasOnnx
        }
    }

    /** True if the pack directory exists AND contains the required model files. */
    fun isInstalled(role: ModelRole, lang: String): Boolean =
        isCompletePack(roleDir(role, lang), role)

    /** All real model files in an installed pack. */
    fun modelFiles(role: ModelRole, lang: String): List<File> {
        val dir = roleDir(role, lang)
        return dir.listFiles { f ->
            f.isFile && f.name != TMP_DIR && f.name != VERSION_FILE && f.name != CHECKSUM_FILE
        }?.toList() ?: emptyList()
    }

    /** Measured total size (bytes) of an installed pack from actual files. */
    fun sizeBytes(role: ModelRole, lang: String): Long =
        modelFiles(role, lang).sumOf { it.length() }

    /** Every installed STT language + its measured size (bytes). */
    fun installedStt(): Map<String, Long> = installedLanguages(ModelRole.STT)
    /** Every installed TTS language + its measured size (bytes). */
    fun installedTts(): Map<String, Long> = installedLanguages(ModelRole.TTS)
    /** Every installed translation pair + its measured size (bytes). */
    fun installedTranslation(): Map<String, Long> {
        val base = File(modelsDir, "translation")
        if (!base.exists()) return emptyMap()
        return base.listFiles()
            ?.filter { it.isDirectory && isInstalled(ModelRole.TRANSLATION, it.name) }
            ?.associate { it.name to sizeBytes(ModelRole.TRANSLATION, it.name) } ?: emptyMap()
    }

    private fun installedLanguages(role: ModelRole): Map<String, Long> {
        val base = if (role == ModelRole.STT) File(modelsDir, "stt") else File(modelsDir, "tts")
        if (!base.exists()) return emptyMap()
        return base.listFiles()
            ?.filter { it.isDirectory && isInstalled(role, it.name) }
            ?.associate { it.name to sizeBytes(role, it.name) } ?: emptyMap()
    }

    /** Total installed model storage across all roles (bytes). */
    fun totalInstalledBytes(): Long =
        installedStt().values.sum() + installedTts().values.sum() + installedTranslation().values.sum()

    /** Delete a single STT or TTS pack. Returns true if removed. */
    fun deletePack(role: ModelRole, lang: String): Boolean {
        return try {
            roleDir(role, lang).deleteRecursively()
        } catch (e: Exception) {
            false
        }
    }

    /** Version stored alongside an installed pack (or "0" if none). */
    fun installedVersion(role: ModelRole, lang: String): String {
        val f = File(roleDir(role, lang), VERSION_FILE)
        return if (f.exists()) f.readText().trim() else "0"
    }

    /** Record the installed version + checksum after a successful atomic install. */
    fun writeInstalledMetadata(role: ModelRole, lang: String, version: String, sha256: String) {
        val dir = roleDir(role, lang)
        dir.mkdirs()
        File(dir, VERSION_FILE).writeText(version)
        File(dir, CHECKSUM_FILE).writeText(sha256)
    }

    /** Read back the recorded checksum sidecar (empty if none). */
    fun recordedChecksum(role: ModelRole, lang: String): String {
        val f = File(roleDir(role, lang), CHECKSUM_FILE)
        return if (f.exists()) f.readText().trim() else ""
    }

    /** Compute SHA-256 of a file (streamed). */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Clean any leftover temp/staging dirs (crashed/download cancelled). */
    fun purgeTempDirs() {
        File(modelsDir, "stt").listFiles()?.forEach { dir -> File(dir, TMP_DIR).deleteRecursively() }
        File(modelsDir, "tts").listFiles()?.forEach { dir -> File(dir, TMP_DIR).deleteRecursively() }
        // Staging is never installed state — safe to discard on startup.
        File(modelsDir, STAGING_DIR).deleteRecursively()
    }
}
