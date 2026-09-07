# Translation Status — iTantra HI↔EN offline neural translation

## Current architecture

```
Hindi/English/any of 10 languages speech
  → Whisper base int8 STT (sherpa-onnx, source language)
  → CrossLanguagePipeline (source != target ⇒ translate; same-language bypass)
     └─ OpusMtTranslationEngine (JNI libitantra_mt.so)
         └─ dlopen(libonnxruntime.so) [bundled by sherpa-onnx, 1.27.x ORT C API]
         └─ encoder_model.onnx + decoder_model.onnx (greedy decode)
         └─ vendored sentencepiece (real SP, Marian-exact ids)
         └─ X→Y cross-language: EN-PIVOT (X→EN→Y) when no direct model exists
  → TextPacket(language = target) → hop AEAD → radio/mesh
  → receiver TTS(language = packet.language)
```

All 10 languages inter-translate offline: 18 direct `EN↔{9 locals}` packs, and
any other X↔Y pairs use two real hops through English (never a fake/direct model).

## Model contract (see docs/OPUS_MT_ANDROID_CONTRACT.md)

Pack dir `models/translation/{src}-{tgt}/`:
- `encoder_model.onnx`, `decoder_model.onnx`
- `config.json` (real special ids: decoder_start=pad=61126, eos=0, bos=0, vocab=61127, d_model=512)
- `tokenizer/sentencepiece.model`, `tokenizer/sp.vocab`

## Status by phase

| Phase | Status | Evidence |
|-------|--------|----------|
| 0 Audit | PASS | this doc; build green; deadlock found+fixed |
| 1 JNI deadlock | PASS | `std::once_flag` + single `g_mtx`; no nested lock; native compiles |
| 2 Real SentencePiece | PASS (tokenizer parity 6/6) | vendored C++ `sentencepiece` compiled into adapter; host harness proves IDs == HF on the 6 required sentences |
| 3 Exact Marian contract | PASS (contract captured from real HF config) | docs/OPUS_MT_ANDROID_CONTRACT.md; converter reads real ids |
| 4 ONNX-vs-HF parity | NOT COMPLETE — BLOCKED on weights | requires downloading opus-mt-hi-en/en-hi + running converter, then verify_onnx_parity.py |
| 5 Decoder optimization | N/A (greedy bounded 64 steps; KV-cache deferred, documented) | — |
| 6 Model packs | NOT COMPLETE — BLOCKED on weights | converter ready; run + hash + host |
| 7 Host artifacts | NOT COMPLETE | Requires Phase 6 outputs |
| 8 ModelCatalog integration | PENDING | translationPacks() downloadUrl/size/checksum stay null until verified packs exist |
| 9 Cross-language pipeline | PASS (unit tested, existing code) | 120 tests incl. CrossLanguagePipeline |
| 10 Model UI | PENDING | Pairs section exists; real download wired in Phase 8 |
| 11 Security | PASS (existing tests) | translation before encryption; packet.language = transmitted text |
| 12 Transport/mesh | PASS (existing tests) | mesh forwards encrypted; no STT/TTS/MT at relay |
| 13 Device test | NOT VERIFIED | needs physical device + packs |
| 14 Benchmarks | PARTIAL | monotonic per-stage timers built; device numbers unmeasured |
| 15 Offline/failure | PARTIAL | offline-by-design; device + missing-model checks pending |
| 16-18 Docs/acceptance | PENDING | README to update at end |

## Actual missing pieces

1. Real Opus-MT ONNX weights (hi-en, en-hi) — converter must run (downloads
   ~300 MB/model from HF).
2. Hosted pack archives with real SHA-256/size (then wire ModelCatalog).
3. Physical Android device for end-to-end + benchmark numbers.

## Build/run requirements

- NDK toolchain (28.x) — native adapter + vendored sentencepiece compile via
  CMake `externalNativeBuild` into `libitantra_mt.so` (arm64 + armeabi-v7a).
- Models: STT (bundled Whisper), TTS (Piper/Coqui/MMS packs), Translation packs.
- Fully offline after pack installation; no cloud service.

## Phase completion checklist (acceptance)

See README "Cross-language runtime status" + docs/OPUS_MT_ANDROID_CONTRACT.md.
Not deployable-end-to-end until Phase 4→7 and Phase 13 are executed with real
model weights + device.