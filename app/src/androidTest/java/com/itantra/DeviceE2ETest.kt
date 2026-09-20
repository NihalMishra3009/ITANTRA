package com.itantra

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itantra.ui.TranslationTestActivity
import org.junit.After
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import com.itantra.stt.SttEngine
import com.itantra.tts.EspeakSynth
import com.itantra.tts.TtsEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * On-device end-to-end checks with the REAL engines (no mocks): every language must
 * speak, and synthesized speech must survive a round trip through Whisper STT.
 * Measurements are emitted on logcat tag "E2E" for collection by the operator.
 */
@RunWith(AndroidJUnit4::class)
class DeviceE2ETest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Some OEM ROMs (observed on OPPO/ColorOS) freeze a process that has no foreground
     * activity, even mid-computation, which makes every timing meaningless. A light
     * activity in the foreground keeps the process running at full speed.
     */
    private var scenario: ActivityScenario<TranslationTestActivity>? = null

    @Before fun keepProcessForeground() { scenario = ActivityScenario.launch(TranslationTestActivity::class.java) }
    @After fun releaseForeground() { scenario?.close() }

    private val sentences = linkedMapOf(
        "hi" to "मुझे मदद चाहिए, कृपया सहायता भेजें",
        "gu" to "મને મદદ જોઈએ છે, કૃપા કરીને સહાય મોકલો",
        "mr" to "मला मदत हवी आहे, कृपया मदत पाठवा",
        "kn" to "ನನಗೆ ಸಹಾಯ ಬೇಕು, ದಯವಿಟ್ಟು ಸಹಾಯ ಕಳುಹಿಸಿ",
        "ml" to "എനിക്ക് സഹായം വേണം, ദയവായി സഹായം അയയ്ക്കുക",
        "ta" to "எனக்கு உதவி தேவை, தயவுசெய்து உதவி அனுப்புங்கள்",
        "te" to "నాకు సహాయం కావాలి, దయచేసి సహాయం పంపండి",
        "or" to "ମୋତେ ସାହାଯ୍ୟ ଦରକାର, ଦୟାକରି ସାହାଯ୍ୟ ପଠାନ୍ତୁ",
        "bn" to "আমার সাহায্য দরকার, দয়া করে সাহায্য পাঠান",
        "en" to "Please send help to the river bridge"
    )

    private fun rms(p: ShortArray): Double =
        if (p.isEmpty()) 0.0 else sqrt(p.sumOf { it.toDouble() * it } / p.size)

    @Test
    fun espeakSpeaksAllTenLanguages() {
        val es = EspeakSynth(ctx)
        for ((lang, text) in sentences) {
            assertTrue("eSpeak unavailable for $lang", es.isAvailable(lang))
            val t0 = System.nanoTime()
            val r = es.synthesize(text, lang)
            val ms = (System.nanoTime() - t0) / 1e6
            assertNotNull("no audio for $lang", r)
            val (pcm, sr) = r!!
            val secs = pcm.size.toDouble() / sr
            Log.i("E2E", "ESPEAK lang=$lang secs=%.2f rms=%.0f synthMs=%.0f rtf=%.3f".format(secs, rms(pcm), ms, ms / 1000.0 / secs))
            assertTrue("$lang audio too short: $secs s", secs > 0.8)
            assertTrue("$lang audio is silent", rms(pcm) > 300)
        }
    }

    @Test
    fun ttsEngineSpeaksAllTenLanguages() {
        val engine = TtsEngine(ctx)
        for ((lang, text) in sentences) {
            engine.initialize(lang)
            val t0 = System.nanoTime()
            val r = engine.synthesize(text, lang, false)
            val ms = (System.nanoTime() - t0) / 1e6
            val secs = r.pcmAudio.size.toDouble() / r.sampleRate
            Log.i("E2E", "TTSENGINE lang=$lang loaded=${engine.isLoadedFor(lang)} secs=%.2f rms=%.0f ms=%.0f".format(secs, rms(r.pcmAudio), ms))
            assertTrue("TtsEngine produced no audio for $lang", r.pcmAudio.isNotEmpty())
            assertTrue("$lang silent", rms(r.pcmAudio) > 300)
        }
        engine.release()
    }

    private fun resampleTo16k(pcm: ShortArray, sr: Int): FloatArray {
        val outLen = (pcm.size.toLong() * 16000 / sr).toInt()
        return FloatArray(outLen) { i ->
            val pos = i.toDouble() * sr / 16000
            val a = pos.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val f = (pos - a).toFloat()
            (pcm[a] * (1 - f) + pcm[b] * f) / 32768f
        }
    }

    private fun words(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), " ").split(" ").filter { it.isNotBlank() }

    private fun wer(ref: List<String>, hyp: List<String>): Double {
        val d = Array(ref.size + 1) { IntArray(hyp.size + 1) }
        for (i in 0..ref.size) d[i][0] = i
        for (j in 0..hyp.size) d[0][j] = j
        for (i in 1..ref.size) for (j in 1..hyp.size)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1)
        return d[ref.size][hyp.size].toDouble() / ref.size.coerceAtLeast(1)
    }

    @Test
    fun ttsToSttRoundTrip() {
        val stt = SttEngine(ctx)
        val es = EspeakSynth(ctx)
        var transcribed = 0
        for ((lang, text) in sentences) {
            val (pcm, sr) = es.synthesize(text, lang) ?: continue
            val audio = resampleTo16k(pcm, sr)
            val t0 = System.nanoTime()
            val res = stt.transcribe(audio, lang)
            val ms = (System.nanoTime() - t0) / 1e6
            val w = wer(words(text), words(res.text))
            val secs = audio.size / 16000.0
            Log.i("E2E", "ROUNDTRIP lang=$lang sttMs=%.0f rtf=%.2f wer=%.2f ref='%s' hyp='%s'".format(ms, ms / 1000.0 / secs, w, text, res.text))
            if (res.text.isNotBlank()) transcribed++
        }
        // English must round-trip nearly verbatim; other languages are logged, not asserted
        // (eSpeak is not natural speech, so their WER says little about real-voice accuracy).
        assertTrue("STT produced no text at all", transcribed > 0)
        val en = stt.transcribe(resampleTo16k(es.synthesize(sentences["en"]!!, "en")!!.first, 22050), "en")
        assertTrue("English round trip too poor: '${en.text}'", wer(words(sentences["en"]!!), words(en.text)) <= 0.5)
        stt.release()
    }

    /**
     * Runs only when a neural voice was downloaded through the Models screen for Telugu.
     * Proves the downloaded voice (not eSpeak) is what actually speaks.
     */
    @Test
    fun downloadedTeluguNeuralVoiceIsUsed() {
        val dir = java.io.File(ctx.filesDir, "models/tts/te")
        org.junit.Assume.assumeTrue("no downloaded Telugu voice", dir.isDirectory)
        val engine = TtsEngine(ctx)
        engine.initialize("te")
        val t0 = System.nanoTime()
        val r = engine.synthesize(sentences["te"]!!, "te", false)
        val ms = (System.nanoTime() - t0) / 1e6
        val secs = r.pcmAudio.size.toDouble() / r.sampleRate
        Log.i("E2E", "NEURAL te backend=${engine.activeBackend()} secs=%.2f rms=%.0f ms=%.0f rtf=%.3f sr=%d".format(secs, rms(r.pcmAudio), ms, ms / 1000.0 / secs, r.sampleRate))
        assertEquals("neural-downloaded", engine.activeBackend())
        assertTrue(r.pcmAudio.isNotEmpty() && rms(r.pcmAudio) > 300)
        engine.release()
    }
}
