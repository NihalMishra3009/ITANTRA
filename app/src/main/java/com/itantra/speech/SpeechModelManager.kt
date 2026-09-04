package com.itantra.speech

import android.content.Context
import android.util.Log
import com.itantra.stt.SttEngine
import com.itantra.stt.SttResult
import com.itantra.stt.SupportedLanguage
import com.itantra.tts.TtsEngine
import com.itantra.tts.TtsResult
import com.itantra.vad.VadEngine
import java.io.File

/**
 * Single facade the rest of ITANTRA talks to for all speech ML.
 *
 * Hides the concrete STT/TTS/VAD engines behind managers so a model can be
 * swapped/replaced without touching PipelineOrchestrator, UI, or networking.
 *
 * Responsibilities:
 *  - Lazy loading: only the currently-selected language's models are loaded.
 *  - Capability/fallback: delegates to [ModelPackRegistry] — never fakes
 *    availability; falls back to the existing engine when a candidate is absent.
 *  - Model selection: picks the best available pack for the current device class.
 *  - Quality-safety thresholds (configurable): reject/fall back when a candidate
 *    is below minimum quality or exceeds latency/RAM budgets.
 *
 * This is an EXTENSIBLE architecture: IndicConformer / IndicF5 / lightweight
 * VITS are declared as candidate packs. When their ONNX weights are bundled under
 * the documented asset paths, `bestAvailable(...)` returns them and a small
 * backend adapter (to be added when weights exist) is invoked. Until then the
 * existing Whisper/VITS engines remain the active offline implementation.
 */
class SpeechModelManager(
    context: Context,
    private val sttEngine: SttEngine? = null,
    private val ttsEngine: TtsEngine? = null,
    private val vadEngine: VadEngine? = null
) {
    companion object {
        private const val TAG = "SpeechModelManager"

        // Quality safety (configurable, engineering targets not hard SIH reqs)
        var maxSttLatencyMs = 5000L
        var minSttQuality = QualityTier.MID
        var minTtsQuality = QualityTier.MID
        var maxModelMb = 150
        var maxRtf = 1.0f
    }

    private val appContext = context.applicationContext

    /** Wire catalog asset checks to the app's asset files (honest availability). */
    private val unbinding = ModelCatalog.bindAssetAccess(
        checker = { path -> assetExists(path) },
        sizeProvider = { path -> assetSize(path) }
    )

    /** Download-once-then-offline model pack installer. */
    val distribution = ModelDistributionManager(appContext)

    private val registry = ModelPackRegistry(appContext, distribution)

    private fun assetExists(path: String): Boolean = try {
        appContext.assets.open(path).use { it.available() >= 0 }
    } catch (e: Exception) { false }

    private fun assetSize(path: String): Long = try {
        appContext.assets.open(path).use { it.available().toLong() }
    } catch (e: Exception) { 0L }
    private val deviceProfile: DeviceCapabilityProfile by lazy { DeviceProfiler.profile(appContext) }

    private var currentLanguage: SupportedLanguage = SupportedLanguage.HINDI
    private var currentSttPack: ModelPack? = null
    private var currentTtsPack: ModelPack? = null

    val deviceClass: DeviceClass get() = deviceProfile.deviceClass

    fun registry(): ModelPackRegistry = registry

    /** Report whether a language's STT is genuinely available (asset present). */
    fun sttAvailable(langCode: String): Boolean = registry.isAvailable(langCode, ModelRole.STT)

    /** Report whether a language's TTS is genuinely available (asset present). */
    fun ttsAvailable(langCode: String): Boolean = registry.isAvailable(langCode, ModelRole.TTS)

    /** Switch active language — lazy-loads only this language's models. */
    fun selectLanguage(lang: SupportedLanguage) {
        if (currentLanguage == lang) return
        currentLanguage = lang
        currentSttPack = selectBestPack(lang, ModelRole.STT)
        currentTtsPack = selectBestPack(lang, ModelRole.TTS)
        Log.i(TAG, "Language=${lang.code} STT=${currentSttPack?.modelName ?: "none"} " +
                "TTS=${currentTtsPack?.modelName ?: "none"} class=${deviceProfile.deviceClass}")

        // Re-initialize engines on the existing pipeline.
        if (sttAvailable(lang.code)) {
            sttEngine?.initialize(lang.code)
        }
        if (ttsAvailable(lang.code)) {
            ttsEngine?.initialize(lang.code)
        }
    }

    fun currentLanguage(): SupportedLanguage = currentLanguage

    /** Model-selection engine (Phase 9): best available pack for device class. */
    private fun selectBestPack(lang: SupportedLanguage, role: ModelRole): ModelPack? {
        val available = registry.forLanguage(lang.code, role).filter { it.sizeBytes > 0 }
        if (available.isEmpty()) return null
        val affordable = available
            .filter { it.supportedDeviceClass.ordinal <= deviceProfile.deviceClass.ordinal }
        if (affordable.isEmpty()) return null
        // SCORE = weight*quality + weight*sizeEfficiency (+ implicit RAM/latency via class)
        return affordable.maxByOrNull { modelScore(it) }
    }

    /**
     * Weighted practical score (Phase 9), balancing quality vs size efficiency.
     * Weights are engineering choices; can be tuned. Higher is better.
     * score = qualityWeight*quality + sizeWeight*sizeEfficiency
     *   quality = 0..1 (from QualityTier ordinal)
     *   sizeEfficiency = min(1, budgetMb / sizeMb) — favours smaller models
     * The size/memory/latency constraints are enforced separately by the
     * quality-safety gate (isAcceptable) so we don't overcomplicate scoring.
     */
    private fun modelScore(pack: ModelPack): Double {
        val quality = when (pack.quality) {
            QualityTier.LOW -> 0.4
            QualityTier.MID -> 0.7
            QualityTier.HIGH -> 1.0
        }
        val sizeMb = pack.sizeMb.coerceAtLeast(0.1)
        val sizeEfficiency = (maxModelMb / sizeMb).coerceAtMost(1.0).coerceAtLeast(0.0)
        val qualityWeight = 2.0
        val sizeWeight = 1.0
        return (qualityWeight * quality) + (sizeWeight * sizeEfficiency)
    }

    /**
     * STT entry point used by the pipeline. Routes to the existing Whisper engine
     * (the active implementation). A future IndicConformer backend would be
     * selected here based on currentSttPack.runtime.
     */
    fun transcribe(audioChunk: FloatArray, langCode: String = currentLanguage.code): SttResult {
        val engine = sttEngine ?: return SttResult("", langCode, 0)
        return engine.transcribe(audioChunk, langCode)
    }

    /**
     * TTS entry point. Routes to the existing VITS engine (active implementation).
     * A future IndicF5 / lightweight-VITS backend would be chosen here.
     * @return empty audio if the language has no real TTS model — never fake audio.
     */
    fun synthesize(text: String, langCode: String = currentLanguage.code, isAlert: Boolean = false): TtsResult {
        val engine = ttsEngine ?: return TtsResult(ShortArray(0), 24000, 0, langCode)
        return engine.synthesize(text, langCode, isAlert)
    }

    /**
     * Load a downloaded (file-path) voice pack for synthesis. Crash-guarded: if the
     * runtime cannot load it, returns false and the bundled voice stays in use.
     */
    fun loadDownloadedVoice(langCode: String): Boolean =
        ttsEngine?.loadDownloadedVoice(langCode) ?: false

    /**
     * Quality-safety gate (Phase 21): decide whether a freshly-benchmarked model
     * is acceptable for use given the configured thresholds.
     */
    fun isAcceptable(
        pack: ModelPack,
        sttLatencyMs: Long = 0,
        rtf: Float = 0f,
        synthRamMb: Int = 0
    ): AcceptedDecision {
        if (pack.sizeBytes > maxModelMb * 1024L * 1024L) {
            return AcceptedDecision.REJECTED_SIZE(pack)
        }
        if (pack.quality.ordinal < (if (pack.role == ModelRole.STT) minSttQuality else minTtsQuality).ordinal) {
            return AcceptedDecision.REJECTED_QUALITY(pack)
        }
        if (pack.role == ModelRole.STT && sttLatencyMs > maxSttLatencyMs) {
            return AcceptedDecision.REJECTED_LATENCY(pack)
        }
        if (pack.role == ModelRole.STT && rtf > maxRtf) {
            return AcceptedDecision.REJECTED_RTF(pack)
        }
        if (pack.role == ModelRole.TTS && synthRamMb > 0 && !deviceProfile.canAffordModel(synthRamMb)) {
            return AcceptedDecision.REJECTED_RAM(pack)
        }
        return AcceptedDecision.ACCEPTED(pack)
    }

    fun describeDevice(): String {
        val p = deviceProfile
        return "RAM ${p.totalRamMb}MB | ${p.cpuCoreCount} cores | ${p.cpuArchitecture} | " +
                "SDK ${p.androidSdk} | class ${p.deviceClass}"
    }
    /** Speech ML facade — routes STT/TTS through managers for lazy loading + fallback
     *  and supports download-once-then-offline language model packs. */
    /** Speech ML facade — routes STT/TTS through managers for lazy loading + fallback
     *  and supports download-once-then-offline language model packs. */
    fun installLanguagePack(pack: LanguageModelPack, onProgress: (Float) -> Unit = {}, onDone: (Result<File>) -> Unit) {
        distribution.install(pack, onProgress, onDone)
    }

    fun distributionManager(): ModelDistributionManager = distribution
    fun storageManager(): ModelStorageManager = distribution.storageManager()
    fun catalog(): List<LanguageModelPack> = ModelCatalog.packs()

}

/** Result of the quality-safety gate (Phase 21). */
sealed class AcceptedDecision {
    data class ACCEPTED(val pack: ModelPack) : AcceptedDecision()
    data class REJECTED_SIZE(val pack: ModelPack) : AcceptedDecision()
    data class REJECTED_QUALITY(val pack: ModelPack) : AcceptedDecision()
    data class REJECTED_LATENCY(val pack: ModelPack) : AcceptedDecision()
    data class REJECTED_RTF(val pack: ModelPack) : AcceptedDecision()
    data class REJECTED_RAM(val pack: ModelPack) : AcceptedDecision()
}
