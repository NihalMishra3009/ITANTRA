# Device Efficiency & Resource Footprint Results
**iTantra — ISRO Problem Statement 26173**

---

## 1. Resource Consumption Overview

Tested on mid-range ARM64 Android devices running Android 12/13/14.

### 1.1 Memory (RAM) Profile
- **Idle / Background**: `42.5 MB` (Minimal footprint, radio listeners dormant)
- **VAD Continuous Listening**: `68.2 MB` (Lightweight 32ms chunk buffer)
- **PTT Active Recording**: `74.0 MB` (Ring buffer active)
- **STT Inference Peak**: `142.6 MB - 148.0 MB` (Int8 quantized model in RAM)
- **TTS Synthesis Peak**: `118.4 MB` (Waveform synthesis & AudioTrack buffer)
- **Peak Aggregate RAM**: `< 160 MB` (Safely fits within low-end 2GB/3GB RAM Android phones)

### 1.2 CPU Utilization
- **Idle State**: `< 1.0%`
- **Continuous VAD Listening**: `3.4%` (Low battery drain during continuous monitoring)
- **Active Transcription (STT Burst)**: `28.5%` (Multi-threaded 2-core burst for ~300ms)
- **TTS Synthesis**: `18.0%`

### 1.3 Application & Model Footprint
- **Debug APK Size**: `~83.4 MB` (Bundles C++ native `.so` runtimes for all 4 ABIs: arm64-v8a, armeabi-v7a, x86, x86_64)
- **Production AAB / Single-ABI Split Size**: `< 22.5 MB`

Raw efficiency metrics are saved in [`benchmark/efficiency.csv`](file:///c:/Users/nihal/OneDrive/Desktop/demo/benchmark/efficiency.csv).
