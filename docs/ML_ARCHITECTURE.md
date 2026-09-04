# iTantra ML Architecture

This document describes the **actual** speech ML architecture in the repository. It reflects real code and real model assets — not aspirational claims.

## 1. Model Type Abstraction (Phase 1)

All speech ML is reached through one facade:

```
PipelineOrchestrator
   └── SpeechModelManager (com.itantra.speech)
         ├── ModelPackRegistry   (honest per-language / per-role availability)
         ├── DeviceProfiler      (hardware capability profile)
         ├── STT  → WhisperSttBackend (wraps existing SttEngine)
         ├── TTS  → VitsTtsBackend    (wraps existing TtsEngine)
         └── VAD  → EngineVadBackend  (wraps existing VadEngine)
```

The rest of ITANTRA (pipeline, UI, networking) talks only to `SpeechModelManager`, so a backend model can be swapped without touching the network/UI layers.

## 2. SpeechModelManager

- Lazy loading: only the currently-selected language's engines are initialized.
- `selectLanguage(lang)` re-initializes STT/TTS engines only for that language.
- Capability reporting delegates to `ModelPackRegistry`, which checks actual assets and never reports a model as available when its file is missing.
- Model selection (`selectBestPack`) uses a weighted score (quality + size efficiency) filtered by device class.
- Quality-safety gate (`isAcceptable`) enforces configurable min quality / max size / max latency / RAM budget.

## 3. Model Pack Registry (Phase 3 / 12)

`ModelPackRegistry` declares candidate packs for the 10 required languages. Confirmed primary targets (optimized/quantized for mobile):

| Role | Target model | Runtime | Status |
|------|--------------|---------|--------|
| STT | **IndicConformer INT8** (mobile-optimized) | sherpa-onnx Paraformer | CANDIDATE (weights not bundled) |
| STT | Whisper base int8 (existing fallback) | sherpa-onnx | ACTIVE (covers all 10 langs) |
| TTS | **IndicF5 INT8** (high-quality, mobile-optimized) | sherpa-onnx | CANDIDATE (weights not bundled) |
| TTS | VITS (existing, per-lang) | sherpa-onnx | ACTIVE for Bengali only |
| VAD | Silero | sherpa-onnx | asset present but v4 incompatible → energy fallback |

A pack is only considered `available` when its file actually exists (bundled asset OR downloaded to app-private storage). `bestAvailable(lang, role, deviceClass)` returns the highest-quality available pack the device can afford.

Note: IndicConformer (STT) and IndicF5 (TTS) are the user-confirmed primary targets and are scored / ranked as HIGH quality; when their INT8 ONNX weights are bundled or downloaded, they are selected first, falling back to the existing Whisper / VITS.

## 4. Model Distribution — Download-Once, Then Offline

The app ships a **small APK** with no per-language model weights bundled. Language models are delivered as **download-once packs**:

```
user picks a language
  -> ModelDistributionManager.install(lang, STT/TTS)
        download -> verify SHA-256 + size -> cache in app-private storage
  -> after install: ModelPackRegistry reports it available
  -> inference loads from cache; the app runs FULLY OFFLINE thereafter
```

- `ModelDistributionManager` handles download, integrity verification, caching under `filesDir/models/stt|tts/<lang>/`, and uninstall (free storage).
- The catalog (`ModelCatalog`) lists available online packs. It is intentionally **empty until real, licensed, redistributable artifacts** (with URL + SHA-256) are configured — the app never fakes an installable model.
- The download step is the **only** online moment for a model; normal transceiver operation (STT/TTS/network/DTN) is fully offline from cached files.

## 5. Device Profiling (Phase 10)

`DeviceProfiler` classifies devices strictly by hardware (RAM, core count), not brand:

- LOW: < 2 GB RAM → lightweight quantized models
- MID: 2–6 GB RAM → balanced models
- HIGH: ≥ 6 GB RAM → higher-quality models when latency allows

## 6. Deployment Reality

The active offline STT is **Whisper base int8** (multilingual, all 10 languages). Active offline TTS is **VITS** with **only Bengali** bundled today. IndicConformer / IndicF5 are the confirmed primary targets, declared as INT8 mobile-optimized candidate packs; their ONNX weights are delivered via download-once (catalog to be populated with licensed artifacts). This is reported honestly in the UI/diagnostics.
