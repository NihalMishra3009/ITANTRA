package com.itantra.speech

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Phase 8: translation pack required-file validation (pure, JVM-testable). */
class TranslationPackValidationTest {

    @Test
    fun testTranslationPackRequiresEncoderDecoderConfigSpm() {
        val dir = tempDir()
        try {
            // Empty / partial dirs are NOT installed.
            assertFalse(isComplete(dir))
            write(dir, "encoder_model.onnx")
            assertFalse(isComplete(dir))
            write(dir, "decoder_model.onnx")
            assertFalse(isComplete(dir))
            write(dir, "config.json")
            assertFalse(isComplete(dir))
            // tokenizer/sentencepiece.model required too.
            File(dir, "tokenizer").mkdirs()
            write(File(dir, "tokenizer"), "sentencepiece.model")
            assertTrue(isComplete(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun isComplete(dir: File): Boolean {
        val hasEnc = File(dir, "encoder_model.onnx").exists()
        val hasDec = File(dir, "decoder_model.onnx").exists()
        val hasCfg = File(dir, "config.json").exists()
        val hasTok = File(File(dir, "tokenizer"), "sentencepiece.model").exists()
        return hasEnc && hasDec && hasCfg && hasTok
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