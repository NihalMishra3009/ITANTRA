# iTantra Model Licenses

Licenses for models referenced by the repository. Publicly downloadable does not automatically mean unrestricted redistribution; each entry notes the actual license and any use caveat.

## Bundled Assets (in APK)

| Model | Language | License | Notes |
|-------|----------|---------|-------|
| Whisper base (encoder/decoder int8 ONNX) | all 10 | MIT | OpenAI Whisper code/weights MIT |
| VITS `vits_bn` | Bengali | MIT | Bundled `model.onnx` + `tokens.txt` |
| Silero VAD (v4) | — | MIT | Asset bundled; v4 format incompatible with this runtime, energy fallback active |

## Downloadable Voice Packs (verified hosted)

| Model | Language | License | Non-commercial? | Notes |
|-------|----------|---------|-----------------|-------|
| Piper hi_IN-pratham (INT8) | Hindi | MIT | No | sherpa-onnx release, verified SHA-256 |
| Piper en_US-lessac (INT8) | English | MIT | No | sherpa-onnx release, verified SHA-256 |
| Piper ml_IN-meera (INT8) | Malayalam | MIT | No | sherpa-onnx release, verified SHA-256 |
| Coqui bn-custom_female | Bengali | CC-BY 4.0 | No | sherpa-onnx release, verified SHA-256 |
| Mimic3 gu_IN-cmu-indic (low) | Gujarati | **CC-BY-NC 4.0** | **Yes** | Non-commercial; flagged in Models UI |
| Meta MMS-TTS (mr/kn/ta/te/or) | Marathi/Kannada/Tamil/Telugu/Odia | **CC-BY-NC 4.0** | **Yes** | Non-commercial; hosted on iTantra release, SHA-256 verified |

**Important:** CC-BY-NC (NonCommercial) voices are available for the offline demo and are
installed/downloaded as regular packs, but they are **not open-source-approved** per the
project's license rule. They are flagged with a `⚠` license badge in the Models screen and
documented in the language card notes as NON-COMMERCIAL.

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
