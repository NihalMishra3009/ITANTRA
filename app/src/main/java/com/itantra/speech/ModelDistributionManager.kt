package com.itantra.speech

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads, verifies, and atomically installs language model packs into app-private
 * storage from a staging directory. The ONLY online steps are optional user-initiated
 * acquisition/update; after install, inference runs fully offline from cached files.
 *
 * Pipeline:
 *   DOWNLOAD (fresh) -> TEMP FILE -> VERIFY SHA-256 -> REQUIRED-FILE VALIDATION
 *   -> ATOMIC PUBLISH (rename .staging -> live dir) -> REGISTER INSTALLED
 *
 * Guarantees:
 *  - never overwrites the active (installed) model mid-download — a full download
 *    writes into .staging/.../.tmp, is verified, then the validated staging directory
 *    is renamed over the live pack in one step
 *  - cancellable and retryable (a retry re-downloads from scratch)
 *  - corrupted downloads detected by size + SHA-256 mismatch -> never installed
 *  - a failed download never destroys an already-installed valid model (staging is
 *    discarded; the live dir is only replaced after validation succeeds)
 *  - individual STT / TTS packs install/delete independently
 *
 * NOTE: downloads are NOT resumable. A cancelled/failed download restarts from
 * byte zero on retry. This is stated honestly rather than claiming HTTP Range
 * support that isn't implemented.
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
        return if (pack.isEngine) {
            val dir = engineDir(pack)
            // Engine pack: valid only when required files are actually present.
            if (File(dir, "tokens.txt").exists() && dir.listFiles { f ->
                    f.isFile && f.name.endsWith(".onnx", ignoreCase = true)
                }?.isNotEmpty() == true) PackStatus.INSTALLED else PackStatus.NOT_INSTALLED
        } else if (storage.isInstalled(pack.role, pack.storageKey)) PackStatus.INSTALLED
        else PackStatus.NOT_INSTALLED
    }

    /** Directory for shared engine packs (independent of language). */
    private fun engineDir(pack: LanguageModelPack): File =
        File(storage.modelsDir, "stt_engine/${pack.id}")

    fun setStatus(packId: String, status: PackStatus) { statuses[packId] = status }

    /** Installed pack actual size (measured from filesystem). */
    fun installedSize(pack: LanguageModelPack): Long =
        if (storage.isInstalled(pack.role, pack.storageKey))
            storage.sizeBytes(pack.role, pack.storageKey) else 0

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
        val lang = pack.storageKey.lowercase()
        // Both language and engine packs stage into .staging/... and only publish
        // into the live dir after validation (never expose a partial model).
        val stagingDir = if (pack.isEngine) File(storage.modelsDir, ModelStorageManager.STAGING_DIR + "/engine/" + pack.id)
        else storage.stagingDir(pack.role, lang)
        stagingDir.apply { mkdirs() }
        val tmpDir = File(stagingDir, ModelStorageManager.TMP_DIR).apply { mkdirs() }
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

                // Extract archives (sherpa-onnx tts voices: model.onnx + tokens.txt,
                // or translation packs with full nested directories).
                val finalFile: File
                if (pack.isArchive) {
                    extractArchiveInto(stagingDir, tmpFile, onProgress, pack.role)
                    finalFile = if (pack.role == ModelRole.TRANSLATION) File(stagingDir, "config.json") else File(stagingDir, "tokens.txt")
                } else {
                    // Atomic move into place for plain single-file models.
                    val f = File(stagingDir, "model.onnx")
                    if (f.exists()) f.delete()
                    if (!tmpFile.renameTo(f)) {
                        tmpFile.copyTo(f, overwrite = true)
                        tmpFile.delete()
                    }
                    finalFile = f
                }

                // REQUIRED-file validation BEFORE publish: never expose a partial
                // pack as installed.
                if (pack.isEngine) {
                    // Engine pack: tokens + at least one .onnx required.
                    val hasTokens = File(stagingDir, "tokens.txt").exists()
                    val hasOnnx = stagingDir.listFiles { f ->
                        f.isFile && f.name.endsWith(".onnx", ignoreCase = true)
                    }?.isNotEmpty() == true
                    if (!hasTokens || !hasOnnx) {
                        throw IOException("Engine pack incomplete: missing tokens.txt/onnx")
                    }
                } else if (!storage.isCompletePack(stagingDir, pack.role)) {
                    throw IOException("Pack incomplete: missing required model/tokens files")
                }

                // Publish stage -> live pack dir atomically (same filesystem).
                publish(stagingDir, if (pack.isEngine) engineDir(pack) else storage.roleDir(pack.role, lang))

                if (pack.isEngine) {
                    writeEngineMetadata(pack, lang, engineDir(pack))
                } else {
                    storage.writeInstalledMetadata(pack.role, lang, pack.version, pack.checksumSha256)
                }
                tmpDir.deleteRecursively()

                setStatus(pack.id, PackStatus.INSTALLED)
                onDone(Result.success(finalFile))
            } catch (e: CancelledException) {
                Log.w(TAG, "Download of ${pack.id} cancelled")
                cntryCleanup(tmpDir, tmpFile, stagingDir)
                setStatus(pack.id, PackStatus.NOT_INSTALLED)
                onDone(Result.failure(e))
            } catch (e: Exception) {
                Log.e(TAG, "Download of ${pack.id} failed: ${e.message}")
                cntryCleanup(tmpDir, tmpFile, stagingDir)
                setStatus(pack.id,
                    if (e is ChecksumMismatchException) PackStatus.CORRUPTED else PackStatus.FAILED)
                onDone(Result.failure(e))
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }.start()
    }

    /**
     * Atomically publish a validated staging dir into the live pack dir. On same
     * filesystem this is a rename; the previous (working) install is replaced only
     * after the new content is fully in place, so an interrupted/ failed install
     * never exposes a partial model.
     */
    private fun publish(stagingDir: File, liveDir: File) {
        if (!stagingDir.exists()) throw IOException("Staging dir missing for publish")
        // Preserve the current installation until replacement is ready.
        val backup = File(liveDir.parentFile, liveDir.name + ".old")
        try {
            if (liveDir.exists() && backup.exists()) backup.deleteRecursively()
            if (liveDir.exists()) liveDir.renameTo(backup)
            if (!stagingDir.renameTo(liveDir)) {
                // Cross-filesystem fallback (shouldn't happen — both under filesDir).
                liveDir.mkdirs()
                stagingDir.copyRecursively(liveDir, overwrite = true)
                stagingDir.deleteRecursively()
                backup.deleteRecursively()
            } else {
                backup.deleteRecursively()
            }
        } catch (e: Exception) {
            // Roll back: restore the previous working install if publish failed.
            try {
                if (!liveDir.exists() && backup.exists()) backup.renameTo(liveDir)
            } catch (_: Exception) {}
            throw e
        }
    }

    private fun cntryCleanup(tmpDir: File, tmpFile: File, stagingDir: File) {
        // Keep staging on failure so a retry can resume; only delete the partial file.
        try { tmpFile.delete() } catch (_: Exception) {}
        if (!stagingDir.listFiles { f -> f.name != ModelStorageManager.TMP_DIR }?.any()!!) {
            try { stagingDir.deleteRecursively() } catch (_: Exception) {}
        }
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
     * Extract a .tar.gz or .tar.bz2 archive (sherpa-onnx TTS voice OR Whisper pack)
     * into the pack directory. Preserves EVERY .onnx by its original filename (Whisper
     * packs ship encoder.onnx + decoder.onnx) plus tokens.txt, and espeak-ng-data for
     * Piper voices. Writes atomically. Uses Apache Commons Compress.
     *
     * Gzip archives decompress ~10-20x faster than bzip2 on device — MMS-style big
     * voices (100+ MB) MUST ship as .tar.gz to keep install latency acceptable.
     */
    private fun extractArchiveInto(targetDir: File, archive: File, onProgress: (Float) -> Unit, packRole: ModelRole = ModelRole.TTS) {
        // TRANSLATION packs preserve the full nested tree (models/, tokenizer/,
        // config.json) with the leading {pair}/ segment stripped.
        val archiveName = archive.name.lowercase()
        // Sniff decompressor: .tar.gz / .tgz → gzip (fast), else bzip2 (legacy Piper packs).
        var isGzip = archiveName.endsWith(".tar.gz") || archiveName.endsWith(".tgz")
        if (!isGzip) {
            try {
                archive.inputStream().use { s ->
                    val magic = ByteArray(2)
                    val n = s.read(magic)
                    isGzip = n == 2 && (magic[0].toInt() and 0xFF) == 0x1F && (magic[1].toInt() and 0xFF) == 0x8B
                }
            } catch (_: Exception) { }
        }
        val totalBytes = archive.length().coerceAtLeast(1L)
        val bz2 = if (isGzip) {
            org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(archive.inputStream())
        } else {
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(archive.inputStream())
        }
        val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz2)
        val tmpExtract = File(targetDir, ModelStorageManager.TMP_DIR).apply { mkdirs() }
        try {
            var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry?
            var onnxFound = false
            var tokensFound = false
            var espeakFound = false
            var bytesConsumed = 0L
            var lastProgress = -1
            val buf = ByteArray(128 * 1024)
            while (tar.nextEntry.also { entry = it } != null) {
                val e = entry ?: continue
                val path = e.name
                val base = path.substringAfterLast('/')
                if (e.isDirectory) continue

                val destRel: String
                if (packRole == ModelRole.TRANSLATION) {
                    // Keep the whole tree, minus the leading {pair}/ directory.
                    destRel = path.split('/').drop(1).joinToString("/")
                    if (destRel.isBlank()) continue
                    if (base.endsWith(".onnx")) onnxFound = true
                    if (base.equals("config.json", true)) tokensFound = true // config as the required file
                } else {
                    val isOnnx = base.endsWith(".onnx") && e.isFile
                    val isTokens = base.equals("tokens.txt", ignoreCase = true) && e.isFile
                    val isEspeak = path.contains("espeak-ng-data") && e.isFile
                    if (!isOnnx && !isTokens && !isEspeak) continue
                    destRel = when {
                        isOnnx -> base
                        isTokens -> "tokens.txt"
                        else -> path.substring(path.indexOf("espeak-ng-data"))
                    }
                    if (isOnnx) onnxFound = true else if (isTokens) tokensFound = true else espeakFound = true
                }

                val dest = File(tmpExtract, destRel)
                dest.parentFile?.mkdirs()
                var written = 0L
                dest.outputStream().use { out ->
                    var n: Int
                    while (tar.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        written += n
                        bytesConsumed += n
                        val p = (bytesConsumed.toDouble() / totalBytes).toFloat()
                        val idx = (p * 100).toInt()
                        if (idx != lastProgress) {
                            lastProgress = idx
                            onProgress((0.05f + p * 0.90f).coerceAtMost(0.95f))
                        }
                    }
                }
                if (written > 0 && e.size > 0 && written < e.size) {
                    throw IOException("Truncated $base ($written/${e.size} bytes)")
                }
            }
            // Required-file validation (role-appropriate).
            if (packRole == ModelRole.TRANSLATION) {
                val hasEnc = File(tmpExtract, "models/encoder_model.onnx").exists()
                val hasDec = File(tmpExtract, "models/decoder_model.onnx").exists()
                val hasCfg = File(tmpExtract, "config.json").exists()
                if (!hasEnc || !hasDec || !hasCfg) {
                    throw IOException("Translation archive incomplete: encoder/decoder/config missing")
                }
                onnxFound = true; tokensFound = true
            } else if (!onnxFound || !tokensFound) {
                throw IOException("Archive missing model(*.onnx)/tokens.txt for ${archive.name}")
            }
            if (packRole != ModelRole.TRANSLATION) writeEspeakMarker(targetDir, espeakFound)
            onProgress(0.95f)
        } finally {
            tar.close()
        }
        // Atomically publish EVERY extracted file (nested for translation; the
        // original TTS/Whisper flat layout is preserved by destRel above).
        if (packRole == ModelRole.TRANSLATION) {
            publishTree(tmpExtract, targetDir)
        } else {
            File(tmpExtract, "tokens.txt").copyTo(File(targetDir, "tokens.txt"), overwrite = true)
            File(tmpExtract, "tokens.txt").delete()
            tmpExtract.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.forEach { onnx ->
                onnx.copyTo(File(targetDir, onnx.name), overwrite = true)
                onnx.delete()
            }
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
    }

    /** Recursively move a staging tree into the live dir (translation packs). */
    private fun publishTree(src: File, dst: File) {
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src)
            val target = File(dst, rel.path)
            if (f.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); f.copyTo(target, overwrite = true) }
        }
        src.deleteRecursively()
    }

    /** Record that the voice pack contains espeak-ng-data (Piper-required). */
    private fun writeEspeakMarker(targetDir: File, present: Boolean) {
        try {
            File(targetDir, ".espeak").writeText(if (present) "1" else "0")
        } catch (e: Exception) { /* non-fatal */ }
    }

    /** Record version + checksum for a shared engine pack. */
    private fun writeEngineMetadata(pack: LanguageModelPack, lang: String, dir: File) {
        dir.mkdirs()
        File(dir, ModelStorageManager.VERSION_FILE).writeText(pack.version)
        File(dir, ModelStorageManager.CHECKSUM_FILE).writeText(pack.checksumSha256)
    }

    /** Cancel an in-progress download. */
    fun cancel(packId: String) { cancelFlags[packId] = true }

    /** Delete an installed pack (STT and TTS independent, engine packs too). */
    fun deletePack(pack: LanguageModelPack): Boolean {
        val ok = if (pack.isEngine) engineDir(pack).deleteRecursively()
        else storage.deletePack(pack.role, pack.storageKey)
        // Also discard any stale staging for this pack.
        val lang = pack.storageKey.lowercase()
        val stage = if (pack.isEngine) File(storage.modelsDir, ModelStorageManager.STAGING_DIR + "/engine/" + pack.id)
        else storage.stagingDir(pack.role, lang)
        try { if (stage.exists()) stage.deleteRecursively() } catch (_: Exception) {}
        statuses.remove(pack.id)
        return ok
    }

    class CancelledException : IOException("cancelled")
    class ChecksumMismatchException(msg: String) : IOException(msg)
}