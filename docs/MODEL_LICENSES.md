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
| Helsinki-NLP Opus-MT | hi ↔ en, en → mr | **Apache-2.0** (open-source approved) | ONNX Runtime (bundled libonnxruntime.so), INT8-quantized | Converted via model-conversion/convert_opus_mt_onnx.py → encoder_model.onnx + decoder_model.onnx + config.json + manifest.json + tokenizer/{source.spm,target.spm,vocab.tsv} under models/translation/{hi-en,en-hi,en-mr}/. Hosted with pinned downloadUrl + SHA-256 (release `mt-onnx`). mr→en was converted but is **not hosted** — its own greedy decode occasionally reproduces unrelated training-corpus text on longer sentences; see docs/CURRENT_STATUS.md section N. |

Translation happens **sender-side, before encryption** — the wire carries only the
final target-language compact text. Relay nodes never require a translation (or
speech) model. SOS/emergency traffic never passes through translation.

## Offline Spell-Check Word Lists (English, Hindi, Marathi)

| Dictionary | Language | License | Source | Notes |
|------------|----------|---------|--------|-------|
| `hi_IN` | Hindi | GPL-2.0 (`hi_IN/COPYING`) | `LibreOffice/dictionaries` @ commit `32b006a2c22a4ac7e8ed3f03346f7b3d85a970a4` | `hi_IN/hi_IN.dic` + `hi_IN/hi_IN.aff` |
| `mr_IN` | Marathi | GPL-2.0 (`mr_IN/COPYING`) | same repo/commit | `mr_IN/mr_IN.dic` + `mr_IN/mr_IN.aff` |
| `en_US` | English | GPL-2.0 (`en/license.txt`) | same repo/commit | `en/en_US.dic` + `en/en_US.aff` |

These are Hunspell dictionaries: a small set of root words plus affix (prefix/suffix) rules.
Hindi and Marathi are morphologically rich — case suffixes and postpositions are glued onto
the stem, sometimes via two CHAINED suffix applications (see the script's
`_expand_suffix_chain` for a worked example: Marathi "घर" house → "घरा" → "घरात" in-the-house) —
so a flat list of dictionary-form roots would falsely flag ordinary, correctly spelled
inflected words as typos. `model-conversion/build_spellcheck_wordlist.py` expands every root
into all of its valid surface forms using the dictionary's own affix rules (via
[spylls](https://github.com/zverok/spylls), a pure-Python Hunspell reimplementation, MPL-2.0,
**build-time only — never bundled into the app**), validates every candidate against spylls'
own Hunspell-accurate `lookup()`, and writes one binary **Bloom filter** per language to
`app/src/main/assets/spellcheck/{en,hi,mr}.bloom` (~1% target false-positive rate). A flat
`HashSet<String>` was tried first: Marathi alone expands to ~2.9 million surface forms, which
came out to an 8.7 MiB gzip-compressed asset (over this project's ~5 MiB low-end-device budget)
and would cost many tens of MB of live heap as Kotlin String objects. A Bloom filter fixes both:
~1.2 bytes/word (Marathi ≈ 3.3 MiB, English/Hindi smaller still), and it can only ever answer
"definitely not present" (correctly flags a typo) or "probably present" (a small, tunable
false-positive rate — an occasional missed typo, an acceptable failure mode) — it structurally
cannot say "not present" for a word that IS in the set, so it can never cause the one
unacceptable failure mode, flagging a real word as misspelled. `com.itantra.stt
.SpellCheckEngine` loads these lazily at runtime (its internal `BloomFilter` reader must stay
bit-for-bit identical to the Python writer's FNV-1a/FNV-1 double-hashing scheme — see both
files' doc comments) — flagging only, never suggestions or auto-correction, and it is a silent
no-op for the seven languages with no shipped filter.

As with eSpeak NG (GPL-3.0-or-later, statically linked — see "Bundled Assets" above), this adds
GPL-2.0-licensed *data* (not code) to an APK that is already a GPL combined work, so this
introduces no new licensing conflict.

Rebuild with:

```bash
pip install spylls==0.1.7
python model-conversion/build_spellcheck_wordlist.py
```

The script pins the source commit and verifies each downloaded `.dic`/`.aff` file's SHA-256
before use, and refuses to write a word list whose root-to-surface-form expansion ratio looks
degenerate (a sign the affix rules did not actually run).

## Candidate Models (declared in catalog, NOT runtime assets)

These are registered in `ModelCatalog` as future candidates. Their `downloadUrl` is `null`
and no Android-loadable ONNX artifact is available.

| Model | Family | License status to verify |
|-------|--------|--------------------------|
| IndicConformer (AI4Bharat, `ai4bharat/indic-conformer-600m-multilingual`) | Conformer RNNT+CTC ASR | **License confirmed MIT** (read from the model's own HF `cardData`/tags on 2026-09-28 — supersedes the CC-BY-NC label in `ModelPackRegistry`, which is stale/wrong). Ships real `.onnx` files (not safetensors), and covers 22 languages including **Odia**, which Whisper in this app cannot decode at all (`SttEngine.WHISPER_UNSUPPORTED`) — would be a genuine fix for that gap. **Not adopted: full fp32, ~2.56 GB total (404 files — a ~3MB encoder graph backed by ~2.4GB of external-data weight tensors), no int8/fp16 variant published anywhere** — 30-60x this app's current int8 Whisper-base footprint and not viable on the low-end-device budget as shipped. Every known real-world usage (50+ HF Spaces, AI4Bharat's own repos) is server-side Python/NeMo; no mobile or edge deployment precedent exists. The repo is also HF-gated as of 2026-09-28 (metadata public, source/weights require accepting terms), so the actual inference pipeline (CTC vs RNNT decode selection, per-language joint-net wiring) is unverified pending that. Revisit only if a quantized release appears, or if int8-quantizing it externally (same approach used for the Opus-MT translation packs, see `model-conversion/convert_opus_mt_onnx.py`) is taken on as its own project. |
| IndicF5 (AI4Bharat) | Flow-matching TTS | Verify per-checkpoint; published weights are safetensors, requires ONNX conversion. Not loadable. |

## Rule

Do **not** include a model merely because its page is public. Confirm the **weights license**,
**dataset license** (voices inherit it), **tokenizer license**, and **vocoder license**. Read the
voice's own `MODEL_CARD`, not a catalog summary: two "MIT" claims in this repo were wrong.
Non-open licenses (CC-BY-NC, "see URL", custom research terms) are not offered.
