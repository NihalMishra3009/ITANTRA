package com.itantra.benchmark

import android.os.SystemClock
import android.util.Log

data class LatencyRecord(
    val messageId: String,
    val language: String,
    val isAlert: Boolean,
    val speechDurationMs: Long,
    val sttLatencyMs: Long,
    val transportLatencyMs: Long,
    val ttsLatencyMs: Long,
    val playbackLatencyMs: Long,
    val totalE2eLatencyMs: Long,
    val rtf: Float,
    val packetBytes: Int = 0,
    val jsonPacketBytes: Int = 0
)

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

    /** P50 / P95 latency helper over a sample list. */
    fun percentile(samples: List<Long>, percentile: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val idx = (percentile / 100.0 * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
