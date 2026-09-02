# Model License & Attribution Audit
**iTantra — ISRO Problem Statement 26173**

All models, frameworks, and acoustic runtimes used in iTantra are 100% open-source, permissive, and approved for offline on-device deployment.

---

## 1. Model Inventory & Licenses

| Component | Model Name / Checkpoint | Primary Upstream Repository | License Type | Commercial & Offline Use | Attribution Required |
|---|---|---|---|---|---|
| **VAD** | Silero VAD (v4/v5 ONNX) | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) | **MIT License** | Yes | Yes (Included in app about/licenses) |
| **STT** | IndicConformer CTC / RNN-T | [AI4Bharat/IndicConformerASR](https://github.com/AI4Bharat/IndicConformerASR) | **MIT License / CC-BY 4.0** | Yes | Yes (AI4Bharat / IIT Madras) |
| **STT Fallback** | Vosk / Sherpa-ONNX Indic | [alphacep/vosk-api](https://github.com/alphacep/vosk-api) | **Apache 2.0** | Yes | Yes (Alpha Cephei Inc.) |
| **TTS** | Indic-TTS (VITS FastPitch) | [AI4Bharat/Indic-TTS](https://github.com/AI4Bharat/Indic-TTS) | **MIT License / CC-BY 4.0** | Yes | Yes (AI4Bharat / IIT Madras) |
| **TTS Fallback** | Piper TTS / Sherpa-ONNX | [rhasspy/piper](https://github.com/rhasspy/piper) | **MIT License** | Yes | Yes (Rhasspy / Piper Community) |
| **Inference Runtime** | ONNX Runtime Mobile Android | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | **MIT License** | Yes | Yes (Microsoft Corporation) |
| **Inference Runtime** | TensorFlow Lite Java/Android | [tensorflow/tensorflow](https://github.com/tensorflow/tensorflow) | **Apache 2.0** | Yes | Yes (Google LLC) |

---

## 2. Redistribution & Compliance Notes

1. **No Proprietary Commercial Voice SDKs**: iTantra does not bundle proprietary SDKs (e.g. Google Cloud Speech, AWS Transcribe, Azure Cognitive Services, or closed SDKs).
2. **Local Model Weight Packaging**: Models are stored within the Android assets package (`assets/models/`) and cached directly in application internal storage (`context.filesDir`).
3. **Attribution Statement**:
   > *"This software utilizes open-source neural acoustic and voice models developed by the AI4Bharat initiative (IIT Madras), Silero Audio, and the Rhasspy/Piper open-source community under MIT and Apache 2.0 licenses."*
