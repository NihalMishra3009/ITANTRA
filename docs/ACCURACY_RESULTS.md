# Accuracy Results

**iTantra — ISRO Problem Statement 26173**

> **STATUS: NOT VERIFIED — no measured STT accuracy exists yet.**
>
> The previous "0.00% WER / 10 languages" table was removed. It was not produced
> by a real speech-evaluation run (the referenced `benchmark/stt_results.csv`
> contained identical reference/hypothesis pairs). Presenting it would be faking
> accuracy. Per SIH Phase 11, a real WER benchmark is required before any WER
> number is published here.

## 1. WER Methodology (target)

```
WAV file
  → real on-device STT (Whisper base int8, sherpa-onnx)
  → hypothesis transcription
  → normalization
  → WER vs a human reference transcript (never self-transcribed)
```

```text
WER = (S + D + I) / N
```

## 2. Honest status per language

| Language | WER | Evidence | Status |
|---|---|---|---|
| hi | none | no real audio evaluation performed yet | NOT VERIFIED |
| en | none | no real audio evaluation performed yet | NOT VERIFIED |
| (all others) | none | MVP scope is Hindi + English | NOT VERIFIED |

Recording source: real natural speech (20+ Hindi, 20+ English utterances), stored
under `benchmark/audio/{hi,en}/`, SIH Phase 11. Until that set exists this page
states nothing.

## 3. Tooling present

- Sliding-window tools + `TranscriptionResult` are exercised by unit tests
  (no-dummy + cancellation coverage), NOT reported as WER.
- The benchmark harness for real recordings is added in SIH Phase 11.
- No WER value in this repository is a measured value as of this writing.