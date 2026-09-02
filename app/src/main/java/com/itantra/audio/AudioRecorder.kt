package com.itantra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance 16kHz Mono PCM Audio Recorder for VAD & STT streaming.
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val chunkSize: Int = 512 // 32ms frames @ 16kHz
) {
    companion object {
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    private val _audioChunkFlow = MutableSharedFlow<FloatArray>(replay = 0, extraBufferCapacity = 64)
    val audioChunkFlow: SharedFlow<FloatArray> = _audioChunkFlow.asSharedFlow()

    private val minBufferSize: Int by lazy {
        val calculated = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        maxOf(calculated, chunkSize * 4)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        if (isRecording) return true

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(chunkSize)
                val floatBuffer = FloatArray(chunkSize)

                while (isActive && isRecording) {
                    val readCount = audioRecord?.read(shortBuffer, 0, chunkSize) ?: -1
                    if (readCount > 0) {
                        for (i in 0 until readCount) {
                            floatBuffer[i] = shortBuffer[i] / 32768.0f
                        }
                        val emitChunk = if (readCount == chunkSize) {
                            floatBuffer.copyOf()
                        } else {
                            floatBuffer.copyOf(readCount)
                        }
                        _audioChunkFlow.tryEmit(emitChunk)
                    } else if (readCount < 0) {
                        Log.w(TAG, "AudioRecord read error: $readCount")
                        delay(10)
                    }
                }
            }
            Log.i(TAG, "Audio recording started successfully @ ${sampleRate}Hz")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting audio recording", e)
            stopRecording()
            return false
        }
    }

    @Synchronized
    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audioRecord", e)
        } finally {
            audioRecord = null
        }
        Log.i(TAG, "Audio recording stopped")
    }

    fun isRecordingNow(): Boolean = isRecording
}
