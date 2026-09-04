# SIH 26173 ML Compliance

Honest mapping of the ML architecture against the SIH requirements. Status is PASS / PARTIAL / NOT IMPLEMENTED based on the actual code and assets.

| Requirement | Actual | Status |
|-------------|--------|--------|
| Fully offline STT | Whisper base int8 via sherpa-onnx, on-device | PASS |
| Fully offline TTS | VITS via sherpa-onnx; Bengali bundled | PARTIAL (1/10 languages) |
| No proprietary voice SDK | All open-source (sherpa-onnx/ONNX) | PASS |
| No cloud inference | No network calls from ML libs | PASS |
| 10 required languages | STT √ (all 10); TTS only Bengali | PARTIAL |
| Accurate capability reporting | ModelPackRegistry + SpeechModelManager check real assets | PASS |
| Lazy model loading | Only selected language's engines initialized | PASS |
| Low/mid-range optimization | DeviceProfiler classes LOW/MID/HIGH; quantized Whisper INT8 | PASS |
| IndicConformer evaluated | Declared as candidate pack (Paraformer runtime); weights not bundled | PARTIAL (architecture ready, no weights) |
| IndicF5 evaluated | Declared as candidate (high-quality); weights not bundled | PARTIAL (architecture ready, no weights) |
| Lightweight VITS/Piper evaluated | Declared as candidate; existing VITS is the baseline | PARTIAL (no new weights) |
| Model selection by benchmark | Selection engine + quality gate implemented | PASS (no fake numbers) |
| VAD low latency | Energy VAD active; Silero asset present but v4-incompatible | PARTIAL |
| Unicode end-to-end | Text preserved as UTF-8 through packet/encryption/relay/TTS | PASS |
| Emergency TTS priority | AudioFocus alarm-stream + max volume for alerts | PASS |
| No existing networking broken | Builds + all tests pass | PASS |

## Ten-Language Capability Registry

| Language | STT | TTS (bundled) |
|----------|-----|---------------|
| Hindi | ✓ Whisper | ✗ |
| Gujarati | ✓ Whisper | ✗ |
| Marathi | ✓ Whisper | ✗ |
| Kannada | ✓ Whisper | ✗ |
| Malayalam | ✓ Whisper | ✗ |
| Tamil | ✓ Whisper | ✗ |
| Telugu | ✓ Whisper | ✗ |
| Odia | ✓ Whisper | ✗ |
| Bengali | ✓ Whisper | ✓ VITS |
| English | ✓ Whisper | ✗ |

## Remaining Work

To reach full 10-language TTS and STT coverage:
1. Bundle VITS/Piper or IndicF5 ONNX weights for the remaining 9 languages under `models/tts/vits_<lang>/` (or the IndicF5 path).
2. Confirm each weight's redistribution license (`docs/MODEL_LICENSES.md`).
3. Replace `models/vad/silero_vad.onnx` with a sherpa-onnx-compatible Silero v5/v6 to enable neural VAD.
4. Optionally bundle IndicConformer weights under `models/stt/indic_conformer_<lang>/model.onnx` and add a Paraformer backend adapter.
