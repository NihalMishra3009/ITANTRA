package com.itantra.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * PCM 16-bit Audio Player for offline TTS synthesis output.
 */
class AudioPlayer {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private val playMutex = Mutex()

    suspend fun playPcm(
        pcmData: ShortArray,
        sampleRate: Int = 22050,
        isAlert: Boolean = false,
        onPlaybackStarted: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        playMutex.lock()
        try {
            stop()

            val usage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
            val contentType = if (isAlert) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_SPEECH

            val attributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val bufferSize = maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                pcmData.size * 2
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(pcmData, 0, pcmData.size)
            audioTrack?.play()
            isPlaying = true
            onPlaybackStarted?.invoke()

            // Wait for playback completion
            val durationMs = (pcmData.size.toDouble() / sampleRate * 1000).toLong()
            Thread.sleep(durationMs + 100)

            stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing PCM audio", e)
            stop()
        } finally {
            playMutex.unlock()
        }
    }

    @Synchronized
    fun stop() {
        isPlaying = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore clean teardown
        } finally {
            audioTrack = null
        }
    }

    fun isPlayingNow(): Boolean = isPlaying
}
