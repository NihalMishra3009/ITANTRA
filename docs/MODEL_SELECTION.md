# iTantra Model Selection

How the app picks a model per language, per device class. Decisions are benchmark- and asset-driven; nothing is chosen purely on marketing.

## Selection Engine (Phase 9)

For each language + role:

1. Gather all candidate packs for that language+role that are **actually available** (asset present).
2. Filter to packs the current device class can afford (`supportedDeviceClass <= deviceClass`).
3. Score each with a weighted formula:

```
SCORE = qualityWeight * quality + sizeWeight * sizeEfficiency
```

where `quality` ∈ {LOW=0.4, MID=0.7, HIGH=1.0}, `sizeEfficiency = min(1, budgetMb / sizeMb)`, `qualityWeight = 2.0`, `sizeWeight = 1.0`. Lower size and higher quality both help; quality dominates.

4. Enforce the **quality-safety gate** (`SpeechModelManager.isAcceptable`): reject a model that is too large (>150 MB), below minimum quality tier, too slow, or too RAM-heavy for the device.

## Candidate Landscape

| Language | STT available | TTS available |
|----------|---------------|---------------|
| Hindi | ✓ Whisper | ✗ (VITS not bundled) |
| ... | ✓ Whisper | ✗ |
| Bengali | ✓ Whisper | ✓ VITS (existing) |
| English | ✓ Whisper | ✗ |

STT falls back: if a candidate like IndicConformer were bundled, it would be selected first (HIGH quality); otherwise the existing Whisper model is used. TTS falls back similarly: IndicF5 / lightweight VITS first if present, else existing VITS (Bengali today), else clear "TTS unavailable".

## Engineering Budget (Phase 22)

Preferred per-language model < 100 MB (acceptable < 150 MB). Preferred synthesis RAM 300–400 MB. Preferred RTF < 1.0 (ideal < 0.5). First-audio latency < 2 s. Models exceeding these are benchmarked and the tradeoff documented, not auto-rejected.

## Benchmarks Welcome

The scoring weights and thresholds are configurable. To add measured WER / synthesis / RTF data, extend `ModelPackRegistry` and feed results into `SpeechModelManager.isAcceptable`. No benchmark numbers are fabricated in this repository.
