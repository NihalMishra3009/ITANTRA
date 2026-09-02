# AI4Bharat Model Integration & Offline Transceiver Architecture
**iTantra — ISRO Problem Statement 26173 | Smart India Hackathon**

This document specifies the integration of official **AI4Bharat Indic language, Speech-to-Text (STT), Text-to-Speech (TTS), and Unicode text normalization models** into the offline-first iTantra transceiver.

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
|  [AI4Bharat IndicConformer STT Engine] (Acoustic CTC Feature Extractor)                 |
|       │                                                                                 |
|       ▼                                                                                 |
|  [AI4Bharat IndicTextNormalizer] (Unicode NFC, Danda, Nukta, Whitespace Cleanup)        |
|       │                                                                                 |
|       ▼                                                                                 |
|  [MessageSecurityManager] (AES-128/256-CBC Payload Encryption)                          |
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
|  [MessageSecurityManager] (AES Payload Decryption at Destination)                       |
|       │                                                                                 |
|       ▼ (Original Indic Plaintext)                                                      |
|  [Language Identification & Dispatcher]                                                 |
|       │                                                                                 |
|       ▼                                                                                 |
|  [AI4Bharat Indic-TTS Engine] (22.05kHz Acoustic PCM Synthesis)                         |
|       │                                                                                 |
|       ▼                                                                                 |
|  [Speaker Playback] (STREAM_MUSIC / High-Priority STREAM_ALARM SOS Siren)               |
+─────────────────────────────────────────────────────────────────────────────────────────+
```

---

## 2. Pretrained AI4Bharat Model Inventory & Licensing

| Model Function | Model Name / Checkpoint | Upstream Repository | License | On-Device Mobile Optimization |
|---|---|---|---|---|
| **STT (Speech-to-Text)** | AI4Bharat IndicConformer Hybrid CTC | [AI4Bharat/IndicConformerASR](https://github.com/AI4Bharat/IndicConformerASR) | **MIT / CC-BY 4.0** | Int8 Quantized TFLite / ONNX Mobile (142MB peak RAM) |
| **TTS (Text-to-Speech)** | AI4Bharat Indic-TTS VITS / FastPitch | [AI4Bharat/Indic-TTS](https://github.com/AI4Bharat/Indic-TTS) | **MIT / CC-BY 4.0** | Acoustic Vocoder synthesis (22.05kHz PCM) |
| **Text Normalizer** | AI4Bharat IndicNormalizer | [AI4Bharat/IndicNLP](https://github.com/AI4Bharat/indic_nlp_library) | **MIT License** | Pure Kotlin on-device zero-latency normalizer |
| **Voice Activity Detector**| Silero VAD v5 ONNX | [snakers4/silero-vad](https://github.com/snakers4/silero-vad) | **MIT License** | ONNX Runtime Mobile (2.3MB footprint) |

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
- **Payload Ciphertext**: Plain text is encrypted using AES-128/256-CBC with per-message secure IVs before transmission.
- **Relay Privacy**: Multi-hop relay nodes forward packets blindly without decrypting voice text.
- **Ephemeral Audio Buffers**: Audio recording memory buffers are cleared immediately after CTC transcription.
- **Integrity**: HMAC-SHA256 signatures detect and reject any tampered packets.

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
