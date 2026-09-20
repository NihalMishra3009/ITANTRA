package com.itantra.tts

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pure (JVM-testable) installer for the eSpeak NG data directory.
 *
 * eSpeak reads `espeak-ng-data` from the real filesystem (not from APK assets), so the
 * bundled zip is unpacked once into app-private storage. A version marker makes the
 * unpack idempotent and lets a new APK refresh stale data.
 */
object EspeakDataInstaller {
    const val DATA_DIR_NAME = "espeak-ng-data"
    private const val MARKER = ".itantra_data_version"

    /** Files that must exist for the data directory to be usable. */
    private val REQUIRED = listOf("phontab", "phondata", "phonindex", "intonations")

    fun isInstalled(parent: File, version: String): Boolean {
        val dir = File(parent, DATA_DIR_NAME)
        val marker = File(dir, MARKER)
        return marker.isFile && marker.readText().trim() == version &&
            REQUIRED.all { File(dir, it).isFile }
    }

    /**
     * Unpack [zip] into `parent/espeak-ng-data`. Entries are expected to be rooted at
     * `espeak-ng-data/`. Any entry that would land outside the target directory
     * (zip-slip) aborts the install. The directory is built in a temp sibling and
     * swapped in only when complete, so a crash mid-unpack never leaves a half-written
     * data dir that looks valid.
     */
    fun install(zip: InputStream, parent: File, version: String): Result<File> = runCatching {
        parent.mkdirs()
        val target = File(parent, DATA_DIR_NAME)
        val staging = File(parent, "$DATA_DIR_NAME.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingRoot = staging.canonicalFile

        ZipInputStream(zip.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val rel = entry.name.removePrefix("$DATA_DIR_NAME/")
                if (rel.isNotEmpty()) {
                    val out = File(staging, rel).canonicalFile
                    require(out.path.startsWith(stagingRoot.path + File.separator) || out == stagingRoot) {
                        "Blocked zip entry outside target: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val missing = REQUIRED.filterNot { File(staging, it).isFile }
        check(missing.isEmpty()) { "espeak data incomplete, missing: $missing" }
        File(staging, MARKER).writeText(version)

        target.deleteRecursively()
        check(staging.renameTo(target)) { "could not publish espeak data dir" }
        target
    }
}
