package com.itantra.tts

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Fully offline, open-source (eSpeak NG) speech synthesis — the guaranteed voice for
 * every iTantra language. It is rule-based (formant), so it sounds robotic but is
 * intelligible, tiny (~2 MB of data), and needs no model download. A neural voice
 * (Piper) takes priority whenever one is installed for the language.
 */
class EspeakSynth(private val context: Context) {

    companion object {
        private const val TAG = "EspeakSynth"
        const val DATA_ASSET = "models/tts/espeak-ng-data.zip"
        /** Bump when the bundled zip changes so installed copies refresh. */
        const val DATA_VERSION = "1.52.0.1-ed530aa-1"

        /** Slightly below eSpeak's 175 wpm default: clearer for Indic phonology. */
        const val RATE_WPM = 150
        /** Alerts are announced at eSpeak's default pace. Set explicitly: the rate persists. */
        const val ALERT_RATE_WPM = 175

        private val VOICES = mapOf(
            "hi" to "hi", "gu" to "gu", "mr" to "mr", "kn" to "kn", "ml" to "ml",
            "ta" to "ta", "te" to "te", "or" to "or", "bn" to "bn", "en" to "en-us"
        )

        /** eSpeak voice for an iTantra language code; null when unsupported. */
        fun voiceFor(langCode: String): String? = VOICES[langCode.lowercase()]
    }

    private var ready = false
    private var sampleRate = 0
    private var currentVoice: String? = null

    fun supports(langCode: String): Boolean = voiceFor(langCode) != null

    private fun bundledDataPresent(): Boolean = try {
        context.assets.open(DATA_ASSET).use { true }
    } catch (e: Exception) {
        false
    }

    /** True when this device can synthesize [langCode] with eSpeak (asset + native lib). */
    fun isAvailable(langCode: String): Boolean =
        supports(langCode) && bundledDataPresent() && EspeakNative.ensureLoaded()

    @Synchronized
    private fun prepare(): Boolean {
        if (ready) return true
        if (!EspeakNative.ensureLoaded()) return false
        val parent = File(context.filesDir, "espeak")
        if (!EspeakDataInstaller.isInstalled(parent, DATA_VERSION)) {
            val res = try {
                context.assets.open(DATA_ASSET).use { EspeakDataInstaller.install(it, parent, DATA_VERSION) }
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (res.isFailure) {
                Log.e(TAG, "eSpeak data install failed", res.exceptionOrNull())
                return false
            }
        }
        val sr = EspeakNative.nInit(parent.absolutePath.toByteArray(Charsets.UTF_8))
        if (sr <= 0) {
            Log.e(TAG, "espeak init failed ($sr)")
            return false
        }
        sampleRate = sr
        ready = true
        return true
    }

    /** Synthesize; returns (pcm, sampleRate) or null when unavailable / failed. */
    @Synchronized
    fun synthesize(text: String, langCode: String, isAlert: Boolean = false): Pair<ShortArray, Int>? {
        val voice = voiceFor(langCode) ?: return null
        if (!prepare()) return null
        if (currentVoice != voice) {
            if (EspeakNative.nSetVoice(voice.toByteArray(Charsets.UTF_8)) != 0) {
                Log.e(TAG, "espeak voice '$voice' not found")
                return null
            }
            currentVoice = voice
        }
        val pcm = EspeakNative.nSynth(text.toByteArray(Charsets.UTF_8), if (isAlert) ALERT_RATE_WPM else RATE_WPM)
        return if (pcm == null || pcm.isEmpty()) null else pcm to sampleRate
    }
}
