# SIH 26173 Final Test Matrix

**iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver**
Status vocabulary: PASS (physically verified) / UNIT TESTED (automated unit test) /
FAIL / NOT VERIFIED. Evidence column names the test or procedure. Nothing marked
PASS without a physical-device procedure.

| # | Item | Status | Evidence |
|---|------|--------|----------|
| A | Hindi STT | NOT VERIFIED | Whisper base int8 wired; no real-WER run yet (Phase 11) |
| B | English STT | NOT VERIFIED | same |
| C | Hindi TTS | NOT VERIFIED | Piper hi_IN wired; no device playback measurement |
| D | English TTS | NOT VERIFIED | Piper en_US wired; no device playback measurement |
| E | HI→EN translation | UNIT TESTED (host parity) | `verify_onnx_parity.py` 6/6 both dirs + on-device native smoke (NATIVE_TEST_OK earlier); **on-device full translation run pending pack install** |
| F | EN→HI translation | UNIT TESTED (host parity) | same |
| G | same-language bypass | UNIT TESTED | `CrossLanguagePipelineTest` |
| H | encrypted packet | UNIT TESTED | `TextPacketTest`, `SecurityTest` |
| I | tamper rejection | UNIT TESTED | `SecurityTest` (tampered ciphertext/header) |
| J | replay rejection | UNIT TESTED | `SecurityTest` (replay) |
| K | Bluetooth | NOT VERIFIED | device required; UI shows offline |
| L | Wi-Fi Direct | NOT VERIFIED | device required |
| M | A→Relay→B routing | UNIT TESTED | `MeshRoutingTest`, `DtnChainTest` |
| N | store-and-forward | UNIT TESTED | `DtnChainTest`, `MeshDtnIntegrationTest` |
| O | ACK | UNIT TESTED | `DeliveryTrackerTest` |
| P | retry | UNIT TESTED | `CompositeTransportTest`, `DeliveryTrackerTest` |
| Q | SOS priority | UNIT TESTED | `SosPipelineTest` |
| R | offline mode | IMPLEMENTED (source audit) | no cloud inference path found (Phase 18 list) |
| S | model installation | UNIT TESTED (staging/publish) | flatten + manifest + smoke gate unit-covered; **on-device install pending** |
| T | corrupted model | UNIT TESTED | checksum mismatch → CORRUPTED; unit covered |
| U | release APK | IMPLEMENTED | `assembleRelease` green; not yet flashed |
| V | lifecycle stress | UNIT TESTED (Vad) | `VadEndpointingTest`; 50× start/stop device run pending |
| W | real latency | NOT VERIFIED | `LATENCY_RESULTS.md` explicitly NOT VERIFIED |
| X | real WER | NOT VERIFIED | `ACCURACY_RESULTS.md` explicitly NOT VERIFIED |

## Physical-device blockers (single RMX3870, flaky USB)

- on-device translation-pack install (513 MB) — drive rerun blocked by USB drop
- C/D (TTS playback), A/B (real STT), K/L (radios), U (release flash)
- Two-phone E2E (Phase 17), relay demo (Phase 25) — second device required

Nothing not in the above table is claimed as measured.