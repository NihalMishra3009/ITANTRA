package com.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages audio focus and stream volume for normal voice and high-priority alert playback.
 */
class AudioFocusManager(private val context: Context) {
    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    fun requestFocus(isAlert: Boolean): Boolean {
        return try {
            if (isAlert) {
                // For alert mode: Set max application-controlled alarm volume
                val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
                Log.i(TAG, "Alert mode: Alarm stream volume set to max ($maxAlarmVol)")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val usage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
                val contentType = if (isAlert) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_SPEECH
                val gainType = if (isAlert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(gainType)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Audio focus changed: $focusChange")
                    }
                    .build()

                audioFocusRequest = focusRequest
                val res = audioManager.requestAudioFocus(focusRequest)
                res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val streamType = if (isAlert) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
                val res = audioManager.requestAudioFocus(
                    null,
                    streamType,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request audio focus", e)
            false
        }
    }

    fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        } finally {
            audioFocusRequest = null
        }
    }
}
