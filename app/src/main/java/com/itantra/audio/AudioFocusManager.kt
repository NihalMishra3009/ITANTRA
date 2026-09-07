package com.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages audio focus and stream volume for normal voice and high-priority alert playback.
 *
 * Alert playback temporarily raises the alarm/media volume to maximum, then RESTORES
 * the previous volume after playback ends — the user's device volume is never
 * permanently changed.
 */
class AudioFocusManager(private val context: Context) {
    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var savedVolume = -1
    private var savedVolumeStream = AudioManager.STREAM_MUSIC
    private var volumeRaised = false

    fun requestFocus(isAlert: Boolean): Boolean {
        return try {
            if (isAlert) {
                // Save previous volume, then raise it for uninterrupted emergency alert.
                savedVolumeStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioManager.STREAM_ALARM
                } else {
                    @Suppress("DEPRECATION")
                    AudioManager.STREAM_ALARM
                }
                savedVolume = audioManager.getStreamVolume(savedVolumeStream)
                val maxAlarmVol = audioManager.getStreamMaxVolume(savedVolumeStream)
                audioManager.setStreamVolume(savedVolumeStream, maxAlarmVol, 0)
                volumeRaised = true
                Log.i(TAG, "Alert mode: alarm stream volume raised to max ($maxAlarmVol); saved=$savedVolume")
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
                    .setAcceptsDelayedFocusGain(true)
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
            if (volumeRaised) {
                // Restore the user's previous volume after the alert finishes.
                if (savedVolume >= 0) {
                    audioManager.setStreamVolume(savedVolumeStream, savedVolume, 0)
                    Log.i(TAG, "Restored ${if (savedVolumeStream == AudioManager.STREAM_ALARM) "alarm" else "music"} volume to $savedVolume")
                }
                volumeRaised = false
                savedVolume = -1
            }
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