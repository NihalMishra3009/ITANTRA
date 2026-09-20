package com.itantra.tts

import android.util.Log

/** JNI binding to libitantra_espeak.so (eSpeak NG, GPL-3.0-or-later). */
object EspeakNative {
    private const val TAG = "EspeakNative"
    private const val LIB = "itantra_espeak"

    @Volatile private var loadAttempted = false
    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loadAttempted) return loaded
        loadAttempted = true
        loaded = try {
            System.loadLibrary(LIB)
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native espeak library not available: ${e.message}")
            false
        }
        return loaded
    }

    /** `dataParent` = directory that CONTAINS `espeak-ng-data`. Returns Hz or <0. */
    @JvmStatic external fun nInit(dataParent: ByteArray): Int
    @JvmStatic external fun nSetVoice(voice: ByteArray): Int
    @JvmStatic external fun nSynth(utf8Text: ByteArray, rateWpm: Int): ShortArray?
    @JvmStatic external fun nTerminate()
}
