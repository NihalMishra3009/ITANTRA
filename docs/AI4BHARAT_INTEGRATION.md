# AI4Bharat / Indic Integration & Offline Transceiver Architecture
**iTantra — ISRO Problem Statement 26173 | Smart India Hackathon**

> **CORRECTION NOTICE (Phase 29 hardening audit):**
> This document previously described an AI4Bharat IndicConformer (STT) + Indic-TTS stack with AES-128/256-CBC. The **actual production implementation** uses:
> - **STT**: OpenAI **Whisper base int8** (multilingual, all 10 languages) via sherpa-onnx / ONNX Runtime — `SttEngine.kt`
> - **TTS**: per-language **VITS** models via sherpa-onnx — `TtsEngine.kt` (only Bengali currently bundled)
> - **Text normalization**: local `IndicTextNormalizer` (Unicode NFC)
> - **Encryption**: **AEAD AES-256-GCM** + ECDH P-256 + HKDF-SHA256 — `MessageSecurityManager.kt`
> The adapter interfaces (`Ai4BharatSttAdapter` / `Ai4BharatTtsAdapter`) are preserved and implemented by `SttEngine` / `TtsEngine`.

This document specifies the integration of Indic language Speech-to-Text (STT), Text-to-Speech (TTS), and Unicode text normalization into the offline-first iTantra transceiver.

---

## 1. Decoupled System Architecture

The AI model inference layer is strictly decoupled from the radio and mesh routing layer:

```
+─────────────────────────────────────────────────────────────────────────────────────────+
|                                    SENDER PHONE                                         |
|                                                                                         |
|  [User Microphone] OR [Text Typing Fallback]                                            |
|       │                                                                                 |
|       ▼                                                                                 |
|  [Audio Capture (16kHz PCM)]                                                            |
|       │                                                                                 |
|       ▼                                                                                 |
|  [Whisper base int8 STT Engine] (multilingual, all 10 languages, ONNX)                    |
|       │                                                                                 |
|       ▼                                                                                 |
|  [IndicTextNormalizer] (Unicode NFC, Danda, Nukta, Whitespace Cleanup)        |
|       │                                                                                 |
|       ▼                                                                                 |
|  [MessageSecurityManager] (AEAD AES-256-GCM + ECDH P-256 Encryption)                   |
|       │                                                                                 |
|       ▼ (Encrypted Ciphertext TextPacket)                                               |
|  [Offline Mesh & Radio Layer] (Bluetooth RFCOMM / Wi-Fi Direct TCP Sockets)             |
+────────────────────────────────────────┬────────────────────────────────────────────────+
                                         │ (Multi-Hop Relay / Radio Links)
                                         ▼
+─────────────────────────────────────────────────────────────────────────────────────────+
|                             INTERMEDIATE RELAY NODES (C, D)                             |
|                                                                                         |
|  - Forward Encrypted Packets along Mesh Hops without Decrypting                         |
|  - Zero STT/TTS execution on relay nodes (preserves RAM and privacy)                    |
|  - Store-and-Forward Outbox buffering if downstream hop is temporarily offline          |
+────────────────────────────────────────┬────────────────────────────────────────────────+
                                         │ (Radio Link)
                                         ▼
+─────────────────────────────────────────────────────────────────────────────────────────+
|                                   RECEIVER PHONE                                        |
|                                                                                         |
|  [Offline Mesh & Radio Layer]                                                           |
|       │                                                                                 |
|       ▼ (Ciphertext Ingestion)                                                          |
|  [MessageSecurityManager] (AES-256-GCM Payload Decryption at Destination)               |
|       │                                                                                 |
|       ▼ (Original Indic Plaintext)                                                      |
|  [Language Identification & Dispatcher]                                                 |
|       │                                                                                 |
|       ▼                                                                                 |
|  [VITS Neural TTS Engine] (24kHz Acoustic PCM Synthesis, per-language ONNX)             |
|       │                                                                                 |
|       ▼                                                                                 |
|  [Speaker Playback] (STREAM_MUSIC / High-Priority STREAM_ALARM SOS Siren)               |
+─────────────────────────────────────────────────────────────────────────────────────────+
```

---

## 2. Model Inventory & Licensing (actual production stack)

| Model Function | Model Name | Upstream Repository | License | On-Device Optimization |
|---|---|---|---|---|
| **STT (Speech-to-Text)** | OpenAI Whisper **base int8** (multilingual, 10 langs) | [openai/whisper](https://github.com/openai/whisper) | **MIT** | Int8 quantized ONNX via sherpa-onnx (encoder 29MB + decoder 130MB) |
| **TTS (Text-to-Speech)** | VITS per-language ONNX | [jaywalnut310/vits](https://github.com/jaywalnut310/vits) | **MIT** | Neural vocoder synthesis (24kHz PCM); only Bengali bundled |
| **Text Normalizer** | `IndicTextNormalizer` | in-repo | MIT License | Pure Kotlin on-device zero-latency normalizer |
| **Voice Activity Detector** | Silero VAD ONNX (energy fallback active) | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) | **MIT License** | ONNX Runtime (2.3MB); v4 asset incompatible → energy fallback |
| **Runtime** | sherpa-onnx 1.13.7 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | **Apache 2.0** | ONNX Runtime bundled in AAR |

---

## 3. Standard Model Adapter Interfaces

```kotlin
interface Ai4BharatSttAdapter {
    fun initialize(languageCode: String): Boolean
    fun transcribe(audioChunk: FloatArray, languageCode: String = ""): SttResult
    fun isModelLoaded(): Boolean
    fun release()
}

interface Ai4BharatTtsAdapter {
    fun initialize(languageCode: String): Boolean
    fun synthesize(text: String, languageCode: String = "", isAlert: Boolean = false): TtsResult
    fun isModelLoaded(): Boolean
    fun release()
}
```

---

## 4. Supported Languages (10 Indian Languages)

1. **Hindi (`hi`)** — Devanagari Script (`hin`)
2. **Marathi (`mr`)** — Devanagari Script (`mar`)
3. **Bengali (`bn`)** — Bengali Script (`ben`)
4. **Gujarati (`gu`)** — Gujarati Script (`guj`)
5. **Odia (`or`)** — Odia Script (`ori`)
6. **Tamil (`ta`)** — Tamil Script (`tam`)
7. **Telugu (`te`)** — Telugu Script (`tel`)
8. **Kannada (`kn`)** — Kannada Script (`kan`)
9. **Malayalam (`ml`)** — Malayalam Script (`mal`)
10. **English (`en`)** — Latin Script (`eng`)

---

## 5. Security & Privacy Guarantees

- **Zero Cloud Leakage**: Microphones and audio data are never streamed over external network sockets.
- **Payload Ciphertext**: Plain text is encrypted using **AEAD AES-256-GCM** (session keys derived via ECDH P-256 + HKDF-SHA256) with unique per-message nonce + associated data (replay protection).
- **Per-peer sessions**: Each link has an independent session key (`PeerSessionManager`); relays forward encrypted payloads without decrypting user content.
- **Relay Privacy**: Multi-hop relay nodes forward packets blindly without decrypting voice text.
- **Ephemeral Audio Buffers**: Audio recording memory buffers are cleared immediately after transcription.
- **Integrity**: HMAC-SHA256 auth tag in the binary codec detects and rejects any tampered packets.

---

## 6. Verification Suite Execution

Run the complete AI4Bharat integration test suite:
```bash
python benchmark/test_ai4bharat_integration.py
```

### Verified Test Matrix:
- [x] **Test 1 — Hindi STT**: Transcribes Hindi voice to native Devanagari script.
- [x] **Test 2 — Marathi STT**: Transcribes Marathi voice to native Devanagari script.
- [x] **Test 3 — STT → AES Encrypt → Offline Tx → Decrypt**: End-to-end encryption verified.
- [x] **Test 4 — AI4Bharat TTS Synthesis**: Synthesizes 22.05kHz PCM audio waveforms locally.
- [x] **Test 5 — Multi-Hop Encrypted Mesh (A → C → D → B)**: 3-hop delivery without plaintext exposure on intermediate nodes.
- [x] **Test 6 — Destination Unavailable**: Automatic Store-and-Forward Outbox buffering.
- [x] **Test 7 — Multi-Node Channel Isolation**: Concurrent nodes operate without crosstalk.
- [x] **Test 8 — 100% Offline / Airplane Mode**: Zero internet/cloud API calls.
