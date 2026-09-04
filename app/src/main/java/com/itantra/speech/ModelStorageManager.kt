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

    companion object {
        private const val MODELS_DIR = "models"

        /** Directory when a model is mid-download/verify (atomic, not visible as installed). */
        const val TMP_DIR = ".tmp"

        /** Version file inside an installed pack directory. */
        const val VERSION_FILE = "version.txt"

        /** Manif stores a checksum sidecar for integrity re-verification after install. */
        const val CHECKSUM_FILE = "checksum.sha256"
    }

    val modelsDir: File = File(context.filesDir, MODELS_DIR)

    fun sttDir(lang: String): File = File(modelsDir, "stt/${lang.lowercase()}")
    fun ttsDir(lang: String): File = File(modelsDir, "tts/${lang.lowercase()}")
    fun roleDir(role: ModelRole, lang: String): File =
        if (role == ModelRole.STT) sttDir(lang) else ttsDir(lang)

    fun tmpDir(role: ModelRole, lang: String): File = File(roleDir(role, lang), TMP_DIR)

    /** True if the pack directory exists and contains at least one real model file. */
    fun isInstalled(role: ModelRole, lang: String): Boolean {
        val dir = roleDir(role, lang)
        return dir.exists() && (dir.listFiles { f -> f.isFile && f.name != TMP_DIR && f.name != VERSION_FILE && f.name != CHECKSUM_FILE }?.isNotEmpty() ?: false)
    }

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

    private fun installedLanguages(role: ModelRole): Map<String, Long> {
        val base = if (role == ModelRole.STT) File(modelsDir, "stt") else File(modelsDir, "tts")
        if (!base.exists()) return emptyMap()
        return base.listFiles()
            ?.filter { it.isDirectory && isInstalled(role, it.name) }
            ?.associate { it.name to sizeBytes(role, it.name) } ?: emptyMap()
    }

    /** Total installed model storage across all roles (bytes). */
    fun totalInstalledBytes(): Long = installedStt().values.sum() + installedTts().values.sum()

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

    /** Clean any leftover temp dirs (crashed/download cancelled). */
    fun purgeTempDirs() {
        File(modelsDir, "stt").listFiles()?.forEach { dir -> File(dir, TMP_DIR).deleteRecursively() }
        File(modelsDir, "tts").listFiles()?.forEach { dir -> File(dir, TMP_DIR).deleteRecursively() }
    }
}
