# Model License & Attribution Audit
**iTantra — ISRO Problem Statement 26173**

> **CORRECTION NOTICE (Phase 29 hardening audit):** This document previously listed AI4Bharat IndicConformer, Indic-TTS, Vosk, and TensorFlow Lite as the bundled stack. The **actual production stack** is OpenAI Whisper (STT) + VITS (TTS) + Silero VAD, all running on sherpa-onnx / ONNX Runtime. The table below reflects only what is actually bundled and used.

All models, frameworks, and acoustic runtimes used in iTantra are 100% open-source, permissive, and approved for offline on-device deployment.

---

## 1. Model Inventory & Licenses (actual bundled stack)

| Component | Model Name / Checkpoint | Primary Upstream Repository | License Type | Commercial & Offline Use | Attribution Required |
|---|---|---|---|---|---|
| **STT** | OpenAI Whisper **base int8** (multilingual) | [openai/whisper](https://github.com/openai/whisper) | **MIT License** | Yes | Yes (OpenAI Whisper) |
| **TTS** | VITS (per-language ONNX; Bengali bundled) | [jaywalnut310/vits](https://github.com/jaywalnut310/vits) | **MIT License** | Yes | Yes (VITS authors) |
| **VAD** | Silero VAD (ONNX; energy fallback active) | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) | **MIT License** | Yes | Yes (Silero) |
| **Inference Runtime** | sherpa-onnx 1.13.7 (whisper + VITS + VAD) | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | **Apache 2.0** | Yes | Yes (k2-fsa) |
| **ONNX Runtime** | ONNX Runtime (bundled via sherpa-onnx AAR) | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | **MIT License** | Yes | Yes (Microsoft Corporation) |

> **Note:** STT uses a single multilingual Whisper base int8 model for all 10 Indian languages. TTS uses per-language VITS models; only `vits_bn` (Bengali) is currently bundled. `ModelCapabilityRegistry` verifies real asset presence and reports honestly which languages have TTS.

---

## 2. Redistribution & Compliance Notes

1. **No Proprietary Commercial Voice SDKs**: iTantra does not bundle proprietary SDKs (e.g. Google Cloud Speech, AWS Transcribe, Azure Cognitive Services, or closed SDKs).
2. **Local Model Weight Packaging**: Models are stored within the Android assets package (`assets/models/`) and cached directly in application internal storage (`context.filesDir`).
3. **Attribution Statement**:
   > *"This software utilizes open-source neural acoustic and voice models including OpenAI Whisper, VITS, and Silero VAD, running on the sherpa-onnx / ONNX Runtime inference engine, under MIT and Apache 2.0 licenses."*
