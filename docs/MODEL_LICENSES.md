# iTantra Model Licenses

Licenses for models referenced by the repository. Publicly downloadable does not automatically mean unrestricted redistribution; each entry notes the actual license and any use caveat.

## Bundled Assets (in APK)

| Model | Language | License | Notes |
|-------|----------|---------|-------|
| Whisper base (encoder/decoder int8 ONNX) | all 10 | MIT | OpenAI Whisper code/weights MIT. **Odia is probably not supported by Whisper** (unverified); no WER has been measured for any language. |
| eSpeak NG (`libitantra_espeak.so` + `espeak-ng-data.zip`) | all 10 | **GPL-3.0-or-later** | Rule-based TTS. Source vendored under `app/src/main/cpp/third_party/espeak-ng` (k2-fsa fork @ `ed530aa`, the revision sherpa-onnx pins). Statically linked, so the distributed APK is a GPL-3.0-or-later combined work. |
| Silero VAD (v4) | — | MIT | Asset bundled; the active detector is Adaptive Energy VAD until a live on-device test passes |

Removed: the bundled Coqui Bengali VITS (`vits_bn`, 114 MB). This document previously claimed MIT for it while
the same model was listed elsewhere as CC-BY 4.0; the license could not be verified, so it was dropped.

## Optional Downloadable Neural Voices (open-licensed only)

Each license below was read from the voice's own `MODEL_CARD` (rhasspy/piper-voices) on 2026-09-20.

| Voice | Language | License | Source |
|-------|----------|---------|--------|
| Piper en_US-ljspeech (high, INT8) | English | Public domain | LJ Speech; prebuilt by sherpa-onnx |
| Piper mr_IN-google (INT8) | Marathi | CC-BY-SA 4.0 | OpenSLR 64; converted by `model-conversion/build_piper_sherpa_pack.py` |
| Piper te_IN-venkatesh (INT8) | Telugu | CC-BY 4.0 | AI4Bharat IndicVoices-R; converted by the same script |
| Piper bn_BD-google (INT8) | Bengali | CC-BY-SA 4.0 + CMU Indic license | OpenSLR 37 + festvox CMU Indic; converted by the same script |

The three converted packs are verified by synthesizing real speech through sherpa-onnx before packaging (the
script refuses to write an archive whose audio is empty or silent). They are pinned by SHA-256 in `ModelCatalog`
against the release `tts-open-v1` on the project repository, **which must be uploaded by someone with write
access** (see "Publishing the voice packs" below). Until it is, the in-app download for those four voices fails
and the bundled eSpeak NG voice is used; no language is ever without TTS.

**Known caveat (Marathi, Bengali):** these voices define five multi-character English diphthong tokens that
sherpa-onnx cannot read; they are skipped. eSpeak's Indic output is per-codepoint so they are never emitted, but
no human listening test has been done.

## Excluded voices (NOT open-source; never offered while `OPEN_SOURCE_ONLY = true`)

| Voice | Language | License / reason |
|-------|----------|------------------|
| Meta MMS-TTS | mr, kn, ta, te, or | CC-BY-NC 4.0 (non-commercial) |
| Mimic3 gu_IN-cmu-indic | Gujarati | CC-BY-NC 4.0 |
| Piper hi_IN-pratham / priyamvada | Hindi | **CC-BY-NC-SA 4.0** dataset. (An earlier revision of this document wrongly listed Hindi as MIT.) |
| Piper hi_IN-rohan | Hindi | IITM IndicTTS license, not an open license |
| Piper ml_IN-meera / arjun | Malayalam | "See URL" (IndicTTS Malayalam corpus): could not be verified as open |
| Piper en_US-lessac | English | Blizzard 2013 custom license, not an open license |
| Coqui bn-custom_female | Bengali | License could not be verified |

**Policy (enforced in code):** `ModelCatalog.OPEN_SOURCE_ONLY = true`. `ModelCatalogTest` fails if any TTS pack
that can be downloaded has a license that is not on the open allow-list (`ModelCatalog.isOpenLicense`).

**Quality caveat:** Hindi, Malayalam, Gujarati, Kannada, Tamil and Odia are served ONLY by eSpeak NG. It is
intelligible but robotic, which will score poorly on "human legibility and flow". The route to a neural voice
for these six is training Piper voices on AI4Bharat IndicVoices-R (CC-BY 4.0); AI4Bharat's Indic-TTS
FastPitch/HiFi-GAN checkpoints (MIT repo, but trained on the IITM IndicTTS corpus whose license is unclear) were
not adopted.

**Unresolved license inconsistency:** `ModelPackRegistry` labels IndicConformer and IndicF5 "CC-BY-NC (verify)"
while `ModelCatalog` labels them MIT. Neither is an installed asset; confirm upstream before adopting either.

### Publishing the voice packs

```bash
# from a checkout with write access to the repo named in ModelCatalog.OPEN_TTS_RELEASE_BASE
gh release create tts-open-v1 model-conversion/converted/open-tts/*.tar.bz2   --title "Open-licensed Piper voices (mr, te, bn)"   --notes "Converted by model-conversion/build_piper_sherpa_pack.py; see docs/MODEL_LICENSES.md"
```

Rebuild an archive with `python model-conversion/build_piper_sherpa_pack.py --help`; the printed SHA-256 must
match the value pinned in `ModelCatalog.realVoices`.

## Offline Neural Translation (cross-language)

| Model | Pair | License | Runtime | Notes |
|-------|------|---------|---------|-------|
| Helsinki-NLP Opus-MT | hi ↔ en | **Apache-2.0** (open-source approved) | ONNX Runtime (bundled libonnxruntime.so) | Converted via model-conversion/convert_opus_mt_onnx.py → encoder_model.onnx + decoder_model.onnx + config.json + manifest.json + tokenizer/ under models/translation/hi-en/ and en-hi/. Hosted with pinned downloadUrl + SHA-256 (release `mt-onnx`). |

Translation happens **sender-side, before encryption** — the wire carries only the
final target-language compact text. Relay nodes never require a translation (or
speech) model. SOS/emergency traffic never passes through translation.

## Candidate Models (declared in catalog, NOT runtime assets)

These are registered in `ModelCatalog` as future candidates. Their `downloadUrl` is `null`
and no Android-loadable ONNX artifact is available.

| Model | Family | License status to verify |
|-------|--------|--------------------------|
| IndicConformer (AI4Bharat) | Paraformer CTC | Verify per-checkpoint; published checkpoint is custom-split, requires ONNX conversion. Not loadable. |
| IndicF5 (AI4Bharat) | Flow-matching TTS | Verify per-checkpoint; published weights are safetensors, requires ONNX conversion. Not loadable. |

## Rule

Do **not** include a model merely because its page is public. Confirm the **weights license**,
**dataset license** (voices inherit it), **tokenizer license**, and **vocoder license**. Read the
voice's own `MODEL_CARD`, not a catalog summary: two "MIT" claims in this repo were wrong.
Non-open licenses (CC-BY-NC, "see URL", custom research terms) are not offered.
