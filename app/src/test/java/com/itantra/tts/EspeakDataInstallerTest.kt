package com.itantra.tts

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EspeakDataInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private val complete = arrayOf(
        "espeak-ng-data/phontab" to "a", "espeak-ng-data/phondata" to "b",
        "espeak-ng-data/phonindex" to "c", "espeak-ng-data/intonations" to "d",
        "espeak-ng-data/voices/!v/f1" to "e"
    )

    @Test
    fun installsCompleteDataAndIsIdempotent() {
        val parent = tmp.newFolder("p")
        assertFalse(EspeakDataInstaller.isInstalled(parent, "v1"))
        val r = EspeakDataInstaller.install(ByteArrayInputStream(zipOf(*complete)), parent, "v1")
        assertTrue(r.isSuccess)
        assertTrue(File(parent, "espeak-ng-data/voices/!v/f1").isFile)
        assertTrue(EspeakDataInstaller.isInstalled(parent, "v1"))
        // A new bundled version invalidates the installed copy.
        assertFalse(EspeakDataInstaller.isInstalled(parent, "v2"))
    }

    @Test
    fun blocksZipSlip() {
        val parent = tmp.newFolder("p")
        val evil = zipOf(*complete, "espeak-ng-data/../../escaped.txt" to "x")
        val r = EspeakDataInstaller.install(ByteArrayInputStream(evil), parent, "v1")
        assertTrue("zip-slip entry must abort", r.isFailure)
        assertFalse(File(parent.parentFile, "escaped.txt").exists())
        assertFalse("no half-installed dir", File(parent, "espeak-ng-data").exists())
    }

    @Test
    fun rejectsIncompleteData() {
        val parent = tmp.newFolder("p")
        val partial = zipOf("espeak-ng-data/phontab" to "a")
        assertTrue(EspeakDataInstaller.install(ByteArrayInputStream(partial), parent, "v1").isFailure)
        assertFalse(EspeakDataInstaller.isInstalled(parent, "v1"))
    }

    @Test
    fun realBundledAssetInstallsAndCoversAllTenLanguages() {
        val asset = File("src/main/assets/models/tts/espeak-ng-data.zip")
        assertTrue("bundled asset missing: ${asset.absolutePath}", asset.isFile)
        val parent = tmp.newFolder("real")
        val r = asset.inputStream().use { EspeakDataInstaller.install(it, parent, EspeakSynth.DATA_VERSION) }
        assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
        val dir = File(parent, "espeak-ng-data")
        for (lang in listOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en")) {
            assertTrue("dictionary for $lang", File(dir, "${lang}_dict").isFile)
        }
    }

    @Test
    fun everyLanguageMapsToAnEspeakVoice() {
        for (lang in listOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en")) {
            assertNotNull("voice for $lang", EspeakSynth.voiceFor(lang))
        }
        assertNull(EspeakSynth.voiceFor("xx"))
    }
}
