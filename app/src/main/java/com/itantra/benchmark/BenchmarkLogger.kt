package com.itantra.benchmark

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

data class LatencyRecord(
    val messageId: String,
    val language: String,
    val isAlert: Boolean,
    val speechDurationMs: Long,
    val sttLatencyMs: Long,
    val translationLatencyMs: Long,
    val transportLatencyMs: Long,
    val ttsLatencyMs: Long,
    val playbackLatencyMs: Long,
    val totalE2eLatencyMs: Long,
    val rtf: Float,
    val packetBytes: Int = 0,
    val jsonPacketBytes: Int = 0,
    // SIH Phase 3 full-field set (defaults keep existing callers compatible).
    val runId: String? = null,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val abi: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val modelVersion: String? = null,
    val hopCount: Int = 0,
    val success: Boolean = true,
    val error: String? = null
) {
    /** True when at least one real measurement exists (not all-zero fabricated). */
    fun hasAnyMeasurement(): Boolean =
        speechDurationMs > 0 || sttLatencyMs > 0 || translationLatencyMs > 0 ||
        transportLatencyMs > 0 || ttsLatencyMs > 0 || playbackLatencyMs > 0 || totalE2eLatencyMs > 0 || rtf > 0f
}

/**
 * Low-bitrate comparison: actual on-wire binary packet vs equivalent JSON.
 */
data class PacketSizeRecord(
    val language: String,
    val textBytes: Int,        // UTF-8 text size
    val binaryPacketBytes: Int, // compact binary wire size
    val jsonBytes: Int         // JSON equivalent
) {
    val compressionRatio: Float get() = if (jsonBytes > 0) jsonBytes.toFloat() / binaryPacketBytes else 0f
}

/** Per-stage translation timing (monotonic, microseconds — from the native adapter). */
data class TranslationTiming(
    val tokenizerMicros: Long,
    val encoderMicros: Long,
    val decoderMicros: Long,
    val totalMicros: Long
)

/**
 * Structured telemetry and benchmark logger for offline transceiver latency, RTF,
 * and low-bitrate efficiency evaluation. All timings use real monotonic clocks.
 */
object BenchmarkLogger {
    private const val TAG = "iTantraBenchmark"
    private val records = mutableListOf<LatencyRecord>()
    private val packetSizes = mutableListOf<PacketSizeRecord>()

    fun nowMs(): Long = SystemClock.elapsedRealtime()

    fun logInteraction(
        messageId: String,
        language: String,
        isAlert: Boolean,
        tSpeechStart: Long,
        tSpeechEnd: Long,
        tSttStart: Long,
        tSttEnd: Long,
        tSend: Long,
        tReceive: Long,
        tTtsStart: Long,
        tTtsEnd: Long,
        tPlayStart: Long,
        translationLatencyMs: Long = 0L,
        packetBytes: Int = 0,
        jsonPacketBytes: Int = 0
    ): LatencyRecord {
        val speechDuration = maxOf(0L, tSpeechEnd - tSpeechStart)
        val sttLatency = maxOf(0L, tSttEnd - tSttStart)
        val transportLatency = maxOf(0L, tReceive - tSend)
        val ttsLatency = maxOf(0L, tTtsEnd - tTtsStart)
        val playbackLatency = maxOf(0L, tPlayStart - tTtsEnd)
        val totalE2e = maxOf(0L, tPlayStart - tSpeechEnd)

        val rtf = if (speechDuration > 0) sttLatency.toFloat() / speechDuration.toFloat() else 0.0f

        val record = LatencyRecord(
            messageId = messageId,
            language = language,
            isAlert = isAlert,
            speechDurationMs = speechDuration,
            sttLatencyMs = sttLatency,
            translationLatencyMs = translationLatencyMs,
            transportLatencyMs = transportLatency,
            ttsLatencyMs = ttsLatency,
            playbackLatencyMs = playbackLatency,
            totalE2eLatencyMs = totalE2e,
            rtf = rtf,
            packetBytes = packetBytes,
            jsonPacketBytes = jsonPacketBytes
        )

        synchronized(records) {
            records.add(record)
        }

        Log.i(TAG, "=== LATENCY AUDIT [${messageId}] ===")
        Log.i(TAG, "Language: $language | Alert: $isAlert")
        Log.i(TAG, "Speech Duration: ${speechDuration}ms")
        Log.i(TAG, "STT Latency: ${sttLatency}ms (RTF: ${String.format("%.3f", rtf)})")
        if (translationLatencyMs > 0) Log.i(TAG, "Translation Latency: ${translationLatencyMs}ms")
        Log.i(TAG, "Transport Latency: ${transportLatency}ms")
        Log.i(TAG, "TTS Latency: ${ttsLatency}ms")
        Log.i(TAG, "Playback Latency: ${playbackLatency}ms")
        Log.i(TAG, "End-to-End Latency: ${totalE2e}ms")
        Log.i(TAG, "Packet: ${packetBytes}B binary vs ${jsonPacketBytes}B JSON" +
                if (packetBytes > 0) " (${String.format("%.1f", jsonPacketBytes.toFloat() / packetBytes)}x smaller)" else "")
        Log.i(TAG, "===================================")

        return record
    }

    fun logPacketSize(language: String, text: String, binaryBytes: Int, jsonBytes: Int) {
        val textBytes = text.toByteArray(Charsets.UTF_8).size
        synchronized(packetSizes) {
            packetSizes.add(PacketSizeRecord(language, textBytes, binaryBytes, jsonBytes))
        }
    }

    /** Cross-language packet-size comparison: real source vs translated text vs wire. */
    fun logTranslationPacketSize(language: String, sourceText: String, targetText: String, packetBytes: Int) {
        val src = sourceText.toByteArray(Charsets.UTF_8).size
        val tgt = targetText.toByteArray(Charsets.UTF_8).size
        Log.i(TAG, "CROSS-LANG [$language] source=$src B → translated=$tgt B → wire packet=$packetBytes B")
    }

    /** Native per-stage translation timing (monotonic). */
    fun logTranslationTiming(t: TranslationTiming) {
        Log.i(TAG, "TRANSLATION TIMING (us): tokenizer=${t.tokenizerMicros} encoder=${t.encoderMicros} " +
                "decoder=${t.decoderMicros} total=${t.totalMicros}")
    }

    fun getRecords(): List<LatencyRecord> {
        synchronized(records) {
            return records.toList()
        }
    }

    fun getPacketSizes(): List<PacketSizeRecord> {
        synchronized(packetSizes) {
            return packetSizes.toList()
        }
    }

    /** Add a fully-formed record (e.g. from an E2E pipeline that measured every stage). */
    fun add(record: LatencyRecord) {
        synchronized(records) { records.add(record) }
    }

    /** P50 / P95 latency helper over a sample list. */
    fun percentile(samples: List<Long>, percentile: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val idx = (percentile / 100.0 * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /**
     * Persist raw records + summary (Phase 3). Writes four files into [dir]:
     *   latency_raw.json / latency_raw.csv       every sample
     *   latency_summary.json / latency_summary.csv P50, P95, avg, min, max, count
     * "NOT MEASURED" is written instead of zero when a metric has no data.
     */
    fun export(dir: java.io.File): java.io.File {
        dir.mkdirs()
        val rawJson = java.io.File(dir, "latency_raw.json")
        val rawCsv = java.io.File(dir, "latency_raw.csv")
        val sumJson = java.io.File(dir, "latency_summary.json")
        val sumCsv = java.io.File(dir, "latency_summary.csv")
        val rs: List<LatencyRecord>
        synchronized(records) { rs = records.toList() }

        val metrics = linkedMapOf<String, (LatencyRecord) -> Long>(
            "speech_duration_ms" to { it.speechDurationMs },
            "stt_latency_ms" to { it.sttLatencyMs },
            "translation_latency_ms" to { it.translationLatencyMs },
            "transport_latency_ms" to { it.transportLatencyMs },
            "tts_latency_ms" to { it.ttsLatencyMs },
            "playback_latency_ms" to { it.playbackLatencyMs },
            "total_e2e_latency_ms" to { it.totalE2eLatencyMs },
            "rtf_x1000" to { (it.rtf * 1000).toLong() }
        )

        // ---- raw JSON ----
        rawJson.writeText(org.json.JSONArray().apply {
            rs.forEach { r ->
                put(org.json.JSONObject().apply {
                    put("message_id", r.messageId); put("language", r.language)
                    put("alert", r.isAlert); put("run_id", r.runId ?: JSONObject.NULL)
                    put("device_model", r.deviceModel ?: JSONObject.NULL)
                    put("android_version", r.androidVersion ?: JSONObject.NULL)
                    put("abi", r.abi ?: JSONObject.NULL)
                    put("source_language", r.sourceLanguage ?: JSONObject.NULL)
                    put("target_language", r.targetLanguage ?: JSONObject.NULL)
                    put("model_version", r.modelVersion ?: JSONObject.NULL)
                    put("hop_count", r.hopCount); put("success", r.success)
                    put("error", r.error ?: JSONObject.NULL)
                    metrics.forEach { (k, f) -> put(k, f(r)) }
                    put("packet_bytes", r.packetBytes); put("json_packet_bytes", r.jsonPacketBytes)
                })
            }
        }.toString())
        Log.i(TAG, "Exported ${rs.size} raw records -> ${rawJson.absolutePath}")

        // ---- raw CSV ----
        val fields = listOf(
            "run_id", "message_id", "language", "alert", "device_model", "android_version",
            "abi", "source_language", "target_language", "model_version", "hop_count",
            "speech_duration_ms", "stt_latency_ms", "translation_latency_ms",
            "transport_latency_ms", "tts_latency_ms", "playback_latency_ms",
            "total_e2e_latency_ms", "rtf", "packet_bytes", "json_packet_bytes",
            "success", "error")
        rawCsv.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(fields.joinToString(",") + "\n")
            rs.forEach { r ->
                w.write(listOf(
                    r.runId ?: "", r.messageId, r.language, r.isAlert, r.deviceModel ?: "",
                    r.androidVersion ?: "", r.abi ?: "", r.sourceLanguage ?: "", r.targetLanguage ?: "",
                    r.modelVersion ?: "", r.hopCount, r.speechDurationMs, r.sttLatencyMs,
                    r.translationLatencyMs, r.transportLatencyMs, r.ttsLatencyMs,
                    r.playbackLatencyMs, r.totalE2eLatencyMs, r.rtf, r.packetBytes,
                    r.jsonPacketBytes, r.success, r.error ?: "").joinToString(",") + "\n")
            }
        }
        Log.i(TAG, "Exported raw CSV -> ${rawCsv.absolutePath}")

        // ---- summary ----
        fun col(samples: List<Long>): String {
            val real = samples.filter { it > 0L }
            return if (real.isEmpty()) {
                "NOT MEASURED"
            } else {
                val p50 = percentile(real, 50.0); val p95 = percentile(real, 95.0)
                val avg = real.average().toLong()
                "p50=$p50 p95=$p95 avg=$avg min=${real.min()} max=${real.max()} n=${real.size}"
            }
        }
        val summaryJson = org.json.JSONObject().apply {
            put("run_id", rs.firstOrNull()?.runId ?: JSONObject.NULL)
            put("device_model", rs.firstOrNull()?.deviceModel ?: JSONObject.NULL)
            put("android_version", rs.firstOrNull()?.androidVersion ?: JSONObject.NULL)
            put("sample_count", rs.size)
            metrics.forEach { (k, f) -> put(k, col(rs.map(f))) }
        }
        sumJson.writeText(summaryJson.toString(2))
        Log.i(TAG, "Exported summary JSON -> ${sumJson.absolutePath}")

        sumCsv.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("metric,p50,p95,avg,min,max,n\n")
            metrics.forEach { (k, f) ->
                w.write("$k,${col(rs.map(f)).replace(" ", ",")}\n")
            }
            w.write("sample_count,,,${rs.size},,,\n")
        }
        return dir
    }
}
