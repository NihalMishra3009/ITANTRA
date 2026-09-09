package com.itantra.speech

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Phase 5/7/13: translation-pack completeness follows the DEVICE runtime contract
 * (flattened: encoder/decoder/config at root, tokenizer/ nested) and sizeBytes
 * counts nested dirs recursively. Pure — no Context.
 */
class TranslationStorageContractTest {

    @Test
    fun testCompleteTranslationPackRequiresAllContractFiles() {
        val dir = createTempDir()
        try {
            assertFalse(ModelStorageManager.isCompleteTranslationPackFiles(dir))

            write(dir, "encoder_model.onnx", ByteArray(8))
            assertFalse("decoder missing", ModelStorageManager.isCompleteTranslationPackFiles(dir))
            write(dir, "decoder_model.onnx", ByteArray(8))
            assertFalse("config missing", ModelStorageManager.isCompleteTranslationPackFiles(dir))
            write(dir, "config.json", "{}".toByteArray())
            assertFalse("spm missing", ModelStorageManager.isCompleteTranslationPackFiles(dir))
            File(dir, "tokenizer").mkdirs()
            write(File(dir, "tokenizer"), "sentencepiece.model", ByteArray(4))
            assertFalse("sp.vocab missing", ModelStorageManager.isCompleteTranslationPackFiles(dir))
            write(File(dir, "tokenizer"), "sp.vocab", "0\t<unk>\n".toByteArray())
            assertTrue(ModelStorageManager.isCompleteTranslationPackFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testHostLayoutModelsSubdirIsNotADevicePack() {
        // The converter emits /models/encoder_model.onnx — the installer flattens
        // it to the root. A raw (unflattened) host pack must NOT be considered
        // installed on-device.
        val dir = createTempDir()
        try {
            File(dir, "models").mkdirs()
            write(File(dir, "models"), "encoder_model.onnx", ByteArray(8))
            write(File(dir, "models"), "decoder_model.onnx", ByteArray(8))
            write(dir, "config.json", "{}".toByteArray())
            File(dir, "tokenizer").mkdirs()
            write(File(dir, "tokenizer"), "sentencepiece.model", ByteArray(4))
            write(File(dir, "tokenizer"), "sp.vocab", "0\t<unk>\n".toByteArray())
            assertFalse("unflattened host layout is not an installed device pack",
                ModelStorageManager.isCompleteTranslationPackFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testSizeBytesCountsNestedDirsRecursively() {
        val dir = createTempDir()
        try {
            write(dir, "encoder_model.onnx", ByteArray(100))
            write(dir, "decoder_model.onnx", ByteArray(200))
            write(dir, "config.json", ByteArray(30))
            File(dir, "models").mkdirs()
            write(File(dir, "models"), "nested.onnx", ByteArray(400))
            File(dir, "tokenizer").mkdirs()
            write(File(dir, "tokenizer"), "sentencepiece.model", ByteArray(50))
            write(File(dir, "tokenizer"), "sp.vocab", ByteArray(20))
            write(dir, "version.txt", ByteArray(5))     // housekeeping: excluded
            write(dir, "checksum.sha256", ByteArray(9)) // housekeeping: excluded

            // Recursive: nested models/ + tokenizer/ files ARE counted; only the
            // version.txt/checksum.sha256 housekeeping files are excluded.
            assertEquals(100L + 200 + 30 + 400 + 50 + 20,
                ModelStorageManager.packSizeBytes(dir, ModelStorageManager::isIgnoredHousekeeping))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun write(dir: File, name: String, bytes: ByteArray) {
        File(dir, name).writeBytes(bytes)
    }

    private fun createTempDir(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "itantra_tst_${System.nanoTime()}")
        d.mkdirs()
        return d
    }
}