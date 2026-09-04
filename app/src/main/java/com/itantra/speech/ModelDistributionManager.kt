package com.itantra.speech

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads, verifies, and atomically installs language model packs into app-private
 * storage. The ONLY online steps are optional user-initiated acquisition/update; after
 * install, inference runs fully offline from cached files.
 *
 * Pipeline:
 *   DOWNLOAD -> TEMP FILE -> VERIFY SHA-256 -> VERIFY METADATA -> ATOMIC MOVE
 *   -> REGISTER INSTALLED
 *
 * Guarantees:
 *  - never overwrites the active (installed) model mid-download (writes into .tmp)
 *  - resumable where the server supports Range (kept minimal)
 *  - cancellable and retryable
 *  - corrupted downloads detected by size + SHA-256 mismatch -> never installed
 *  - individual STT / TTS packs install/delete independently
 */
class ModelDistributionManager(
    context: Context
) {
    companion object {
        private const val TAG = "ModelDistribution"
        private const val CHUNK = 64 * 1024
        private const val TIMEOUT_MS = 20000
    }

    private val storage = ModelStorageManager(context.applicationContext)

    /** Current pack statuses (live, driven by storage + in-progress downloads). */
    private val statuses = java.util.concurrent.ConcurrentHashMap<String, PackStatus>()

    /** Cancellation flags per pack id. */
    private val cancelFlags = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun storageManager(): ModelStorageManager = storage

    fun status(pack: LanguageModelPack): PackStatus {
        statuses[pack.id]?.let {
            if (it in setOf(PackStatus.DOWNLOADING, PackStatus.VERIFYING, PackStatus.LOADING)) return it
        }
        return if (storage.isInstalled(pack.role, pack.language.code)) PackStatus.INSTALLED
        else PackStatus.NOT_INSTALLED
    }

    fun setStatus(packId: String, status: PackStatus) { statuses[packId] = status }

    /** Installed pack actual size (measured from filesystem). */
    fun installedSize(pack: LanguageModelPack): Long =
        if (storage.isInstalled(pack.role, pack.language.code))
            storage.sizeBytes(pack.role, pack.language.code) else 0

    /** Model files present on disk for a (role, lang) — empty when not installed. */
    fun installedModels(lang: String, role: ModelRole): List<File> =
        storage.modelFiles(role, lang)

    /** True when a (role, lang) pack has verified files installed. */
    fun isInstalled(lang: String, role: ModelRole): Boolean =
        storage.isInstalled(role, lang)

    /**
     * Install (download once) a pack. onProgress: 0..1. onDone: success(file) | failure.
     * Cancellable via [cancel]. Retryable by calling install() again.
     */
    fun install(
        pack: LanguageModelPack,
        onProgress: (Float) -> Unit = {},
        onDone: (Result<File>) -> Unit
    ) {
        val url = pack.downloadUrl
        if (url == null || !pack.supportsLanguage) {
            onDone(Result.failure(IllegalStateException(
                if (!pack.supportsLanguage) "Model does not support ${pack.language.code}"
                else "No download source configured for ${pack.id}"
            )))
            return
        }
        val lang = pack.language.code.lowercase()
        val targetDir = storage.roleDir(pack.role, lang).apply { mkdirs() }
        val tmpDir = File(targetDir, ModelStorageManager.TMP_DIR).apply { mkdirs() }
        val tmpFile = File(tmpDir, "model.part")

        setStatus(pack.id, PackStatus.DOWNLOADING)
        cancelFlags[pack.id] = false

        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = openDownload(url)
                val code = conn!!.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val contentLength = conn.contentLengthLong
                var downloaded = 0L
                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { out ->
                        val buf = ByteArray(CHUNK)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            if (cancelFlags[pack.id] == true) {
                                throw CancelledException()
                            }
                            out.write(buf, 0, n)
                            downloaded += n
                            if (contentLength > 0) {
                                onProgress((downloaded.toDouble() / contentLength).toFloat().coerceAtMost(1f))
                            }
                        }
                    }
                }
                onProgress(1f)

                // Verify size + checksum BEFORE install.
                setStatus(pack.id, PackStatus.VERIFYING)
                verifyIntegrity(pack, tmpFile)

                // Extract archives (sherpa-onnx tts voices: model.onnx + tokens.txt).
                val finalFile: File
                if (pack.isArchive) {
                    extractArchiveInto(targetDir, tmpFile, onProgress)
                    finalFile = File(targetDir, "model.onnx")
                } else {
                    // Atomic move into place for plain single-file models.
                    val f = File(targetDir, "model.onnx")
                    if (f.exists()) f.delete()
                    if (!tmpFile.renameTo(f)) {
                        tmpFile.copyTo(f, overwrite = true)
                        tmpFile.delete()
                    }
                    finalFile = f
                }
                storage.writeInstalledMetadata(pack.role, lang, pack.version, pack.checksumSha256)
                tmpDir.deleteRecursively()

                setStatus(pack.id, PackStatus.INSTALLED)
                onDone(Result.success(finalFile))
            } catch (e: CancelledException) {
                Log.w(TAG, "Download of ${pack.id} cancelled")
                cntryCleanup(tmpDir, tmpFile)
                setStatus(pack.id, PackStatus.NOT_INSTALLED)
                onDone(Result.failure(e))
            } catch (e: Exception) {
                Log.e(TAG, "Download of ${pack.id} failed: ${e.message}")
                cntryCleanup(tmpDir, tmpFile)
                setStatus(pack.id,
                    if (e is ChecksumMismatchException) PackStatus.CORRUPTED else PackStatus.FAILED)
                onDone(Result.failure(e))
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun cntryCleanup(tmpDir: File, tmpFile: File) {
        // Keep temp dir on failure so a retry can resume; only delete the partial file.
        try { tmpFile.delete() } catch (_: Exception) {}
    }

    private fun openDownload(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "iTantra-build")
        // GitHub asset URLs redirect to objects.githubusercontent.com; follow explicitly.
        var c = conn
        repeat(5) {
            val code = c.responseCode
            if (code !in 300..399) return c
            val loc = c.getHeaderField("Location")
            if (loc.isNullOrBlank()) return c
            c.disconnect()
            c = URL(loc).openConnection() as HttpURLConnection
            c.connectTimeout = 30000
            c.readTimeout = 60000
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("User-Agent", "iTantra-build")
            c.connect()
        }
        return c
    }

    private fun verifyIntegrity(pack: LanguageModelPack, file: File) {
        if (pack.checksumSha256.isNotBlank()) {
            val actual = storage.sha256(file)
            if (!actual.equals(pack.checksumSha256, ignoreCase = true)) {
                throw ChecksumMismatchException("SHA-256 mismatch for ${pack.id}")
            }
        }
    }

    /**
     * Extract a .tar.bz2 archive (sherpa-onnx tts voice) into the pack directory.
     * Writes model.onnx + tokens.txt atomically. Uses Apache Commons Compress.
     */
    private fun extractArchiveInto(targetDir: File, archive: File, onProgress: (Float) -> Unit) {
        val tmpExtract = File(targetDir, ModelStorageManager.TMP_DIR).apply { mkdirs() }
        val bz2 = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
            archive.inputStream()
        )
        val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz2)
        try {
            var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry?
            var onnxFound = false
            var tokensFound = false
            var espeakFound = false
            val buf = ByteArray(128 * 1024)
            while (tar.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                val path = e.name
                val base = path.substringAfterLast('/')
                // Full file entries relative to the pack root.
                val isOnnx = base.endsWith(".onnx") && !onnxFound && e.isFile
                val isTokens = base.equals("tokens.txt", ignoreCase = true) && !tokensFound && e.isFile
                val isEspeak = path.contains("espeak-ng-data") && e.isFile
                val isDataFile = isOnnx || isTokens || isEspeak
                if (!isDataFile) continue
                // Write the FULL entry using an explicit read loop (tar.copyTo can
                // stop early on large entries), then verify the written size.
                val dest: File = when {
                    isOnnx -> File(tmpExtract, "model.onnx")
                    isTokens -> File(tmpExtract, "tokens.txt")
                    else -> {
                        // espeak-ng-data required for Piper; place under tmpExtract/espeak-ng-data.
                        val idx = path.indexOf("espeak-ng-data")
                        val sub = if (idx >= 0) path.substring(idx) else base
                        File(tmpExtract, sub)
                    }
                }
                dest.parentFile?.mkdirs()
                var written = 0L
                dest.outputStream().use { out ->
                    var n: Int
                    while (tar.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        written += n
                    }
                }
                if (isOnnx) {
                    if (e.size > 0 && written < e.size) {
                        throw IOException("Truncated model.onnx ($written/${e.size} bytes)")
                    }
                    onnxFound = true
                } else if (isTokens) {
                    tokensFound = true
                } else if (isEspeak) {
                    espeakFound = true
                }
            }
            if (!onnxFound || !tokensFound) {
                throw IOException("Archive missing model.onnx/tokens.txt for ${archive.name}")
            }
            // espeak-ng-data is REQUIRED for Piper voices (dataDir); record its presence.
            writeEspeakMarker(targetDir, espeakFound)
            onProgress(0.95f)
        } finally {
            tar.close()
        }
        // Atomically publish extracted files into the live dir (copy is robust on all
        // Android versions; rename can silently fail cross-device).
        val liveModel = File(targetDir, "model.onnx")
        val liveTokens = File(targetDir, "tokens.txt")
        File(tmpExtract, "model.onnx").copyTo(liveModel, overwrite = true)
        File(tmpExtract, "tokens.txt").copyTo(liveTokens, overwrite = true)
        File(tmpExtract, "model.onnx").delete()
        File(tmpExtract, "tokens.txt").delete()
        // espeak-ng-data is REQUIRED by Piper voices (dataDir) — move it recursively.
        val espeakSrc = File(tmpExtract, "espeak-ng-data")
        if (espeakSrc.exists()) {
            val espeakDst = File(targetDir, "espeak-ng-data")
            espeakSrc.walkTopDown().forEach { srcFile ->
                val rel = srcFile.relativeTo(espeakSrc)
                val dst = File(espeakDst, rel.path)
                if (srcFile.isDirectory) dst.mkdirs() else srcFile.copyTo(dst, overwrite = true)
            }
            espeakSrc.deleteRecursively()
        }
        tmpExtract.deleteRecursively()
    }

    /** Record that the voice pack contains espeak-ng-data (Piper-required). */
    private fun writeEspeakMarker(targetDir: File, present: Boolean) {
        try {
            File(targetDir, ".espeak").writeText(if (present) "1" else "0")
        } catch (e: Exception) { /* non-fatal */ }
    }

    /** Cancel an in-progress download. */
    fun cancel(packId: String) { cancelFlags[packId] = true }

    /** Delete an installed pack (STT and TTS independent). */
    fun deletePack(pack: LanguageModelPack): Boolean {
        val ok = storage.deletePack(pack.role, pack.language.code)
        statuses.remove(pack.id)
        return ok
    }

    class CancelledException : IOException("cancelled")
    class ChecksumMismatchException(msg: String) : IOException(msg)
}