# Translation Status — iTantra HI↔EN offline neural translation

## Current architecture

```
Hindi/English/any of 10 languages speech
  → Whisper base int8 STT (sherpa-onnx, source language)
  → CrossLanguagePipeline (source != target ⇒ translate; same-language bypass)
     └─ OpusMtTranslationEngine (JNI libitantra_mt.so)
         └─ links ONNX Runtime C API (single process via sherpa-onnx libonnxruntime.so)
         └─ encoder_model.onnx + decoder_model.onnx (greedy decode, ORT_ENABLE_ALL)
         └─ vendored real SentencePiece (Marian-exact detokenization)
         └─ X→Y cross-language: EN-PIVOT (X→EN→Y) when no direct model exists
     └─ structured native results: "__MT_OK__:<text>" | "__MT_ERR__:<code>|<msg>"
  → TextPacket(language = target) → hop AEAD → radio/mesh
  → receiver TTS(language = packet.language)
```

All 10 languages inter-translate offline: 18 direct `EN↔{9 locals}` packs, and
any other X↔Y pairs use two real hops through English (never a fake/direct model).
Only `hi↔en` direct packs are currently hosted; the other 16 show "Not hosted"
honestly in the UI.

## Model contract (see docs/OPUS_MT_ANDROID_CONTRACT.md)

Pack dir `models/translation/{src}-{tgt}/`:
- `encoder_model.onnx`, `decoder_model.onnx`
- `config.json` (real special ids: hi-en decoder_start=pad=61126, eos=0, bos=0, vocab=61127, d_model=512; en-hi vocab=61950, decoder_start=pad=61949)
- `tokenizer/sentencepiece.model`, `tokenizer/sp.vocab`
- `manifest.json` (itantra-mt-pack-v1: required_files + tokenizer ids + license)

Installer validates the manifest and required files, publishes atomically, then
runs a post-install smoke test through the real native engine ("नमस्ते" / "Hello");
a failed smoke test removes the pack and reports failure.

## Status by phase

| Phase | Status | Evidence |
|-------|--------|----------|
| 0 Audit | PASS | build green; deadlock found+fixed |
| 1 Contract consistency | PASS | one pack contract everywhere (converter/installer/engine/UI/tests) |
| 2 JNI init/deadlock | PASS | `std::once_flag` + single `g_mtx`; no nested lock; native compiles |
| 3 Structured JNI errors | DONE (9/2026) | sealed `NativeTranslateResult`; `__MT_ERR__:<code>|<msg>`; 4 unit tests; no string sniffing |
| 4 ONNX-vs-HF parity | PASS (host) | verify_onnx_parity.py greedy-aligned vs HF manual reference; 6/6 both directions (incl. "आप कहाँ जा रहे हैं?"→"Where are you going?") |
| 5 Pack manifest | DONE | `manifest.json` written by converter, validated by installer |
| 6 Post-install smoke test | DONE | real engine run after publish; rollback on failure |
| 7 Storage accounting | DONE | recursive sizeBytes (nested models/ + tokenizer/ counted) |
| 8 Decoder optimization | PARTIAL | `ORT_ENABLE_ALL` graph opt; full KV-cache deferred (needs re-exported decoder w/ past_key_values) |
| 9 ORT perf tuning | PARTIAL | env/session cached per pair; enc-hidden input hoisted out of decode loop |
| 10 Fewer native copies | DONE | one encHidden input for all decode steps (was per-step memcpy) |
| 11 Lazy init | DONE | native self-test triggers on first load, not construction |
| 12 Cross-language pipeline | PASS (unit) | 130 tests; same-language + SOS bypass; 9 parallel pipelines stress-tested |
| 13 Capability correctness | PASS | supports() = catalog-driven; never fabricates |
| 14 Translation UI states | DONE | status labels: Downloading/Verifying/Corrupted/Failed/Not hosted; delete + retry |
| 15 Download hardening | DONE | path-traversal / absolute-path / drive-letter / symlink + canonical resolve checks |
| 16 Atomic install | DONE | staging → validate → smoke → atomic publish (.old backup) → delete backup |
| 17 Release build audit | PASS | assembleRelease succeeds; JNI proguard keep-rules; R8-safe |
| 18 Device full-chain | NOT VERIFIED | blocked: needs physical device + packs |
| 19 Two-phone E2E | NOT VERIFIED | blocked: needs two devices |
| 20 Benchmarks | PARTIAL | per-stage timers + logger exist; device numbers unmeasured |
| 21 Security regression | PASS (unit) | per-peer keys / replay / packet auth tests green |
| 22 SOS regression | PASS (unit) | SOS bypass translation + volume restore tested |
| 23 Transport regression | PASS (unit) | BT/Wi-Di/mesh unit tests green |
| 24 Lifecycle | PASS | env/sessions released on nnRelease + pair-swap under mutex; executor bounded |
| 25 Test suite | PASS | 130 unit tests, 0 failures; lint 0 errors/fatals |
| 26 Docs | THIS FILE | honest status; no fabricated claims |
| 27 SIH demo | PENDING | device + packs required |
| 28 Acceptance | NOT MET | final on-device E2E + benchmarks pending |

## Actual missing pieces

1. Physical Android device (RMX3870 arm64) — for Phase 18/19/20, including the
   513 MB hi-en pack download + install + latency numbers. Not a bug; transport size.
2. On-device benchmark values of the current build (encode/decode/ttf).
3. KV-cache decoder (past_key_values) — deferred; documented in nnmt_jni.cpp.

## Build/run requirements

- Android SDK + NDK (28.x) — adapter + vendored sentencepiece compile via CMake
  into `libitantra_mt.so` (arm64 + armeabi-v7a).
- Models: STT (bundled Whisper int8), TTS (Piper/VITS packs), Translation packs
  (hosted on GitHub release `mt-onnx`: hi-en 513509018 B, en-hi 518143817 B,
  real SHA-256 in ModelCatalog).
- Fully offline after pack installation; no cloud service.

## Acceptance checklist

- [x] Build green (debug + release), 130 unit tests, lint clean (0 errors)
- [x] Real Opus-MT hi-en + en-hi converted + hosted with real SHA + smoke-tested
- [x] Structured errors, manifest, smoke test, hardened download, atomic install
- [ ] Physical device: install pack, translate speech→translation→TTS end-to-end
- [ ] Two-phone radio E2E with real translation
- [ ] Benchmark numbers (encode/decode/end-to-end latency)