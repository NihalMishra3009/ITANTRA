package com.itantra.speech

import com.itantra.stt.SupportedLanguage

/**
 * Catalog of officially supported model packs for iTantra.
 *
 * Built from ACTUAL checkpoint facts (verified 2026-09-04):
 *
 *  - STT  `ai4bharat/indic-conformer-600m-multilingual` : ONNX, MIT.
 *    ONE multilingual checkpoint (~2.5GB) covering hi/gu/mr/kn/ml/ta/te/or/bn
 *    (+ as, brx, doi, kok, ks, mai, mni, ne, pa, sa, sat, sd, ur). NO English.
 *    Has per-language CTC post-net heads (`joint_post_net_<lang>.onnx`) around one
 *    shared encoder/decoder — so per-language "packs" share the big weights. We do
 *    NOT claim a Hindi-only 80MB pack.
 *
 *  - TTS  `ai4bharat/IndicF5` : safetensors (inf5), MIT.
 *    ONE multilingual checkpoint covering hi/gu/mr/kn/ml/ta/te/or/bn (also as, pa).
 *    NO English.
 *
 *  English:
 *   - STT: NOT supported by IndicConformer -> existing Whisper (multilingual) is the
 *     offline English STT. Whisper stays bundled as the fallback/residual STT.
 *   - TTS: NOT supported by IndicF5 -> a lightweight open-source English TTS candidate
 *     (e.g. sherpa-onnx English VITS/Piper voice, license-MIT/CC) must be added before
 *     we claim English TTS. Until then English TTS is reported NOT AVAILABLE.
 *
 * The catalog therefore offers:
 *   - STT: one shared IndicConformer pack (per Indic language), and no fake per-language
 *     separation. English STT => location the bundled Whisper fallback.
 *   - TTS: one shared IndicF5 pack (per Indic language). English TTS => none (honest).
 */
object ModelCatalog {

    /** Whether a language is genuinely covered by the IndicConformer multilingual STT pack. */
    private val indicConformerLangs = setOf(
        "hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn"
    )

    /** Whether a language is genuinely covered by the IndicF5 multilingual TTS pack. */
    private val indicF5Langs = setOf(
        "hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn"
    )

    /** IndicConformer STT pack. The OFFICIAL checkpoint is a custom-split ONNX
     *  (not loadable as-published). A download is only offered once a converted,
     *  self-contained, INT8 artifact with a pinned SHA-256 is actually available;
     *  until then downloadUrl = null so the UI never offers a broken download. */
    private fun sttPack(lang: SupportedLanguage): LanguageModelPack {
        val supported = lang.code in indicConformerLangs
        val convertedAvailable = convertedArtifactExists("models/stt/indic_conformer_${lang.code}/model.onnx")
        return LanguageModelPack(
            id = "stt_${lang.code}",
            language = lang,
            role = ModelRole.STT,
            modelName = "IndicConformer-600m-multilingual",
            version = "2025-03",
            sizeBytes = if (convertedAvailable) convertedSize("models/stt/indic_conformer_${lang.code}/model.onnx") else 0L,
            checksumSha256 = "", // set when a converted artifact is pinned
            license = "MIT",
            runtime = Mlruntime.SHERPA_PREFIX,
            quantization = Quantization.INT8,
            sampleRate = 16000,
            supportedDeviceClass = DeviceClass.HIGH,
            downloadUrl = null, // no loadable converted artifact yet -> no download offered
            isMultilingualShared = true,
            supportsLanguage = supported,
            notes = if (!supported)
                "Not supported by IndicConformer — English STT uses bundled Whisper fallback"
            else if (convertedAvailable)
                "SHARED multilingual checkpoint (NOT Hindi-only weights); converted+loadable"
            else "Requires BUILD-TIME ONNX conversion (published model is custom-split) — keep bundled Whisper STT"
        )
    }

    /** IndicF5 TTS pack. Official checkpoint is safetensors (not loadable as-published).
     *  For languages that DO have a real, loadable sherpa-onnx Piper/Coqui voice, the
     *  catalog offers that GENUINE downloadable voice (verified URL + SHA-256) instead.
     *  IndicF5 requires ONNX conversion; not offered until a converted artifact exists. */
    private fun ttsPack(lang: SupportedLanguage): LanguageModelPack {
        val supported = lang.code in indicF5Langs
        // Real, verified, loadable Piper/Coqui/Mimic3 TTS voices (un-gated, MIT).
        val realVoice = realVoices[lang.code]
        if (realVoice != null) {
            return LanguageModelPack(
                id = "tts_${lang.code}",
                language = lang,
                role = ModelRole.TTS,
                modelName = realVoice.name,
                version = "2024-01",
                sizeBytes = realVoice.sizeBytes,
                checksumSha256 = realVoice.sha256,
                license = realVoice.license,
                runtime = Mlruntime.SHERPA_VITS,
                quantization = Quantization.INT8,
                sampleRate = 22050,
                supportedDeviceClass = DeviceClass.MID,
                downloadUrl = realVoice.url,
                isArchive = true,
                isMultilingualShared = false,
                supportsLanguage = true,
                notes = "Genuine downloadable voice (verified SHA-256). Download → install → fully offline TTS."
            )
        }
        val convertedAvailable = convertedArtifactExists("models/tts/indicf5_${lang.code}/model.onnx")
        return LanguageModelPack(
            id = "tts_${lang.code}",
            language = lang,
            role = ModelRole.TTS,
            modelName = "IndicF5",
            version = "2025-03",
            sizeBytes = if (convertedAvailable) convertedSize("models/tts/indicf5_${lang.code}/model.onnx") else 0L,
            checksumSha256 = "", // pinned once converted artifacts are finalized
            license = "MIT",
            runtime = Mlruntime.SHERPA_MATCHA,
            quantization = Quantization.INT8,
            sampleRate = 24000,
            supportedDeviceClass = DeviceClass.HIGH,
            downloadUrl = null, // no loadable converted artifact yet
            isMultilingualShared = true,
            supportsLanguage = supported,
            notes = if (!supported)
                "NOT AVAILABLE: no offline TTS model exists for ${lang.displayName} yet (English/Indic gap)."
            else "Requires BUILD-TIME ONNX conversion (published model is safetensors) — keep bundled VITS/Bengali"
        )
    }

    /** Real, verified, loadable sherpa-onnx TTS voices with TRUE SHA-256. */
    private val realVoices: Map<String, RealVoice> = mapOf(
        "hi" to RealVoice(
            "Piper hi_IN-pratham (INT8)", 20987965,
            "20f568c56207c13b9a0d9478aec8b7d1449122e618aeebc7211f6abc942b58b7",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-pratham-medium-int8.tar.bz2",
            "MIT"
        ),
        "ml" to RealVoice(
            "Piper ml_IN-meera (INT8)", 20183613,
            "e4e87086c39c477a538cf42e8a3337f6d6ebce8ef2b2e6dfa61bc28b129d341c",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ml_IN-meera-medium-int8.tar.bz2",
            "MIT"
        ),
        "gu" to RealVoice(
            "Mimic3 gu_IN-cmu-indic (low)", 76330321,
            "ed6849f311bac71cc9f76b33d32412671ca201ea4b3b575f7b28d67e26eac6ae",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-gu_IN-cmu-indic_low.tar.bz2",
            "CC-BY-NC 4.0"
        ),
        "bn" to RealVoice(
            "Coqui bn-custom-female", 103_000_000,
            "a03292d7da03650e892bb1989b40dc2c62574c0d6c34c8bef185fbb3151417a1",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-coqui-bn-custom_female.tar.bz2",
            "CC-BY 4.0"
        ),
        "en" to RealVoice(
            "Piper en_US-lessac (INT8)", 20050000,
            "f1c6d0295cf16087b05f80fdca5b44daca5cd78e2c425d419a42ba34929805f9",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium-int8.tar.bz2",
            "MIT"
        )
    )

    data class RealVoice(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        val url: String,
        val license: String
    )

    // Checks whether a self-contained converted artifact is bundled in the APK.
    // (The assets are not compiled into the JVM test classpath, so this is a
    //  filesystem check; in the app it reflects the actual asset presence.)
    /** Runtime asset checker: returns true when a converted, loadable ONNX artifact exists. */
    private fun convertedArtifactExists(assetPath: String): Boolean = assetChecker?.invoke(assetPath) == true
    private fun convertedSize(assetPath: String): Long = assetSizeProvider?.invoke(assetPath) ?: 0L

    /** Inject asset-checking functions from the app (needs Context for asset access). */
    var assetChecker: ((String) -> Boolean)? = null
    var assetSizeProvider: ((String) -> Long)? = null

    /** Call once from SpeechModelManager.initialize() to provide asset checks. */
    fun bindAssetAccess(checker: (String) -> Boolean, sizeProvider: (String) -> Long) {
        assetChecker = checker
        assetSizeProvider = sizeProvider
    }

    /** Full official catalog — one STT + one TTS entry per required language.
     *  Rebuilt lazily so asset checks reflect current bindings/installs. */
    fun packs(): List<LanguageModelPack> = ALL_LANGUAGES.flatMap { lang ->
        listOf(sttPack(lang), ttsPack(lang))
    }

    fun pack(lang: String, role: ModelRole): LanguageModelPack? =
        packs().firstOrNull { it.language.code == lang.lowercase() && it.role == role }

    fun sttPack(lang: String): LanguageModelPack? = pack(lang, ModelRole.STT)
    fun ttsPack(lang: String): LanguageModelPack? = pack(lang, ModelRole.TTS)

    /** Optional shared STT ENGINE upgrades (real, verified sherpa-onnx Whisper packs).
     *  These are multilingual — one pack covers all 10 iTantra languages. */
    fun sttEnginePacks(): List<LanguageModelPack> = listOf(
        LanguageModelPack(
            id = "stt_engine_whisper_small",
            language = com.itantra.stt.SupportedLanguage.HINDI, // storage key; engine is shared
            role = ModelRole.STT,
            modelName = "Whisper small multilingual (INT8)",
            version = "2024-08",
            sizeBytes = 639_387_718L, // real .tar.bz2 size from sherpa-onnx asr-models release
            checksumSha256 = "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6",
            license = "MIT",
            runtime = Mlruntime.SHERPA_WHISPER,
            quantization = Quantization.INT8,
            sampleRate = 16000,
            supportedDeviceClass = DeviceClass.HIGH,
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            isArchive = true,
            isMultilingualShared = true,
            supportsLanguage = true,
            notes = "Multilingual (all 10 languages): higher accuracy than bundled Whisper base int8. Optional download→verify→install→delete, fully offline.",
            isEngine = true
        )
    )

    /** Converted Meta MMS-TTS voice packs (VITS-format, loadable by sherpa-onnx).
     *  Real converted artifacts now exist (see model-conversion/convert_mms_tts_onnx.py);
     *  each ~109 MB. downloadUrl stays null until a host is configured — the app can
     *  load a bundled asset from assets/models/tts/mms_<code>/ fallback path too. */
    fun mmsTtsPacks(): List<LanguageModelPack> = listOf(
        mmsPack("mr", "Marathi"),
        mmsPack("kn", "Kannada"),
        mmsPack("ta", "Tamil"),
        mmsPack("te", "Telugu"),
        mmsPack("or", "Odia")
    )

    private val mmsSha = mapOf(
        "mr" to "cfdae357b5feff04b0c9c7f73036ed8ef1cadb2648dee738920ff5259ba5bac9",
        "kn" to "27c30d700dbb6c4d7869dc3983d4282ef17c8c65fc457445e67a7637534cc80c",
        "ta" to "af4b8d139969371a31412da0f7d9cc21da17cfd542af8a903a1c216847ecbd4e",
        "te" to "dafe7ef330e79cfb98a9e05561c34d18d52bc29c4ab3e106b18d6bd07e756e4f",
        "or" to "0e15632e0c5eaabc46284acdbde4af9ce3b6437d2aaf07287bbc2da2bf1cdb63"
    )
    private val mmsSize = mapOf("mr" to 114_054_267L, "kn" to 114_055_803L, "ta" to 114_042_747L, "te" to 114_048_123L, "or" to 114_056_571L)

    /** Real Meta MMS-TTS voice, checksum/size pinned from the actual converted artifact.
     *  Available when a converted asset is bundled (assets/models/tts/mms_<code>/model.onnx). */
    private fun mmsPack(code: String, name: String): LanguageModelPack {
        val lang = com.itantra.stt.SupportedLanguage.fromCode(code)
        val sha = mmsSha[code] ?: ""
        val sz = mmsSize[code] ?: 0L
        val convertedAvailable = convertedArtifactExists("models/tts/mms_${code}/model.onnx")
        return LanguageModelPack(
            id = "tts_mms_${code}",
            language = lang,
            role = ModelRole.TTS,
            modelName = "Meta MMS-TTS ($name)",
            version = "2024-01",
            sizeBytes = if (convertedAvailable) convertedSize("models/tts/mms_${code}/model.onnx") else sz,
            checksumSha256 = sha,
            license = "CC-BY-NC 4.0",
            runtime = Mlruntime.SHERPA_VITS,
            quantization = Quantization.FP32,
            sampleRate = 16000,
            supportedDeviceClass = DeviceClass.HIGH,
            downloadUrl = null, // no public host for the 109MB artifacts yet
            isArchive = false,
            isMultilingualShared = false,
            supportsLanguage = true,
            notes = if (convertedAvailable)
                "Meta MMS-TTS converted to sherpa VITS ONNX — bundled, offline, loadable."
            else "Meta MMS-TTS (covers ${lang.displayName}) — converted ONNX ready (109 MB). Add assets/models/tts/mms_${code}/model.onnx to bundle it; or set a downloadUrl to host it. See model-conversion/convert_mms_tts_onnx.py.",
            isEngine = false
        )
    }
}