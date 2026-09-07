# iTantra — Offline Multilingual Neural Transceiver
**ISRO Problem Statement 26173 | Smart India Hackathon**

[![Android Build](https://img.shields.io/badge/Android-Gradle%20Build%20PASS-brightgreen.svg)]()
[![Inference](https://img.shields.io/badge/On--Device-100%25%20Offline-blue.svg)]()
[![STT](https://img.shields.io/badge/STT-Whisper%20base%20int8-purple.svg)]()
[![TTS](https://img.shields.io/badge/TTS-VITS%20Piper%2FCoqui%2FMMS-purple.svg)]()
[![VAD](https://img.shields.io/badge/VAD-Energy%20Adaptive%20Fallback-grey.svg)]()
[![Security](https://img.shields.io/badge/Security-Hop--Level%20AEAD%20ECDH-red.svg)]()
[![Protocol](https://img.shields.io/badge/Protocol-v4%20Binary%20Wire-teal.svg)]()
[![Languages](https://img.shields.io/badge/Languages-10%20Indian%20Languages-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT%20%2FApache--2.0-green.svg)]()

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
VITS / PIPER NEURAL TTS (ONNX via sherpa-onnx OfflineTts)
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
- **10 Indian Languages — STT**: ONE multilingual **Whisper base int8** model recognizes all 10 languages (verified in `ModelCapabilityRegistry`).
- **Downloadable TTS Voice Packs (offline after install)**:
  - Verified downloadable voices exist for **all 10 languages**:
    - **Open-source (MIT/CC-BY):** Hindi, English, Malayalam (Piper VITS), Bengali (Coqui TTS)
    - **Restricted non-commercial (CC-BY-NC):** Gujarati (Mimic3), Marathi/Kannada/Tamil/Telugu/Odia (Meta MMS-TTS converted to sherpa-onnx VITS, hosted on the iTantra release, SHA-256 verified). License restrictions are displayed honestly in the Models UI.
  - Bengali VITS (`vits_bn`) is still bundled in the APK as a zero-download fallback.
  - **IndicConformer / IndicF5 are NOT runtime dependencies** of this prototype (their published checkpoints are not directly loadable through the current Android pipeline).
- **Real Model Inference (ONNX Runtime)**:
  - **VAD**: Energy-based adaptive VAD with noise-floor tracking, minimum speech duration, hangover, and clipping detection. Clearly reported as "Energy fallback" — not neural VAD.
  - **STT**: OpenAI Whisper base int8 (encoder + decoder ONNX).
  - **TTS**: VITS neural models (per-language `model.onnx` + `tokens.txt`).
  - No fake/placeholder models; every `.onnx` is a genuine trained binary.
- **Per-Hop Wire Security (honest trust model)**:
  - **Hop-level AES-256-GCM + HMAC-SHA256**: each radio link is authenticated and encrypted with the immediate peer's session key (ECDH P-256 + HKDF-SHA256 derived via `PeerSessionManager`). No global shared key exists.
  - **Relay model (A→R1→B)**: A encrypts with key(A-R1), R1 decrypts+verifies, then re-encrypts with key(R1-B) for B. Routing metadata (sender/recipient/hops) travels unencrypted in the binary header; payload is per-hop encrypted.
  - Bootstrap `SESSION_START` packets carry public keys and are sent without authentication (`FLAG_UNAUTH`) since no shared key exists yet.
  - **Per-peer replay protection** (`ReplayProtection`): bounded per-peer cache with TTL + clock-skew guard. Destination duplicates re-ACK (lost-ACK recovery) without re-delivering. `messageId` retransmission through relays bounded by TTL + maxHops.
  - No hard-coded secrets; no fallback to a global session key.
- **Compact Binary Protocol**: `BinaryPacketCodec` (v4) replaces JSON on the wire — sender/recipient node IDs + HMAC-SHA256 auth, dramatically smaller than JSON, ideal for low-bitrate links. Authenticated packets without a valid peer key are **rejected**.
- **Persistent Store-and-Forward**: messages persist in a **Room outbox** that survives app restart; ACK, exponential-backoff retry, duplicate suppression, TTL, multi-hop relay, and emergency priority (emergency bypasses the normal queue).
- **Dual Radio Transport**: AUTO mode (Bluetooth RFCOMM + Wi-Fi Direct), selectable Bluetooth-only or Wi-Fi Direct-only via the transport selector — the selector **actually drives** the active radios in `CompositeTransport`. Packet size/route/protocol metrics are all real.
- **Dedicated SOS Emergency Pipeline**: press SOS → immediate emergency packet (no microphone, no STT required) → priority queue → store-and-forward → ACK/retry → receiver synthesizes and plays alert at max volume (volume saved/restored afterward). Visible SOS state: SENDING → DELIVERED / RETRYING / QUEUED_NO_PEER.
- **Real Benchmarking**: monotonic-clock latency capture (STT / transport / TTS / playback) using `SystemClock.elapsedRealtime()` via `BenchmarkLogger.nowMs()`. Fabricated timestamps eliminated. Remote E2E uses sender wallclock vs receiver wallclock (documented clock skew), local segments monotonic. Null for unknown values, not "0 ms".
- **DTN Network Layer**: application-level `ITN-XXXXXX` node identity (transport-independent), neighbor discovery (NODE_HELLO/NODE_ANNOUNCE), a real routing table with cost-based next-hop selection (ROUTE_REQUEST/RESPONSE/UPDATE), multi-neighbor relay, and store-carry-forward delivery tracked by a `DeliveryTracker` (QUEUED→STORED→FORWARDING→DELIVERED→ACKNOWLEDGED).
- **Offline Location & Privacy**: `LocationManager` uses GNSS/Wi-Fi-RTT/BLE-RSSI/relay-anchor sources (never GPS-only), never fabricates coordinates, and advertises coarse, expiry-limited, privacy-preserving positions.
- **Network Map & Diagnostics**: `NetworkActivity` shows live node identity, neighbors, routing table, verified model capability, delivery status, and latency — all driven by real backend state, no fake nodes.
- **Atomic Model Installation**: downloads go into `.staging/`, validated (SHA-256 + required files present), then atomically renamed into the live pack directory. Partial/corrupt downloads never expose a broken model as installed. Previous working install preserved on failure.
- **Lifecycle Robustness**: `PipelineOrchestrator.release()` cancels coroutines, transport listeners, and releases engines on `Activity.onDestroy`. Heavy model loads deferred off the UI thread (`Dispatchers.IO`) to avoid ANRs.

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
│   ├── SIH_COMPLIANCE.md              # SIH 26173 requirement -> status matrix
│   ├── MODEL_LICENSES.md              # open-source licenses (MIT / Apache 2.0)
│   ├── OFFLINE_VERIFICATION.md        # airplane-mode & zero-network proof
│   ├── COMMUNICATION_VERIFICATION.md  # mesh / DTN / security verification
│   ├── ACCURACY_RESULTS.md            # WER / CER accuracy metrics
│   ├── EFFICIENCY_RESULTS.md          # RAM, CPU, battery benchmarks
│   ├── LATENCY_RESULTS.md             # end-to-end latency breakdown
│   ├── DEMO_GUIDE.md                  # step-by-step demo walkthrough
│   ├── LIMITATIONS.md                 # physical boundaries & model-availability constraints
│   ├── AI4BHARAT_INTEGRATION.md       # Indic integration & architecture
│   └── MODEL_TUNING_REPORT.md         # quantization / tuning results
│
└── README.md
```

> **Multi-peer mesh + per-peer security + live network UI + Android Keystore** are implemented (see `CompositeTransport`, `PeerSessionManager`, `AndroidKeyStore`, live `NetworkActivity`). Model assets (Whisper/VITS/Silero) are stored via Git LFS — run `git lfs pull` after clone.

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
