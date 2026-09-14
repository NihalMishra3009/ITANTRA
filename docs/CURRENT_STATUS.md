# CURRENT STATUS — iTantra (SIH 26173)

Generated from the repository at commit range ending `2bb3cc5` (plus later fixes).
Status vocabulary: IMPLEMENTED / UNIT TESTED / INTEGRATION TESTED /
PHYSICALLY VERIFIED / NOT VERIFIED / BLOCKED.

## A. What is actually implemented (code present, builds)

- STT: Whisper base INT8 (bundled, sherpa-onnx), Hindi + English priority, all-10 multilingual capability declared.
- TTS: Piper/VITS packs, Hindi + English hosted; Bengali VITS bundled.
- VAD: **Adaptive Energy VAD** as active detector; Silero v4 model bundled but NOT promoted (Phase 8 condition unmet — needs live on-device discrimination test). `isUsingNeuralVad()==false`.
- Translation: real Helsinki-NLP Opus-MT hi↔en (ONNX + vendored SentencePiece + JNI), structured result envelopes, session cache, staged smoke test, rollback-safe install.
- Packet: compact binary v4 (msg-id exact, sender/recipient, HMAC per-peer, flags, TTL, hop).
- Security: per-peer ECDH/HKDF/AES-GCM/HMAC, replay protection (per-peer, bounded, TTL-aware), unknown-language rejection, payload cap 32767.
- Transport/mesh: Bluetooth + Wi-Fi Direct selection, CompositeTransport, mesh routing, store-and-forward outbox, ACK/retry, emergency queue preemption.
- SOS: protocol-level EMERGENCY, no STT/translation dependency, priority send, volume restore.
- Installer: download → SHA-256 → extract (hardened) → validate → STAGED smoke → publish-keeping-backup → verify → delete backup. Failed replacement restores the old model.
- Benchmark: monotonic clocks, real export (raw+summary JSON/CSV, P50/P95/avg/min/max, NOT MEASURED sentinel). No fake numbers anywhere.

## B. What is unit tested (automated, passing)

168 unit tests, 0 failures (`./gradlew testDebugUnitTest`). Covered: codec, security (tamper/replay/wrong-key/unknown-lang/payload-cap/unauth), mesh (relay/dup/ACK/lifecycle/emergency), SOS, translation parser, install rollback, storage contract, archive-entry policy, VAD state machine, benchmark math, transport selection, location.

## C. What is integration tested

- ONNX-vs-HF parity: `verify_onnx_parity.py` — 12/12 PASS (6 hi-en + 6 en-hi, incl. deterministic greedy, EOS behavior).
- Full clean build: `./gradlew clean testDebugUnitTest assembleDebug assembleRelease lintDebug` — SUCCESSFUL; lint 0 errors/fatals.
- Host translation latency measured (real) via `--bench`: hi-en decode ~184 ms, en-hi ~410 ms (host CPU, marked NOT device).

## D. What is physically tested

- RMX3870 (armeabi-v7a? arm64-v8a): earlier sessions verified `NATIVE_TEST_OK` (native adapter + ORT load), TTS/STT engine load, discovery. **DURING THIS RUN: no device stable enough — on-device pack install reproductions dropped repeatedly at the USB link.**

## E. What remains unverified (NOT VERIFIED; physically blocked)

- Hindi/English STT real WER (needs 20+ real utterances per language, device)
- Hindi/English TTS playback + RTF on device
- HI→EN / EN→HI on TWO PHONES end-to-end
- Same-language E2E, relay (A→R→B) physically, offline-mode physical, SOS physical alarm run
- Real latency CSV/JSON from device instrumentation
- RAM/CPU measurements
- Release APK flashed + verified on device
- 50× lifecycle stress on device

## F. Known limitations

- Energy VAD: silence-tier semantics now correct (SENTENCE_END boundary, LONG_SILENCE finalize); not a neural VAD.
- Decoder runs autoregressive greedy WITHOUT KV-cache reuse (documented; no re-exported past-key graph).
- Downloads are not resumable (honest; HTTP range unsupported).
- 513 MB FP32 translation packs on-device install is slow on mid-range phones; rehost with manifest deferred.
- Hosted translation archives predate `manifest.json` — installer validates legacy packs via contract files when manifest absent.

## G. Exact validation commands used

```powershell
./gradlew testDebugUnitTest
./gradlew clean testDebugUnitTest assembleDebug assembleRelease lintDebug
python model-conversion/verify_onnx_parity.py --pack model-conversion/converted/hi-en --pack model-conversion/converted/en-hi --bench
python benchmark/evaluate_wer.py       # honest skip until real WAVs exist
python benchmark/evaluate_latency.py   # honest: NO measured data
```

Results: 168/168 tests, lint 0 errors/fatals, release build SUCCESSFUL, parity 12/12.

## H. Device(s) used for any physical testing

- Only RMX3870 (serial SWCEYXTK7H95NFCQ) has EVER been attached to this project.
  It validated the native translation adapter (NATIVE_TEST_OK) in a prior session.
  No two-phone test has EVER been performed. No device was stable enough during
  this validation cycle to complete the on-device translation-pack install.