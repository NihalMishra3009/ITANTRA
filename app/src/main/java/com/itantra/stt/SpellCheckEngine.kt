package com.itantra.stt

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline, per-language spelling-mistake FLAGGING only — never suggestions, never
 * auto-correction. This must not alter user-authored or emergency text, so it only ever
 * answers "which of these tokens are not in the dictionary?".
 *
 * Backed by Hunspell-affix-expanded word lists (see
 * model-conversion/build_spellcheck_wordlist.py) rather than a flat root-word list: Hindi and
 * Marathi are morphologically rich (case suffixes, postpositions glued onto the stem), so a
 * naive flat list would falsely flag ordinary, correctly-spelled inflected words as typos. The
 * build script expands every Hunspell root into every surface form the affix rules produce and
 * validates each one, so what's shipped here already accounts for real inflection.
 *
 * Stored on-device as a Bloom filter (`{lang}.bloom` under assets/spellcheck/), not a flat
 * HashSet<String>: the expanded Marathi form set alone is ~2.9 million words, which as Strings
 * in a HashSet would cost many tens of MB of RAM on a low-end phone. A Bloom filter answers
 * "definitely not present" (correctly flags a typo) or "probably present" (a small, tunable
 * false-positive rate — an occasional missed typo, an acceptable failure mode) and can
 * structurally never say "not present" for a word that actually is in the set — so it can never
 * cause the one unacceptable failure mode, flagging a real word as misspelled. See
 * model-conversion/build_spellcheck_wordlist.py for the exact construction (~1% target FPR).
 *
 * Only English, Hindi and Marathi have a shipped filter — the three languages this repo has
 * reliable, open-licensed Hunspell dictionaries for (see docs/MODEL_LICENSES.md). Every other
 * supported language is a silent no-op: [check] returns an empty list, never throws, and never
 * touches the asset system for a language with no filter.
 */
object SpellCheckEngine {
    private const val TAG = "SpellCheckEngine"
    private const val ASSET_DIR = "spellcheck"

    /** Languages with a shipped Bloom filter; matches SupportedLanguage.code. */
    val SUPPORTED_LANGUAGES: Set<String> = setOf("en", "hi", "mr")

    // Only ever holds SUCCESSFULLY loaded filters. A language that failed to load (or has none)
    // is tracked separately in [unavailable] so we don't retry the asset read on every call —
    // ConcurrentHashMap can't store a null value as a "known missing" marker, hence the split.
    private val loaded = ConcurrentHashMap<String, BloomFilter>()
    private val unavailable = ConcurrentHashMap.newKeySet<String>()

    // Keeps letters (\p{L}) AND combining marks (\p{M}) together, e.g. Devanagari matras/virama
    // are their own Unicode category (Mn/Mc) and would otherwise split "का" into "क" + stray
    // mark. A run of letters/marks optionally joined by a single apostrophe (English "don't",
    // "ABC's") counts as one token.
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")

    /**
     * Returns the tokens in [text] that are NOT recognized as valid words for [languageCode] —
     * i.e. possibly misspelled. Empty (never null, never throws) for any language without a
     * shipped filter, or if [text] is blank.
     */
    fun check(context: Context, text: String, languageCode: String): List<String> {
        val filter = filterFor(context, languageCode) ?: return emptyList()
        return flagUnrecognized(text, languageCode, filter)
    }

    /**
     * Pure tokenize-and-match core, split out from [check] so it is unit-testable without an
     * Android Context/AssetManager (this repo's JVM unit tests don't use Robolectric — see
     * app/src/test/java/com/itantra/speech/SpellCheckEngineTest.kt). Tests load a real
     * generated .bloom file straight off disk with plain java.io and call this directly.
     */
    internal fun flagUnrecognized(text: String, languageCode: String, filter: BloomFilter): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = TOKEN_REGEX.findAll(text).map { it.value }.filter { it.length > 1 }
        return tokens.filterNot { isRecognized(it, languageCode, filter) }.distinct().toList()
    }

    private fun isRecognized(word: String, languageCode: String, filter: BloomFilter): Boolean {
        if (filter.mightContain(word)) return true
        // English dictionary forms are mostly lowercase; tolerate sentence-initial capitalization
        // without needing a second copy of every word in the shipped filter.
        if (languageCode == "en" && filter.mightContain(word.lowercase())) return true
        return false
    }

    private fun filterFor(context: Context, languageCode: String): BloomFilter? {
        if (languageCode !in SUPPORTED_LANGUAGES) return null
        loaded[languageCode]?.let { return it }
        if (languageCode in unavailable) return null
        synchronized(this) {
            loaded[languageCode]?.let { return it }
            if (languageCode in unavailable) return null
            return try {
                val filter = context.assets.open("$ASSET_DIR/$languageCode.bloom").use { raw ->
                    BloomFilter.read(raw)
                }
                loaded[languageCode] = filter
                filter
            } catch (e: Exception) {
                // Missing/corrupt asset must never crash the pipeline — spell-check is purely
                // informational, so treat it the same as "no filter for this language".
                Log.w(TAG, "No usable spell-check filter for '$languageCode': ${e.message}")
                unavailable.add(languageCode)
                null
            }
        }
    }
}

/**
 * A read-only Bloom filter over UTF-8 strings, matching the exact binary format and hash scheme
 * written by model-conversion/build_spellcheck_wordlist.py (write_bloom_filter /
 * _bloom_bit_indices there) — this Kotlin code MUST stay bit-for-bit identical to that Python
 * code, or a filter built there won't agree with queries made here.
 *
 * Format: 5-byte magic "ITBF1", 1-byte k (hash count), 8-byte big-endian m (bit count), 8-byte
 * big-endian n (informational item count), then ceil(m/8) bytes of bit array.
 *
 * Hashing: two independent 64-bit FNV hashes (FNV-1a and FNV-1 — same constants, different
 * mix order) combined via Kirsch-Mitzenmacher double hashing: g_i(x) = h1(x) + i*h2(x) mod m.
 */
internal class BloomFilter(private val bits: ByteArray, private val m: Long, private val k: Int) {

    fun mightContain(word: String): Boolean {
        val data = word.toByteArray(Charsets.UTF_8)
        val h1 = fnv1a64(data)
        val h2 = fnv1_64(data) or 1L
        for (i in 0 until k) {
            val idx = java.lang.Long.remainderUnsigned(h1 + i.toLong() * h2, m)
            val byteIdx = (idx / 8).toInt()
            val bitIdx = (idx % 8).toInt()
            if (bits[byteIdx].toInt() and (1 shl bitIdx) == 0) return false
        }
        return true
    }

    companion object {
        private const val FNV64_PRIME: Long = 0x100000001B3L
        // 0xCBF29CE484222325 doesn't fit a signed 64-bit literal; parse it unsigned instead.
        private val FNV64_OFFSET_BASIS: Long = java.lang.Long.parseUnsignedLong("CBF29CE484222325", 16)

        private fun fnv1a64(data: ByteArray): Long {
            var h = FNV64_OFFSET_BASIS
            for (b in data) {
                h = h xor (b.toLong() and 0xFF)
                h *= FNV64_PRIME
            }
            return h
        }

        private fun fnv1_64(data: ByteArray): Long {
            var h = FNV64_OFFSET_BASIS
            for (b in data) {
                h *= FNV64_PRIME
                h = h xor (b.toLong() and 0xFF)
            }
            return h
        }

        fun read(stream: java.io.InputStream): BloomFilter {
            DataInputStream(stream).use { input ->
                val magic = ByteArray(5)
                input.readFully(magic)
                val magicStr = String(magic, Charsets.US_ASCII)
                require(magicStr == "ITBF1") { "bad Bloom filter magic: $magicStr" }
                val k = input.readUnsignedByte()
                val m = input.readLong()
                input.readLong() // n — informational only, not needed to query
                val byteCount = ((m + 7) / 8).toInt()
                val bits = ByteArray(byteCount)
                input.readFully(bits)
                return BloomFilter(bits, m, k)
            }
        }
    }
}
