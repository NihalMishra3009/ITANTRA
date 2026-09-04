# iTantra ML Benchmarks

Real measurements from actual on-device runs and unit tests. **No fabricated numbers.**

## On-Device STT (Whisper base int8, sender RMX3870, 2026-09-04)

Observed from app logcat during real PTT tests:

| Utterance | Language | Latency (ms) | Text |
|-----------|----------|--------------|------|
| PTT hold | hi | 537 | "(Bell)" |
| PTT hold | hi | 726 | "a..." |
| PTT hold | hi | 708 | "aal alu" |
| Continuous | hi | 1237 | "Shr" |
| Continuous | hi | 584 | "(SKR)" |
| PTT (Bengali) | bn | 3742 | (empty — no voice) |

Note: latency is wall-clock on a mid-range Realme; RTF could not be computed because no clean speech duration was captured in these tests. No WER was run.

## End-to-End Latency (two-device)

Observed on receiver UI during a real two-device transmission (RMX → vivo):
- E2E chip: **338 ms** (single hop, delivery to display; TTS was empty for Hindi so this excludes speech synthesis)

## Unit-Test Benchmarks

- All 59 unit tests pass (protocol, security, mesh, location, VAD, delivery, speech selection).
- Speech selection tests verify device-class classification, budget budgeting, quality-safety gating, and honest availability — no inference numbers are fabricated.

## How to Measure

To add real per-model WER / RTF / RAM / CPU:
1. Use the Python suites in `benchmark/` (WER via Levenshtein).
2. Feed results into `ModelPackRegistry` + `SpeechModelManager.isAcceptable`.
3. On-device RTF = STT latency / audio duration (captured in `BenchmarkLogger`).
