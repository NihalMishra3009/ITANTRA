# iTantra — Offline Multilingual Neural Transceiver
**ISRO Problem Statement 26173 | Smart India Hackathon**

[![Android Build](https://img.shields.io/badge/Android-Gradle%20Build%20PASS-brightgreen.svg)]()
[![Inference](https://img.shields.io/badge/On--Device-100%25%20Offline-blue.svg)]()
[![Languages](https://img.shields.io/badge/Languages-10%20Indian%20Languages-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT%2FApache%202.0-green.svg)]()

iTantra is an offline, peer-to-peer neural walkie-talkie Android application engineered for disaster response teams, remote expeditions, and cellular-denied tactical environments. It captures voice audio from the microphone, applies on-device Voice Activity Detection (VAD) and Speech-to-Text (STT), transmits lightweight JSON text packets over Bluetooth or Wi-Fi Direct, and synthesizes speech locally on the receiver phone using on-device Text-to-Speech (TTS).

---

## 🚀 Key Features

- **100% Offline & Private**: Zero internet connection, zero cloud STT/TTS APIs, zero telemetry tracking.
- **10 Indian Languages Supported**: Hindi (`hi`), English (`en`), Gujarati (`gu`), Marathi (`mr`), Kannada (`kn`), Malayalam (`ml`), Tamil (`ta`), Telugu (`te`), Odia (`or`), Bengali (`bn`).
- **Push-To-Talk (PTT) & Continuous Modes**: Intuitive half-duplex walkie-talkie UI with automatic sentence boundary pause detection.
- **Emergency Alert (SOS) Mode**: Broadcasts high-priority alerts with maximum alarm stream audio focus and dual siren chime.
- **Dual Radio Transports**: Bluetooth Classic RFCOMM SPP and Wi-Fi Direct TCP socket with unified framing.
- **Low-Latency & Lightweight**: E2E latency under 600ms, RTF < 0.24, peak RAM < 160MB.

---

## 📁 Repository Structure

```text
iTantra/
├── app/
│   ├── src/main/
│   │   ├── java/com/itantra/
│   │   │   ├── audio/           # AudioRecorder, AudioPlayer, AudioFocusManager
│   │   │   ├── vad/             # VadEngine (Silero VAD / Energy VAD)
│   │   │   ├── stt/             # SttEngine, SttModelManager
│   │   │   ├── tts/             # TtsEngine, TtsModelManager
│   │   │   ├── transport/       # TransportLayer, BluetoothTransport, WifiDirectTransport
│   │   │   ├── protocol/        # TextPacket protocol & framing
│   │   │   ├── orchestrator/    # PipelineOrchestrator (PTT / Continuous / Alert)
│   │   │   ├── benchmark/       # BenchmarkLogger & metrics
│   │   │   └── ui/              # MainActivity UI
│   │   └── res/                 # Layouts, themes, colors, mipmaps
│   └── build.gradle.kts
│
├── model-conversion/            # Offline model acquisition & conversion scripts
│   ├── convert_vad.py
│   ├── convert_stt.py
│   └── convert_tts.py
│
├── benchmark/                   # Verification & Evaluation suite
│   ├── evaluate_wer.py
│   ├── evaluate_latency.py
│   ├── evaluate_efficiency.py
│   ├── stt_results.csv
│   ├── latency.csv
│   └── efficiency.csv
│
├── docs/                        # Complete technical documentation
│   ├── ARCHITECTURE.md
│   ├── MODEL_LICENSES.md
│   ├── OFFLINE_VERIFICATION.md
│   ├── ACCURACY_RESULTS.md
│   ├── EFFICIENCY_RESULTS.md
│   ├── LATENCY_RESULTS.md
│   ├── DEMO_GUIDE.md
│   └── LIMITATIONS.md
│
└── README.md
```

---

## 🛠️ Building & Installing the App

### Prerequisites
- Android Studio / Android SDK (API 24 to API 34)
- JDK 17 or JDK 21

### Build via Gradle Command Line
```powershell
# Run unit tests
.\gradlew.bat testDebugUnitTest

# Assemble Debug APK
.\gradlew.bat assembleDebug
```

The compiled APK will be available at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install to Connected Phones via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🧪 Benchmarking & Verification

Run the Python verification test suite:
```bash
# Evaluate Word Error Rate (WER)
python benchmark/evaluate_wer.py

# Evaluate End-to-End Latency & RTF
python benchmark/evaluate_latency.py

# Evaluate Memory & CPU Efficiency
python benchmark/evaluate_efficiency.py
```

---

## 📜 Documentation Index

- [System Architecture](docs/ARCHITECTURE.md)
- [Model Licenses & Attribution](docs/MODEL_LICENSES.md)
- [Offline Verification Guide](docs/OFFLINE_VERIFICATION.md)
- [Accuracy Benchmark (WER)](docs/ACCURACY_RESULTS.md)
- [Device Efficiency Results](docs/EFFICIENCY_RESULTS.md)
- [Latency Benchmark & RTF](docs/LATENCY_RESULTS.md)
- [Live Demo Script](docs/DEMO_GUIDE.md)
- [System Boundaries & Limitations](docs/LIMITATIONS.md)

---

## 📄 License

This project is licensed under the **MIT License** with open-source acoustic models under Apache 2.0 and CC-BY 4.0. See [MODEL_LICENSES.md](docs/MODEL_LICENSES.md) for details.
