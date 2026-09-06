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
        // Meta MMS-TTS converted voice (hosted tar.bz2) — covers the 5 Indic languages
        // with no Piper/Coqui sherpa voice. Loadable by sherpa-onnx VITS front-end.
        val mms = if (lang.code in mmsSha) mmsPack(lang.code) else null
        if (mms != null && mms.downloadUrl != null) {
            return mms.copy(id = "tts_${lang.code}")
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
     *  Converted + verified ONNX artifacts are HOSTED on the iTantra GitHub release
     *  `mms-tts` (see model-conversion/convert_mms_tts_onnx.py). Download → verify
     *  SHA-256 → extract → fully offline TTS for these languages. */
    fun mmsTtsPacks(): List<LanguageModelPack> = listOf(
        mmsPack("mr"),
        mmsPack("kn"),
        mmsPack("ta"),
        mmsPack("te"),
        mmsPack("or")
    )

    /** Host base for the converted MMS-TTS tar.bz2 archives (GitHub release). */
    private const val MMS_RELEASE_BASE =
        "https://github.com/NihalMishra3009/ITANTRA/releases/download/mms-tts"

    /** tar.bz2 archive SHA-256 (the thing actually downloaded + verified). */
    private val mmsSha = mapOf(
        "mr" to "fe4125718f5023e8fb1854199cfe145a2e54bb2efc26c5c329f914176ffacfcd",
        "kn" to "4f17983600ea2a56a01bc9dc86dc2a328e4c925dfb7f4e019b3ec6bb505c7698",
        "ta" to "7a3b2233cd546ff9b79852b9c63758e325d9d62aab436fe1976ee4ac89fa8fd4",
        "te" to "cb1222f7301e46d7e597d671c27f712d094c5d93d50d2dd49c47fc28ed2051bb",
        "or" to "a564d7f91d98b4311302ef5800168918ebbf6a887a94e834c3f233bc344c11c5"
    )
    private val mmsSize = mapOf("mr" to 107_756_072L, "kn" to 107_749_974L, "ta" to 107_733_234L, "te" to 107_768_327L, "or" to 107_748_930L)

    /** Real Meta MMS-TTS voice, checksum/size pinned from the hosted archive. */
    private fun mmsPack(code: String): LanguageModelPack {
        val lang = com.itantra.stt.SupportedLanguage.fromCode(code)
        val sha = mmsSha[code] ?: ""
        val sz = mmsSize[code] ?: 0L
        val convertedAvailable = convertedArtifactExists("models/tts/mms_${code}/model.onnx")
        return LanguageModelPack(
            id = "tts_mms_${code}",
            language = lang,
            role = ModelRole.TTS,
            modelName = "Meta MMS-TTS (${lang.displayName})",
            version = "2024-01",
            sizeBytes = sz,
            checksumSha256 = sha,
            license = "CC-BY-NC 4.0",
            runtime = Mlruntime.SHERPA_VITS,
            quantization = Quantization.FP32,
            sampleRate = 16000,
            supportedDeviceClass = DeviceClass.HIGH,
            downloadUrl = "$MMS_RELEASE_BASE/vits-mms-$code.tar.bz2",
            isArchive = true,
            isMultilingualShared = false,
            supportsLanguage = true,
            notes = if (convertedAvailable)
                "Meta MMS-TTS converted to sherpa VITS ONNX — bundled, offline, loadable."
            else "Meta MMS-TTS (covers ${lang.displayName}) — downloaded from iTantra release, SHA-256 verified, fully offline after install.",
            isEngine = false
        )
    }
}