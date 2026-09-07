package com.itantra.speech

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Phase 7 atomic-install validation tests. Exercise the REAL pure validation
 * function (ModelStorageManager.isCompletePackFiles) — no Android Context.
 */
class ModelStorageValidationTest {

    @Test
    fun testTtsPackValidRequiresModelAndTokens() {
        val dir = createTempDir()
        try {
            // Empty dir: NOT a valid pack.
            assertFalse(ModelStorageManager.isCompletePackFiles(dir))

            // Only model.onnx WITHOUT tokens -> incomplete (must not install).
            write(dir, "model.onnx", byteArrayOf(1, 2, 3))
            assertFalse(ModelStorageManager.isCompletePackFiles(dir))

            // model.onnx + tokens.txt -> valid TTS pack.
            write(dir, "tokens.txt", "a 0\nb 1\n".toByteArray())
            assertTrue(ModelStorageManager.isCompletePackFiles(dir))

            // Metadata sidecars never count as a pack by themselves.
            write(dir, "version.txt", "1".toByteArray())
            write(dir, "checksum.sha256", "deadbeef".toByteArray())
            assertTrue(ModelStorageManager.isCompletePackFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testSttEngineValidRequiresTokensAndOnnx() {
        val dir = createTempDir()
        try {
            assertFalse(ModelStorageManager.isCompletePackFiles(dir))
            write(dir, "tokens.txt", "a 0\n".toByteArray())
            assertFalse("STT needs at least one .onnx", ModelStorageManager.isCompletePackFiles(dir))
            write(dir, "encoder.onnx", ByteArray(16))
            assertTrue("STT with encoder + tokens is valid", ModelStorageManager.isCompletePackFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testPartialModelNeverValid() {
        val dir = createTempDir()
        try {
            // A partial model (only tokens, no model) must never be reported valid:
            // exactly the interrupted-install case the staging dir prevents.
            write(dir, "tokens.txt", "a 0\n".toByteArray())
            assertFalse(ModelStorageManager.isCompletePackFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun write(dir: File, name: String, bytes: ByteArray) {
        File(dir, name).writeBytes(bytes)
    }

    private fun createTempDir(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "itantra_stg_${System.nanoTime()}")
        d.mkdirs()
        return d
    }
}