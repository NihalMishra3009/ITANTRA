# iTantra Model Licenses

Licenses for models referenced by the repository. Publicly downloadable does not automatically mean unrestricted redistribution; each entry notes the actual license and any use caveat.

## Bundled Assets (in APK)

| Model | Language | License | Notes |
|-------|----------|---------|-------|
| Whisper base (encoder/decoder int8 ONNX) | all 10 | MIT | OpenAI Whisper code/weights MIT |
| VITS `vits_bn` | Bengali | MIT | Bundled `model.onnx` + `tokens.txt` |
| Silero VAD (v4) | — | MIT | Asset bundled; VAD active detector is Adaptive Energy VAD until a live on-device Silero discrimination test passes (SIH Phase 8) |

## Downloadable Voice Packs (verified hosted)

| Model | Language | License | Non-commercial? | Notes |
|-------|----------|---------|-----------------|-------|
| Piper hi_IN-pratham (INT8) | Hindi | MIT | No | sherpa-onnx release, verified SHA-256 |
| Piper en_US-lessac (INT8) | English | MIT | No | sherpa-onnx release, verified SHA-256 |
| Piper ml_IN-meera (INT8) | Malayalam | MIT | No | sherpa-onnx release, verified SHA-256 |
| Coqui bn-custom_female | Bengali | CC-BY 4.0 | No | sherpa-onnx release, verified SHA-256 |
| Mimic3 gu_IN-cmu-indic (low) | Gujarati | **CC-BY-NC 4.0** | **Yes** | Non-commercial; flagged in Models UI |
| Meta MMS-TTS (mr/kn/ta/te/or) | Marathi/Kannada/Tamil/Telugu/Odia | **CC-BY-NC 4.0** | **Yes** | Non-commercial; hosted on iTantra release, SHA-256 verified |

**Policy (enforced in code):** `ModelCatalog.OPEN_SOURCE_ONLY = true`. The two CC-BY-NC
rows above (Mimic3 Gujarati, Meta MMS-TTS mr/kn/ta/te/or) are **not offered, downloaded
or advertised**; those six languages report TTS as NOT AVAILABLE until an open-licensed
voice with a verified URL and SHA-256 is added. Effective open TTS today: Hindi, English,
Malayalam, Bengali. `ModelCatalogTest.testOpenSourceOnlyPolicyOffersNoNonCommercialPack`
guards this. The catalog code for the NC voices is retained behind the flag only.

**Unresolved license inconsistency:** `ModelPackRegistry` labels IndicConformer and
IndicF5 "CC-BY-NC (verify)" while `ModelCatalog` labels them MIT. Neither is an installed
asset; confirm the per-checkpoint license upstream before adopting either.

## Offline Neural Translation (cross-language)

| Model | Pair | License | Runtime | Notes |
|-------|------|---------|---------|-------|
| Helsinki-NLP Opus-MT | hi ↔ en | **Apache-2.0** (open-source approved) | ONNX Runtime (bundled libonnxruntime.so) | Converted via model-conversion/convert_opus_mt_onnx.py → encoder_model.onnx + decoder_model.onnx + config.json + manifest.json + tokenizer/ under models/translation/hi-en/ and en-hi/. Hosted with pinned downloadUrl + SHA-256 (release `mt-onnx`). |

Translation happens **sender-side, before encryption** — the wire carries only the
final target-language compact text. Relay nodes never require a translation (or
speech) model. SOS/emergency traffic never passes through translation.

**Important:** CC-BY-NC (NonCommercial) voices are available for the offline demo and are
installed/downloaded as regular packs, but they are **not open-source-approved** per the
project's license rule. They are flagged with a `⚠` license badge in the Models screen and
documented in the language card notes as NON-COMMERCIAL. The Opus-MT translation model is
Apache-2.0 and fully open-source approved.

## Candidate Models (declared in catalog, NOT runtime assets)

These are registered in `ModelCatalog` as future candidates. Their `downloadUrl` is `null`
and no Android-loadable ONNX artifact is available.

| Model | Family | License status to verify |
|-------|--------|--------------------------|
| IndicConformer (AI4Bharat) | Paraformer CTC | Verify per-checkpoint; published checkpoint is custom-split, requires ONNX conversion. Not loadable. |
| IndicF5 (AI4Bharat) | Flow-matching TTS | Verify per-checkpoint; published weights are safetensors, requires ONNX conversion. Not loadable. |

## Rule

Do **not** include a model merely because its page is public. Confirm the **weights license**,
**tokenizer license**, and **vocoder license** allow redistribution for a hackathon.
Non-commercial licenses (CC-BY-NC) are documented but flagged as restrictions, not presented
as approved open-source.
