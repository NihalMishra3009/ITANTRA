package com.itantra.tts

data class TtsResult(
    val pcmAudio: ShortArray,
    val sampleRate: Int = 22050,
    val durationMs: Long,
    val languageCode: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TtsResult
        if (!pcmAudio.contentEquals(other.pcmAudio)) return false
        if (sampleRate != other.sampleRate) return false
        if (durationMs != other.durationMs) return false
        if (languageCode != other.languageCode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pcmAudio.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + languageCode.hashCode()
        return result
    }
}
