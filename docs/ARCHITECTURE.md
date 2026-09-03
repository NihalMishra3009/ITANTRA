# iTantra — System Architecture Specification
**ISRO Problem Statement 26173 | Offline Multilingual Neural Transceiver**

---

## 1. High-Level Architectural Overview

iTantra is an offline, delay-tolerant, peer-to-peer neural communication system providing half-duplex digital voice transceiving over short-range radios (Bluetooth Classic RFCOMM and Wi-Fi Direct TCP). It converts voice to compact encrypted text on the sender, routes it through a DTN store-carry-forward mesh (discovery + routing table + multi-neighbor relay), and re-synthesizes speech on the receiver in the original language.

```
+----------------------------------------------------------------------------------------------+
|                                   SENDER NODE (ITN-XXXXXX)                                   |
|                                                                                              |
|  [Microphone]                                                                                |
|       │ (16kHz 16-bit Mono PCM, 32ms chunks)                                                 |
|       ▼                                                                                      |
|  [AudioRecorder] ────► [VadEngine] 3-tier voice endpointing                                  |
|       │                      (Silero ONNX / energy fallback;                                |
|       │                       SHORT_PAUSE / SENTENCE_END / LONG_SILENCE)                    |
|       ▼ (speech buffer)       │ (sentence/long-silence boundary)                            |
|  [SttEngine] Whisper base int8 multilingual (sherpa-onnx, ONNX, all 10 languages)            |
|       │                                                                                      |
|       ▼ (text)                                                                               |
|  [IndicTextNormalizer]                                                                       |
|       │                                                                                      |
|       ▼ (encrypted compact binary packet v3)                                                 |
|  [MessageSecurityManager] AEAD AES-256-GCM + ECDH P-256 session; replay-protected AAD         |
|       │                                                                                      |
|       ▼                                                                                      |
|  [MeshRoutingManager + NetworkDiscoveryManager]                                              |
|       │   store-carry-forward Room outbox, ACK, retry, routing table, multi-neighbor          |
|       ▼                                                                                      |
|  [TransportLayer] (Bluetooth RFCOMM / Wi-Fi Direct TCP)                                      |
+──────────────────────────────────────────┬───────────────────────────────────────────────────+
                                           │  (offline radio hop → relay → destination)
                                           ▼
+----------------------------------------------------------------------------------------------+
|                RELAY NODE(S) (store → carry → forward; retries across hops)                  |
+──────────────────────────────────────────┬───────────────────────────────────────────────────+
                                           ▼
+----------------------------------------------------------------------------------------------+
|                                 DESTINATION NODE                                             |
|  [TransportLayer]                                                                             |
|       ▼                                                                                       |
|  [MeshRoutingManager] dedup, TTL, route, ACK generation                                       |
|       ▼ (delivered packet)                                                                    |
|  [PipelineOrchestrator]                                                                       |
|       ▼                                                                                       |
|  [TtsEngine] VITS neural TTS (sherpa-onnx, ONNX)                                              |
|       │ (PCM samples)                                                                         |
|       ▼                                                                                       |
|  [AudioFocusManager] ──► [AudioPlayer (AudioTrack)] ──► Speaker                               |
|  (normal vs STREAM_ALARM max volume for alerts)                                               |
|       ▼                                                                                       |
|  [DeliveryTracker] DELIVERED → PLAYING → ACK (UI visibility)                                  |
+----------------------------------------------------------------------------------------------+
```

---

## 2. Component Design & Responsibilities

### 2.1 Node Identity (`com.itantra.identity`)
- **`NodeIdentity`**: Persistent, transport-independent application identity (`ITN-XXXXXX`) with a P-256 keypair stored in app-private prefs. A node ID survives Bluetooth/Wi-Fi transport changes; the MAC address is only a transport-level identifier, never the app identity.

### 2.2 Audio Subsystem (`com.itantra.audio`)
- **`AudioRecorder`**: Captures 16 kHz 16-bit mono PCM via `VOICE_RECOGNITION`, dispatches 512-sample (32 ms) float arrays over a coroutine `SharedFlow`.
- **`AudioPlayer`**: Plays synthesized PCM via `AudioTrack` (low-latency); `isAlert` routes to alarm stream for non-interruptible emergency playback.
- **`AudioFocusManager`**: Requests transient ducking for speech, exclusive alarm-stream focus + max volume for alerts.

### 2.3 Voice Activity Detection (`com.itantra.vad`)
- **`VadEngine`**: 3-tier voice endpointing satisfying "STT forms sentences after detecting pauses":
  - `SHORT_PAUSE` — brief break within a sentence (possible partial boundary)
  - `SENTENCE_END` — normal pause, forms a sentence boundary
  - `LONG_SILENCE` — long silence, finalizes the utterance
  - Thresholds (`shortPauseMs`, `sentenceEndMs`, `longSilenceMs`) are configurable.
  - Uses Silero VAD (sherpa-onnx) when the model is compatible; otherwise falls back to RMS energy VAD (clearly reported, never presented as neural).

### 2.4 On-Device Speech-to-Text (`com.itantra.stt`)
- **`SttEngine`**: One Whisper base int8 (multilingual) ONNX model via sherpa-onnx `OfflineRecognizer` covers all 10 languages. Streaming partial transcripts during speech; final decode at utterance end.
- **`SupportedLanguage` / `SttModelManager`**: enum of 10 languages (hi/en/gu/mr/kn/ml/ta/te/or/bn) + `SttResult`.

### 2.5 On-Device Text-to-Speech (`com.itantra.tts`)
- **`TtsEngine`**: VITS neural TTS via sherpa-onnx `OfflineTts`, per-language `models/tts/vits_<lang>/model.onnx` + `tokens.txt`. Returns empty audio (never fake speech) when a language's model is absent. Currently only Bengali is bundled.

### 2.6 Model Capability Registry (`com.itantra.ai4bharat`)
- **`ModelCapabilityRegistry`**: Verifies actual asset files on disk (not README claims). Reports `sttAvailable` / `ttsAvailable` per language; powers the diagnostics/verification screen.

### 2.7 Transport Layer (`com.itantra.transport`)
- **`TransportLayer` (interface)**: Clean abstraction decoupling routing from radio hardware — routing never cares whether the hop is Bluetooth, Wi-Fi Direct, or a future embedded/LoRa-like radio.
- **`BluetoothTransport`**: RFCOMM SPP (`8ce255c0-200a-11e0-ac64-0800200c9a66`), discovery incl. unpaired + auto-bonding.
- **`WifiDirectTransport`**: Wi-Fi P2P group negotiation, TCP ServerSocket on 8888, real group-owner IP resolution.

### 2.8 DTN Routing, Discovery & Delivery (`com.itantra.transport`)
- **`NetworkDiscoveryManager`**: Neighbor registry + routing table. Protocol: `NODE_HELLO`, `NODE_ANNOUNCE`, `ROUTE_REQUEST`, `ROUTE_RESPONSE`, `ROUTE_UPDATE`, `LOCATION_UPDATE`. Route selection is cost-based (hop count + staleness + link + failure penalties). Shares only minimal routing metadata — never private contact lists.
- **`MeshRoutingManager`**: Store-carry-forward — persistent Room outbox, exponential-backoff retry, ACK, TTL, dedup, emergency preemption, multi-hop relay (route-aware). Group/zone addressing is delivered locally and relayed without requiring ACK.
- **`DeliveryTracker`**: Per-message lifecycle `CREATED → QUEUED → STORED → FORWARDING → FORWARDED → DELIVERED → PLAYING → ACKNOWLEDGED` (+ FAILED/EXPIRED), driven by real events.

### 2.9 Protocol (`com.itantra.protocol`)
- **`TextPacket`**: in-memory message model + `Destination`/`AddressMode` (INDIVIDUAL / GROUP / ZONE). E2E-encrypted payload; routing metadata kept separate.
- **`BinaryPacketCodec`**: v3 compact wire format — carries sender/recipient node IDs + HMAC-SHA256 auth. v2 decode retained for backward compatibility. Text-only: raw audio never transmitted.

### 2.10 Security (`com.itantra.security`)
- **`MessageSecurityManager`**: AEAD AES-256-GCM + ECDH P-256 + HKDF-SHA256. Replay protection via per-message AAD binding and unique nonces. No hard-coded secrets; no plaintext private keys.

### 2.11 Location (`com.itantra.location`)
- **`LocationManager`**: Offline, privacy-preserving location from GNSS / Wi-Fi-RTT / BLE-RSSI / relay anchors (not GPS-only). Never fabricates coordinates — reports `UNKNOWN`/`APPROXIMATE`/`ESTIMATED`/`LAST_KNOWN`/`EXACT`. Advertised positions are coarse (accuracy-bounded) and expiry-limited.

### 2.12 Benchmark (`com.itantra.benchmark`)
- **`BenchmarkLogger`**: monotonic-clock latency capture (STT / transport / TTS / playback / E2E / RTF) and measured binary-vs-JSON packet size. No hardcoded values.

### 2.13 Orchestrator (`com.itantra.orchestrator`)
- **`PipelineOrchestrator`**: Central state machine (IDLE/LISTENING/TRANSCRIBING/TRANSMITTING/RECEIVING/SYNTHESIZING/PLAYING/COLLISION_BUSY). Integrates VAD → STT → encrypt → route → decrypt → TTS → playback; PTT, continuous, and SOS modes; shared via `iTantraApp` for secondary screens.

### 2.14 UI (`com.itantra.ui`)
- **`MainActivity`**: premium dark emergency UI — large PTT radar, status visuals, language/link/mode selectors, transcripts, latency chip, SOS.
- **`NetworkActivity`**: network map + diagnostics reading live backend state (node id, neighbors, routing table, verified model capability, delivery status, latency) — no fake nodes or numbers.

---

## 3. Data Flow — Voice to Voice

```text
VOICE → OFFLINE STT → SENTENCE DETECTION → TEXT → ENCRYPTION
     → DESTINATION DISCOVERY → DTN ROUTING → STORE/CARRY/FORWARD
     → MULTI-HOP TRANSPORT → DESTINATION → DECRYPTION
     → OFFLINE TTS → AUDIO → ACK → DELIVERY CONFIRMATION
```
