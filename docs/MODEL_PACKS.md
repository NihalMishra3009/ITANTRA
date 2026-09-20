# iTantra Model Packs

## Base APK + OptionalLanguage Packs

ITANTRA is:

> one lightweight offline communication application with **optional neural language packs**.

The base APK ships:
- UI, networking, Bluetooth, Wi-Fi Direct, DTN, routing, encryption, packet codec
- audio pipeline (AudioRecorder/Player/Focus), VAD, model-management framework
- the existing **Whisper multilingual STT** asset (covers all 10 languages as STT fallback)
- the existing **Bengali VITS** asset (TTS baseline/fallback)

It does NOT bundle every language's IndicConformer / IndicF5 weights. Those are
installed separately by the user via **Models** → per-language Download.

## Storage Layout

```
{filesDir}/models/
    stt/<lang>/   model.onnx (+ version.txt, checksum.sha256)
    tts/<lang>/   model.onnx (+ version.txt, checksum.sha256)
```

- STT and TTS live in separate directories → independent install/delete.
- `ModelStorageManager` owns all paths, existence, size, delete, checksum, version.

## Statuses (filesystem-derived)

NOT_INSTALLED → DOWNLOADING → VERIFYING → INSTALLED → LOADING → LOADED
(FAILED / CORRUPTED / UPDATE_AVAILABLE on error or newer version)

Status is derived from actual files + in-progress download flags — never a UI boolean.

## Catalog (verified from actual checkpoints, 2026-09-04)

| Role | Model | License | Languages | Type |
|------|-------|---------|-----------|------|
| STT | IndicConformer-600m-multilingual | MIT | hi,gu,mr,kn,ml,ta,te,or,bn | **single multilingual ONNX checkpoint** (~2.5GB) |
| TTS | IndicF5 | MIT | hi,gu,mr,kn,ml,ta,te,or,bn | **single multilingual checkpoint** |
| STT | Whisper base int8 (fallback) | MIT | **all 10 including English** | bundled in APK |
| TTS | eSpeak NG | GPL-3.0-or-later | all 10 | bundled in APK (0.75 MB data + native lib); the open-source floor |
| TTS | Piper en (LJ Speech), mr, te, bn | Public domain / CC-BY-SA / CC-BY / CC-BY-SA | en,mr,te,bn | optional downloadable neural voices (built by `model-conversion/build_piper_sherpa_pack.py`) |

**Honesty notes:**
- IndicConformer is ONE shared checkpoint, NOT 10 per-language files. Downloading
  "Hindi STT" installs the shared checkpoint (+ the Hindi CTC head) — the app reports
  the real shared size, never a fabricated 1/10 split.
- IndicF5 has **no English** and is not yet converted; all 10 languages instead use
  the bundled open-source eSpeak NG voice, with optional Piper neural voices for
  en,mr,te,bn (each SHA-256 verified). Non-open voices (MMS, Mimic3, Piper hi/ml) are excluded.
- IndicConformer has **no English ASR**. English STT uses the bundled Whisper fallback.

## Independent STT/TTS

A user may install:
- Hindi STT only
- Hindi TTS only
- Hindi STT + TTS
- none (relay)

Relay devices need no speech model — only networking/routing/DTN/encryption.

## Security

Every downloadable pack is verified by SHA-256 before being moved into place
(atomic install). Corrupted or mismatched downloads are never installed and are
reported CORRUPTED. Acquisition uses HTTPS.

## Offline Guarantee

> Internet may be used for **optional initial model acquisition/update**.
> Once a pack is installed, speech inference and ITANTRA communication operate
> **fully offline**.