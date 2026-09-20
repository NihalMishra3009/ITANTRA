package com.itantra.translation

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The native runtime reads these files byte-for-byte. A missing file, a gap in the id table or a
 * CRLF checkout does not throw: it silently feeds the model wrong tokens and yields garbage
 * translations (observed on a real phone before the Marian tokenizer was added).
 */
class TranslationTokenizerAssetsTest {
    private val root = File("src/main/assets/models/translation-tokenizers")
    private val pairs = mapOf("en-hi" to 61950, "hi-en" to 61127) // vocab size incl. <pad>

    @Test fun everyBundledPairHasAllThreeTokenizerFiles() {
        for (pair in pairs.keys) for (f in listOf("source.spm", "target.spm", "vocab.tsv")) {
            val file = File(root, "$pair/$f")
            assertTrue("$pair/$f missing", file.isFile)
            assertTrue("$pair/$f empty", file.length() > 100_000)
        }
    }

    @Test fun vocabIsContiguousAndHasTheSpecialTokensWhereTheModelExpectsThem() {
        for ((pair, size) in pairs) {
            val raw = File(root, "$pair/vocab.tsv").readBytes()
            assertFalse("$pair vocab.tsv has CR bytes (line-ending rewrite)", raw.any { it == 13.toByte() })
            val lines = String(raw, Charsets.UTF_8).split("\n").filter { it.isNotEmpty() }
            assertEquals("$pair vocab size", size, lines.size)
            lines.forEachIndexed { i, l ->
                val tab = l.indexOf('\t')
                assertTrue("$pair line $i has no tab", tab > 0)
                assertEquals("$pair ids must be contiguous", i, l.substring(0, tab).toInt())
            }
            fun tok(i: Int) = lines[i].substringAfter('\t')
            assertEquals("</s>", tok(0))     // eos_token_id
            assertEquals("<unk>", tok(1))    // unk
            assertEquals("<pad>", tok(size - 1)) // pad_token_id = decoder_start_token_id = vocab_size - 1
        }
    }
}
