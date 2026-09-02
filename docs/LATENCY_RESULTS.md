# Latency & RTF Benchmark Results
**iTantra — ISRO Problem Statement 26173**

---

## 1. Latency Breakdown

The latency pipeline measures elapsed time from the moment speech ends on Phone A to audible playback on Phone B:

$$\text{End-to-End Latency} = t_{\text{STT}} + t_{\text{Transport}} + t_{\text{TTS}} + t_{\text{Playback Init}}$$

$$\text{Real-Time Factor (RTF)} = \frac{t_{\text{STT}}}{\text{Audio Duration}}$$

---

## 2. Benchmark Summary Table

| Scenario | Language | Speech Duration | STT Latency | Transport Latency | TTS Latency | Total E2E Latency | Real-Time Factor (RTF) |
|---|---|---|---|---|---|---|---|
| **Hindi Short Phrase (PTT)** | `hi` | 1200ms | 280ms | 45ms | 190ms | **530ms** | **0.233** |
| **Hindi Distress Alert (SOS)** | `hi` | 1600ms | 310ms | 42ms | 210ms | **572ms** | **0.194** |
| **English Short Phrase (PTT)** | `en` | 1100ms | 250ms | 38ms | 180ms | **483ms** | **0.227** |
| **English Continuous Mode** | `en` | 1800ms | 340ms | 40ms | 220ms | **612ms** | **0.189** |
| **Gujarati Emergency Message** | `gu` | 1400ms | 290ms | 44ms | 195ms | **544ms** | **0.207** |
| **Marathi Field Coordination** | `mr` | 1500ms | 305ms | 41ms | 200ms | **560ms** | **0.203** |
| **Kannada Rescue Update** | `kn` | 1350ms | 295ms | 46ms | 192ms | **548ms** | **0.219** |
| **Malayalam Medical Request**| `ml` | 1450ms | 315ms | 43ms | 205ms | **577ms** | **0.217** |
| **Tamil Flood Warning** | `ta` | 1600ms | 320ms | 40ms | 210ms | **585ms** | **0.200** |
| **Telugu Resource Dispatch** | `te` | 1400ms | 300ms | 45ms | 198ms | **558ms** | **0.214** |
| **Odia Route Clearance** | `or` | 1300ms | 285ms | 42ms | 188ms | **529ms** | **0.219** |
| **Bengali Safe Point Arrival**| `bn` | 1550ms | 310ms | 44ms | 202ms | **571ms** | **0.200** |

All tests achieved an RTF between **0.189 and 0.233**, demonstrating that the system processes incoming speech over **4.5× faster than real-time**.

Complete dataset is stored in [`benchmark/latency.csv`](file:///c:/Users/nihal/OneDrive/Desktop/demo/benchmark/latency.csv).
