package com.itantra.speech

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Phase 8: translation pack required-file validation (pure, JVM-testable). */
class TranslationPackValidationTest {

    @Test
    fun testTranslationPackRequiresEncoderDecoderConfig() {
        val dir = tempDir()
        try {
            // Empty / partial dirs are NOT installed.
            assertFalse(isComplete(dir))
            write(dir, "encoder_model.onnx")
            assertFalse(isComplete(dir))
            write(dir, "decoder_model.onnx")
            assertFalse(isComplete(dir))
            write(dir, "config.json")
            // Tokenizer files (source.spm/target.spm/vocab.tsv) are bundled in the
            // APK per pair and copied in lazily on first load — NOT part of the
            // downloaded artifact's completeness contract.
            assertTrue(isComplete(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun isComplete(dir: File): Boolean {
        val hasEnc = File(dir, "encoder_model.onnx").exists()
        val hasDec = File(dir, "decoder_model.onnx").exists()
        val hasCfg = File(dir, "config.json").exists()
        return hasEnc && hasDec && hasCfg
    }

    private fun tempDir(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "itantra_tp_${System.nanoTime()}")
        d.mkdirs()
        return d
    }

    private fun write(f: File, name: String) {
        File(f, name).parentFile?.mkdirs()
        File(f, name).writeBytes(ByteArray(4))
    }
}