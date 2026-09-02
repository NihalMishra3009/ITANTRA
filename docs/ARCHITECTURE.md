# iTantra — System Architecture Specification
**ISRO Problem Statement 26173 | Offline Multilingual Neural Transceiver**

---

## 1. High-Level Architectural Overview

iTantra is an offline peer-to-peer neural communication system that provides half-duplex digital voice transceiving over short-range radios (Bluetooth Classic RFCOMM and Wi-Fi Direct TCP). It achieves low-bandwidth, noise-resilient communication by converting voice to compact text on the sender device, transmitting structured JSON text packets, and re-synthesizing the speech audio on the receiving device in the original language.

```
+-----------------------------------------------------------------------------------------+
|                                    SENDER PHONE                                         |
|                                                                                         |
|  [Microphone]                                                                           |
|       │ (16kHz 16-bit Mono PCM)                                                         |
|       ▼                                                                                 |
|  [AudioRecorder] ────────► [VadEngine (Silero ONNX / Energy VAD)]                       |
|       │                         │                                                       |
|       ▼ (Speech buffer)         ▼ (Utterance End / PTT Release)                         |
|  [SttEngine (IndicConformer CTC)]                                                       |
|       │                                                                                 |
|       ▼ (Recognized Text)                                                               |
|  [TextPacket Serializer] (id, sender, lang, text, isAlert, timestamp)                   |
|       │                                                                                 |
|       ▼ (Length-prefixed 4-byte header + JSON)                                          |
|  [TransportLayer] (Bluetooth RFCOMM SPP / Wi-Fi Direct TCP)                             |
+────────────────────────────────────────┬────────────────────────────────────────────────+
                                         │  (Offline Radio Link)
                                         ▼
+─────────────────────────────────────────────────────────────────────────────────────────+
|                                   RECEIVER PHONE                                        |
|                                                                                         |
|  [TransportLayer] (Socket Receiver Thread & Length Framer)                             |
|       │                                                                                 |
|       ▼ (Validated TextPacket)                                                          |
|  [PipelineOrchestrator] (Deduplication & Language Dispatcher)                           |
|       │                                                                                 |
|       ▼                                                                                 |
|  [TtsEngine (Indic-TTS / Formant Vocoder)]                                              |
|       │ (22.05kHz PCM Samples)                                                          |
|       ▼                                                                                 |
|  [AudioFocusManager] ───► [AudioPlayer (AudioTrack)] ───► [Speaker]                     |
|  (Normal vs STREAM_ALARM Max Vol)                                                       |
+-----------------------------------------------------------------------------------------+
```

---

## 2. Component Design & Responsibilities

### 2.1 Audio Subsystem (`com.itantra.audio`)
- **`AudioRecorder`**: Captures raw PCM audio at 16,000 Hz (16-bit Mono) using `VOICE_RECOGNITION` audio source. Dispatches 512-sample (32ms) float arrays across Kotlin Coroutine `SharedFlow`.
- **`AudioPlayer`**: Manages low-latency `AudioTrack` playback for synthesized 22.05 kHz PCM waveforms. Implements thread-safe coroutine mutex locking.
- **`AudioFocusManager`**: Handles `AudioFocusRequest` transitions. For standard speech, requests transient ducking; for `isAlert = true`, forces maximum `STREAM_ALARM` application volume and exclusive focus.

### 2.2 Voice Activity Detection (`com.itantra.vad`)
- **`VadEngine`**: Integrates an ONNX Runtime instance running Silero VAD v4 alongside an adaptive energy/zero-crossing rate estimator. Emits granular state transitions: `SPEECH_START`, `SPEECH_CONTINUE`, `PAUSE_DETECTED`, `SENTENCE_END`, and `SILENCE`. Configured with a default silence boundary threshold of 800ms.

### 2.3 On-Device Speech-to-Text (`com.itantra.stt`)
- **`SttEngine`**: On-device CTC decoding engine supporting 10 Indian languages. Performs argmax per frame, collapsing consecutive duplicate tokens and blank tokens.
- **`SttModelManager`**: Coordinates metadata, scripts, and model handles for:
  - Phase 1: Hindi (`hi`), English (`en`)
  - Phase 2: Gujarati (`gu`), Marathi (`mr`), Kannada (`kn`)
  - Phase 3: Malayalam (`ml`), Tamil (`ta`), Telugu (`te`)
  - Phase 4: Odia (`or`), Bengali (`bn`)

### 2.4 On-Device Text-to-Speech (`com.itantra.tts`)
- **`TtsEngine`**: Generates 22.05 kHz PCM waveforms locally using neural acoustic models and pitch formant synthesis. When `isAlert = true`, synthesizes a loud dual-tone emergency siren chime before the speech payload.

### 2.5 Transport Layer (`com.itantra.transport`)
- **`TransportLayer` (Interface)**: Decouples the transceiver orchestration from radio hardware details.
- **`BluetoothTransport`**: RFCOMM SPP socket implementation using standard service UUID `8ce255c0-200a-11e0-ac64-0800200c9a66`.
- **`WifiDirectTransport`**: Wi-Fi P2P (`WifiP2pManager`) discovery and group negotiation with a TCP ServerSocket on port 8888.

### 2.6 Protocol & Orchestrator (`com.itantra.protocol` & `com.itantra.orchestrator`)
- **`TextPacket`**: 4-byte big-endian length prefix framing around compact JSON payloads.
- **`PipelineOrchestrator`**: Central state machine managing Push-To-Talk, Continuous Conversation, SOS Alerting, and half-duplex collision prevention.
