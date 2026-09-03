# iTantra — Offline Multilingual Neural Transceiver
**ISRO Problem Statement 26173 | Smart India Hackathon**

[![Android Build](https://img.shields.io/badge/Android-Gradle%20Build%20PASS-brightgreen.svg)]()
[![Inference](https://img.shields.io/badge/On--Device-100%25%20Offline-blue.svg)]()
[![STT](https://img.shields.io/badge/STT-Whisper%20base%20int8-purple.svg)]()
[![TTS](https://img.shields.io/badge/TTS-VITS%20ONNX-purple.svg)]()
[![VAD](https://img.shields.io/badge/VAD-Silero-blue.svg)]()
[![Security](https://img.shields.io/badge/Payload-AES--256--GCM%20%2B%20ECDH-red.svg)]()
[![Protocol](https://img.shields.io/badge/Protocol-Compact%20Binary-teal.svg)]()
[![Languages](https://img.shields.io/badge/Languages-10%20Indian%20Languages-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT%20%2F%20Apache%202.0-green.svg)]()

**iTantra** is a fully offline, peer-to-peer multilingual neural transceiver engineered for disaster response teams, deep-space simulation habitats, remote field expeditions, and cellular/satellite-denied environments. It captures audio from the microphone, applies on-device Voice Activity Detection (VAD) and **OpenAI Whisper multilingual Speech-to-Text (STT) via sherpa-onnx / ONNX Runtime**, encrypts the payload with **AEAD AES-256-GCM** derived from an **ECDH P-256 session handshake**, transmits compact binary packets over **Bluetooth RFCOMM / Wi-Fi Direct** with a **persistent Room store-and-forward outbox**, and synthesizes speech on receiver devices using **VITS neural Text-to-Speech**.

All model assets run locally via ONNX Runtime — **no cloud APIs, no internet**.

---

## 🌟 Core Architecture & Pipeline

```text
USER SPEAKS
      ↓
MICROPHONE (16kHz 16-bit Mono PCM, 32ms chunks)
      ↓
SILERO VAD (sherpa-onnx / ONNX Runtime) + sentence endpointing
      ↓
OPENAI WHISPER base int8 MULTILINGUAL STT (all 10 languages, ONNX)
      ↓
INDICTEXTNORMALIZER (Unicode NFC, Indic punctuation cleanup)
      ↓
STREAMING PARTIAL + SENTENCE ENDPOINTING
      ↓
MESSAGESECURITYMANAGER (AEAD AES-256-GCM, ECDH P-256 session handshake)
      ↓
COMPACT BINARY PACKET (BinaryPacketCodec: 28B header + HMAC-SHA256 auth)
      ↓
OFFLINE RADIO TRANSPORT (Bluetooth RFCOMM / Wi-Fi Direct — real group-owner IP)
      ↓  (Persistent Room outbox, ACK, retry, emergency priority, multi-hop relay)
RECEIVER DESTINATION NODE
      ↓
MESSAGESECURITYMANAGER (AEAD decryption)
      ↓
VITS NEURAL TTS (ONNX via sherpa-onnx OfflineTts)
      ↓
SPEAKER AUDIO PLAYBACK (AudioTrack; alert uses alarm stream + audio focus)
```

### Latency / Low-Bitrate Path

```text
SPEECH → TEXT → COMPACT PACKET → WIRELESS LINK → TEXT → TTS → SPEECH
```

Audio is never transmitted. Only the compact UTF-8 text packet travels over the air; the receiver re-synthesizes speech locally.

---

## 🚀 Key Features

- **100% Offline Operation**: zero cloud STT/TTS APIs, zero telemetry, zero internet dependency.
- **10 Indian Languages Supported**: Hindi (`hi`), Marathi (`mr`), Bengali (`bn`, TTS), Gujarati (`gu`), Odia (`or`), Tamil (`ta`), Telugu (`te`), Kannada (`kn`), Malayalam (`ml`), English (`en`).
  - **STT**: ONE multilingual **Whisper base int8** model recognizes all 10 languages (verified in `ModelCapabilityRegistry`).
  - **TTS**: per-language **VITS ONNX** models via sherpa-onnx. **Currently only Bengali (`vits_bn`) is bundled**; the other 9 languages report TTS unavailable honestly. The architecture loads any `models/tts/vits_<lang>/` present.
- **Real Model Inference (ONNX Runtime)**:
  - **VAD**: Silero VAD (v5/v6 compatible via sherpa-onnx).
  - **STT**: OpenAI Whisper base int8 (encoder + decoder ONNX).
  - **TTS**: VITS neural models (per-language `model.onnx` + `tokens.txt`).
  - No fake/placeholder models; every `.onnx` is a genuine trained binary.
- **Modern Transport Security**:
  - **AEAD AES-256-GCM** per-payload (confidentiality + integrity + authentication + replay protection).
  - **ECDH P-256** ephemeral key agreement + **HKDF-SHA256** to derive a shared session key between two phones.
  - No hard-coded secrets.
- **Compact Binary Protocol**: `BinaryPacketCodec` (v3) replaces JSON on the wire — sender/recipient node IDs + HMAC-SHA256 auth, dramatically smaller than JSON, ideal for low-bitrate links.
- **Persistent Store-and-Forward**: messages persist in a **Room outbox** that survives app restart; ACK, exponential-backoff retry, duplicate suppression, TTL, multi-hop relay, and emergency priority (emergency bypasses the normal queue).
- **Dual Radio Transports**: Bluetooth Classic RFCOMM (with full in-range discovery incl. unpaired devices + auto-bonding) and Wi-Fi Direct P2P TCP (with real group-owner IP resolution).
- **Walking-Talkie & SOS Modes**: Push-To-Talk (PTT), Continuous hands-free, and High-Priority Emergency SOS with audio-focus override.
- **Real Benchmarking**: monotonic-clock latency capture (STT / transport / TTS / E2E / RTF) and measured binary-vs-JSON packet size.
- **DTN Network Layer**: application-level `ITN-XXXXXX` node identity (transport-independent), neighbor discovery (NODE_HELLO/NODE_ANNOUNCE), a real routing table with cost-based next-hop selection (ROUTE_REQUEST/RESPONSE/UPDATE), multi-neighbor relay, and store-carry-forward delivery tracked by a `DeliveryTracker` (QUEUED→STORED→FORWARDING→DELIVERED→ACKNOWLEDGED).
- **Offline Location & Privacy**: `LocationManager` uses GNSS/Wi-Fi-RTT/BLE-RSSI/relay-anchor sources (never GPS-only), never fabricates coordinates, and advertises coarse, expiry-limited, privacy-preserving positions.
- **Network Map & Diagnostics**: `NetworkActivity` shows live node identity, neighbors, routing table, verified model capability, delivery status, and latency — all driven by real backend state, no fake nodes.

---

## 📁 Repository Structure

```text
iTantra/
├── app/
│   ├── libs/
│   │   └── sherpa-onnx-1.13.7.aar        # sherpa-onnx native runtime (ONNX, ASR, TTS, VAD) via LFS
│   ├── src/main/
│   │   ├── java/com/itantra/
│   │   │   ├── ai4bharat/        # IndicTextNormalizer, LanguageManager, Ai4BharatModelManager, Adapters
│   │   │   ├── security/         # MessageSecurityManager (AEAD AES-256-GCM, ECDH P-256, HKDF), Base64Codec
│   │   │   ├── audio/            # AudioRecorder, AudioPlayer, AudioFocusManager
│   │   │   ├── vad/              # VadEngine (Silero VAD via sherpa-onnx + energy fallback)
│   │   │   ├── stt/              # SttEngine (Whisper base int8 multilingual via sherpa-onnx)
│   │   │   ├── tts/              # TtsEngine (VITS ONNX via sherpa-onnx OfflineTts)
│   │   │   ├── transport/        # MeshRoutingManager, OutboxDatabase (Room), BluetoothTransport, WifiDirectTransport
│   │   │   ├── protocol/         # TextPacket + BinaryPacketCodec (compact binary wire format)
│   │   │   ├── orchestrator/     # PipelineOrchestrator (state machine, PTT, Continuous, SOS, streaming STT)
│   │   │   ├── benchmark/        # BenchmarkLogger & metrics (latency / RTF / packet size)
│   │   │   └── ui/               # MainActivity (premium emergency-communication UI)
│   │   ├── assets/models/        # Genuine ONNX models: Whisper base, Silero VAD, VITS-TTS (via LFS)
│   │   └── res/                  # Layouts, themes, colors, drawables
│   └── build.gradle.kts
│
├── benchmark/                    # Verification & Benchmarking Suite
│   ├── verify_ai4bharat_complete.py   # model / pipeline validation & audit
│   ├── test_ai4bharat_integration.py  # end-to-end integration
│   ├── test_mesh_routing.py           # multi-node store-and-forward test
│   ├── evaluate_wer.py                # Word Error Rate (WER) benchmark
│   ├── evaluate_latency.py            # per-stage latency benchmark
│   └── evaluate_efficiency.py         # memory / CPU / APK footprint
│
├── docs/                         # Technical Architecture & Verification Documentation
│   ├── ARCHITECTURE.md                # system design & module breakdown
│   ├── MODEL_LICENSES.md              # open-source licenses (MIT / Apache 2.0)
│   ├── OFFLINE_VERIFICATION.md        # airplane-mode & zero-network proof
│   ├── ACCURACY_RESULTS.md            # WER / CER accuracy metrics
│   ├── EFFICIENCY_RESULTS.md          # RAM, CPU, battery benchmarks
│   ├── LATENCY_RESULTS.md             # end-to-end latency breakdown
│   ├── DEMO_GUIDE.md                  # step-by-step demo walkthrough
│   └── LIMITATIONS.md                 # physical boundaries & model-availability constraints
│
└── README.md
```

> **Model assets** (Whisper base `.onnx`, Silero `.onnx`, VITS `.onnx`, `sherpa-onnx .aar`) are stored via **Git LFS**. After cloning run `git lfs pull` to fetch the real binaries.

---

## 🛠️ Building & Installing the App

### Prerequisites
- Android Studio (API 24 to API 34) + Android SDK, JDK 17+
- `git-lfs` installed (`git lfs pull` after clone to fetch model binaries)
- Python 3.8+ (only for `benchmark/` suites)

### 1. Run JVM Unit Tests
```powershell
.\gradlew.bat testDebugUnitTest
```

### 2. Assemble Debug APK
```powershell
.\gradlew.bat assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`

### 3. Install & Launch
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.itantra/.ui.MainActivity
```

---

## 🧪 Automated Benchmarking & Verification Suite

```powershell
# Model / pipeline validation
python benchmark/verify_ai4bharat_complete.py

# End-to-end encryption + transceiver test
python benchmark/test_ai4bharat_integration.py

# Multi-node mesh & store-and-forward partition test
python benchmark/test_mesh_routing.py

# Accuracy & latency benchmarks
python benchmark/evaluate_wer.py
python benchmark/evaluate_latency.py
python benchmark/evaluate_efficiency.py
```

### On-Device Verification
Run `adb logcat -s WhisperSttEngine TtsEngine VadEngine iTantraBenchmark` to observe real-time:
- STT latency / RTF, transport latency, TTS latency, E2E latency
- Binary vs JSON packet size per transmitted message

---

## 📊 Benchmarking

The app captures **real monotonic-clock measurements** at each pipeline stage:

| Metric | Source |
|---|---|
| STT latency & RTF | `BenchmarkLogger.logInteraction` (t2→t3) |
| Transport latency | packet transmit→receive (t5→t6) |
| TTS latency | text→first PCM (t7→t8) |
| End-to-end (E2E) | speech start→playback start (t0→t9) |
| Packet size | `BinaryPacketCodec` bytes vs equivalent JSON (`logPacketSize`) |

Values are best read live on a target device via logcat or the Diagnostics UI rather than as hard-coded claims.

---

## 📜 Licenses & Attribution

- **OpenAI Whisper**: [MIT License](https://github.com/openai/whisper/blob/main/LICENSE).
- **sherpa-onnx** (inference runtime): [Apache 2.0](https://github.com/k2-fsa/sherpa-onnx).
- **Silero VAD**: [MIT License](https://github.com/snakers4/silero-vad).
- **VITS TTS**: [MIT License](https://github.com/jaywalnut310/vits).
- **iTantra Source Code**: **MIT License**.
