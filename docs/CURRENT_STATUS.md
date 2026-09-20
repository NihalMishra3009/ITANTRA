# CURRENT STATUS — iTantra (SIH 26173)

Generated from the repository at commit range ending `01c2f02` (plus the build-repair
and reliability fixes described in section I).
Status vocabulary: IMPLEMENTED / UNIT TESTED / INTEGRATION TESTED /
PHYSICALLY VERIFIED / NOT VERIFIED / BLOCKED.

## A. What is actually implemented (code present, builds)

- STT: Whisper base INT8 (bundled, sherpa-onnx), Hindi + English priority, all-10 multilingual capability declared.
- TTS: **open-source only.** Bundled eSpeak NG (GPL-3.0+) covers all 10 languages; optional Piper neural voices (en/mr/te/bn, open-licensed). Non-open voices excluded — see `docs/MODEL_LICENSES.md`.
- VAD: **Adaptive Energy VAD** as active detector; Silero v4 model bundled but NOT promoted (Phase 8 condition unmet — needs live on-device discrimination test). `isUsingNeuralVad()==false`.
- Translation: real Helsinki-NLP Opus-MT hi↔en (ONNX + vendored SentencePiece + JNI), structured result envelopes, session cache, staged smoke test, rollback-safe install.
- Packet: compact binary v4 (msg-id exact, sender/recipient, HMAC per-peer, flags, TTL, hop).
- Security: per-peer ECDH/HKDF/AES-GCM/HMAC, replay protection (per-peer, bounded, TTL-aware), unknown-language rejection, payload cap 32767.
- Transport/mesh: Bluetooth + Wi-Fi Direct selection, CompositeTransport, mesh routing, store-and-forward outbox, ACK/retry, emergency queue preemption.
- SOS: protocol-level EMERGENCY, no STT/translation dependency, priority send, volume restore.
- Installer: download → SHA-256 → extract (hardened) → validate → STAGED smoke → publish-keeping-backup → verify → delete backup. Failed replacement restores the old model.
- Benchmark: monotonic clocks, real export (raw+summary JSON/CSV, P50/P95/avg/min/max, NOT MEASURED sentinel). No fake numbers anywhere.

## B. What is unit tested (automated, passing)

174 unit tests, 0 failures (`./gradlew testDebugUnitTest`). Covered: codec, security (tamper/replay/wrong-key/unknown-lang/payload-cap/unauth), mesh (relay/dup/ACK/lifecycle/emergency), outbox persistence (expiry + ACK row cleanup, insert/delete ordering), SOS, translation parser, install rollback, storage contract, archive-entry policy, VAD state machine, benchmark math, transport selection, location.

## C. What is integration tested

- ONNX-vs-HF parity: `verify_onnx_parity.py` — 12/12 PASS (6 hi-en + 6 en-hi, incl. deterministic greedy, EOS behavior).
- Full clean build: `./gradlew clean testDebugUnitTest assembleDebug assembleRelease lintDebug` — SUCCESSFUL; lint 0 errors/fatals. (This claim was stale in the previous revision of this
  document: the tree at `01c2f02` did **not** compile. See section I.)
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

Results: 174/174 tests, lint 0 errors/fatals, signed release build SUCCESSFUL, parity 12/12.

## H. Device(s) used for any physical testing

- Only RMX3870 (serial SWCEYXTK7H95NFCQ) has EVER been attached to this project.
  It validated the native translation adapter (NATIVE_TEST_OK) in a prior session.
  No two-phone test has EVER been performed. No device was stable enough during
  this validation cycle to complete the on-device translation-pack install.
## I. Build repair + reliability fixes applied after `01c2f02`

The committed tree at `01c2f02` did not build. It has been repaired, and several
defects found while reviewing the runtime paths were fixed:

**Build breaks (the app could not be compiled or installed at all):**

- `AndroidManifest.xml` declared `.ui.TranslationTestActivity` twice — manifest
  merger aborted `processDebugMainManifest`. De-duplicated.
- `TranslationTestActivity.kt` passed `lparams(1) to 0` (a `Pair`) to
  `LinearLayout.addView`, and called a non-existent `nativeEngineSelfTest()`
  (the engine method is `nativeSelfTest()`). Both fixed.
- `assembleRelease` produced `app-release-unsigned.apk`, which cannot be
  installed. A release `signingConfig` now reads `keystore.properties` (git-ignored)
  or the `ITANTRA_KEYSTORE_*` environment variables, and falls back to the debug key
  so the release build always emits an installable apk. A fallback-signed apk is for
  sideloading and demos only, not Play distribution.

**Runtime defects:**

- `PipelineOrchestrator.onPttPressed` collected `AudioRecorder.audioChunkFlow`
  (a `SharedFlow`, which never completes) in a fresh coroutine on every press, and
  never cancelled it. Collectors accumulated for the life of the process, each one
  running VAD, partial STT and — in CONTINUOUS mode — utterance finalization on the
  same chunks. Now exactly one capture job exists, cancelled on release/shutdown.
- The inbound decode-and-play path was not serialized, so two packets arriving
  together interleaved their `RECEIVING`/`SYNTHESIZING`/`PLAYING`/`IDLE` transitions
  and the UI could read `IDLE` while audio was still playing. Now behind a mutex.
- Outbox store-and-forward: an expired message left the in-memory queue but its Room
  row survived, so every restart restored it again and the table grew without bound.
  Insert and delete were also launched onto the shared IO pool from different call
  sites, so a delete could overtake its own insert and orphan the row permanently.
  Rows are now deleted on every removal path, and all outbox DB writes are serialized
  onto a single-threaded dispatcher. Covered by `OutboxPersistenceTest` (verified to
  fail without the fix).
- `SttEngine.initialize` built a log label via `File(path).parentFile.name`, which
  throws when the path has no parent. The throw happened *after* the recognizer had
  loaded, so the surrounding catch discarded a working STT engine. Now null-safe.
- `MainActivity.renderLatency` ended in a no-op `if (visible) { set visible }` block
  (removed), and formatted RTF with the default locale, which renders Devanagari
  digits on an `hi`-locale device. Now `Locale.US`.

**Not changed (deliberately):** `isMinifyEnabled = false` is left off for release.
Enabling R8 would need on-device verification of Room and sherpa-onnx reflection
paths, and no device was available in this cycle; the APK size is dominated by the
265 MB of bundled models, not by code.

## J. Open-source TTS for all 10 languages

- `ModelCatalog.OPEN_SOURCE_ONLY = true`. Every language now has an open-licensed offline voice:
  eSpeak NG (bundled, all 10) and optional Piper neural voices for en/mr/te/bn.
- **Verified on the host, not on a device:** the vendored eSpeak NG core was built with mingw-gcc and produced
  real audio (1.9–3.5 s, RMS ~3000/32768) for all 10 languages; the three converted Piper packs synthesize real
  speech through sherpa-onnx. **Not verified:** the Android build of `libitantra_espeak.so` running on a phone,
  speech quality by ear, RTF/latency on a device.
- Quality risk: hi, ml, gu, kn, ta, or are eSpeak-only (robotic). This will likely score poorly on TTS legibility.
- **Action needed:** the mr/te/bn Piper packs must be uploaded to release `tts-open-v1` (the `stackht` account used
  here has no write access to the repo). Until then only eSpeak NG is used for them.
- Removed the bundled 114 MB Coqui Bengali model (unverifiable license); the APK is ~110 MB smaller.
- Fixed a latent build bug: the vendored SentencePiece CMake referenced training sources (`builder.h`) that are
  not in the tree. It only built from a cached configure; a fresh clone could not build.
- Corrected earlier false claims: Piper Hindi is CC-BY-NC-SA (not MIT); the bundled Bengali model's license was
  self-contradictory.

## K. On-device end-to-end results (single phone)

**Device:** OPPO CPH2127, Android 12, arm64-v8a, 3.7 GB RAM (mid/low range). Debug build, USB. Instrumented
tests in `app/src/androidTest/.../DeviceE2ETest.kt`; measurements are on logcat tag `E2E`.

| Check | Result |
|---|---|
| App launch, main + Models screens, SOS dialog and send | Works, no crash. SOS with no peer shows "SOS SENDING", QUEUE: 1 (store-and-forward) |
| eSpeak NG, all 10 languages, on device | 3.1-4.2 s of audio per sentence, RTF 0.010-0.013 (about 90x real time) |
| `TtsEngine` end to end, all 10 languages | Pass, non-silent audio for every language |
| Neural Piper Telugu: download from the GitHub release, install, load | Works. Speech produced; RTF about 1.3 on the first call (slower than real time). Install takes about 3 min (bzip2 extraction on this CPU) |
| Whisper base int8 STT speed | RTF 0.9-1.3 for most languages, i.e. transcription is about as slow as the audio is long |
| TTS -> STT round trip, English | WER 0.14 (1 of 7 words) |
| Idle (main screen, 60 s) | 113 MB RSS, 115 MB PSS, about 1.1% of one core, 31 threads |

**Not a valid accuracy result:** the round trip feeds robotic eSpeak audio to Whisper. Indic-language output was
mostly garbage, which says little about real speech. **No real-speech WER has been measured for any language.**

**Bugs found on the device and fixed:**
1. Whisper decoded every language as the FIRST one used (the language was baked in at build time). Now switched
   in place with `setConfig`.
2. Odia crashed the whole app: Whisper has no Odia, and sherpa-onnx calls `exit()` on an invalid language.
   Odia STT is now reported unavailable and never reaches the recognizer.
3. Every voice pack I built had CRLF line endings in `tokens.txt`, which ABORTS sherpa-onnx on Android. It passed
   on Windows, so host verification missed it. Packs rebuilt and re-uploaded; the build script now validates
   the file; the app also strips CRs before loading. Regression tests added.
4. Downloaded voices installed but were never used: the loader required a file named `model.onnx`, while packs keep
   their own name. Fixed and covered by a test.
5. The Models screen showed "STT ✓" for every language regardless of support.

**Environment finding:** on this ROM (ColorOS), a process without a foreground activity is frozen by the OS,
even mid-computation. My first timings (65-638 s for a few seconds of audio) were wall-clock time spent frozen and
were wrong; the decoder is not runaway. For a walkie-talkie this matters: receiving while backgrounded or with the
screen off will need a foreground service. That has not been built or tested.

**Still not tested:** two phones (Bluetooth/Wi-Fi Direct), relay, real-speech accuracy, alert volume and
non-interruptible playback, full-duplex "phone" mode, receiving with the screen off, battery drain.
