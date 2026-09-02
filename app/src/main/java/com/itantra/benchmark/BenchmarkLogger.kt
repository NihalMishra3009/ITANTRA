package com.itantra.benchmark

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
    val rtf: Float
)

/**
 * Structured telemetry and benchmark logger for offline transceiver latency and RTF evaluation.
 */
object BenchmarkLogger {
    private const val TAG = "iTantraBenchmark"
    private val records = mutableListOf<LatencyRecord>()

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
        tPlayStart: Long
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
            rtf = rtf
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
        Log.i(TAG, "===================================")

        return record
    }

    fun getRecords(): List<LatencyRecord> {
        synchronized(records) {
            return records.toList()
        }
    }
}
