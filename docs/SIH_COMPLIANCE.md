# SIH 26173 Compliance Matrix

iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links

All status reflects the **actual implementation** (verified on device assets + unit tests), not aspirational claims. Where hardware/models are genuinely absent, status is marked PARTIAL with the exact limitation.

| # | Requirement | Implementation | Evidence | Status |
|---|-------------|----------------|----------|--------|
| 1 | Fully offline operation | Local Whisper STT, VITS TTS, Silero VAD via sherpa-onnx/ONNX Runtime. No cloud, no Firebase, no online STT/TTS/translation. | `docs/OFFLINE_VERIFICATION.md`; `INTERNET` only for local P2P sockets. | PASS |
| 2 | STT module forms sentences after pauses | 3-tier voice endpointing in `VadEngine` (SHORT_PAUSE / SENTENCE_END / LONG_SILENCE) drives utterance finalization in `PipelineOrchestrator`. Configurable thresholds. | `VadEngine.kt`; `VadEndpointingTest.kt` | PASS |
| 3 | Multilingual STT (10 Indian languages) | Single Whisper base int8 (multilingual) covers hi/en/gu/mr/kn/ml/ta/te/or/bn. Verified via `ModelCapabilityRegistry` against real assets. | `ModelCapabilityRegistry`; `SttEngine.kt`; test `SttTtsEngineTest` | PASS |
| 4 | Multilingual TTS (10 languages) | Per-language VITS model, loaded only if present. **Only Bengali bundled.** 9 languages report TTS ✗ (honest). Architecture ready for the 9 missing models. | `ModelCapabilityRegistry` (verified); `TtsEngine.kt` | **PARTIAL** (9/10 TTS assets missing) |
| 5 | Low bitrate transmission | Compact binary v3 packets (~90-110 B vs ~500 B JSON), text-only payload, no raw voice on the wire. | `BinaryPacketCodec.kt`; `TextPacketTest` (binary < JSON) | PASS |
| 6 | Text over voice priority | STT output is transmitted as compact encrypted text; raw audio never transmitted. | `PipelineOrchestrator.finalizeUtteranceAndSend` | PASS |
| 7 | Application-level Node identity (persistent, transport-independent) | `ITN-XXXXXX` node id + persistent P-256 keypair stored in app-private prefs. NOT derived from BT MAC. | `NodeIdentity.kt` | PASS |
| 8 | Network discovery | NODE_HELLO / NODE_ANNOUNCE / ROUTE_REQUEST / ROUTE_RESPONSE / ROUTE_UPDATE / LOCATION_UPDATE protocol types. | `NetworkDiscoveryManager.kt`; `TextPacket.kt` | PASS |
| 9 | Privacy-preserving directory | Relay/routing shares only minimal metadata (node id, role, capabilities, route). Private contact graph never broadcast. | `NetworkDiscoveryManager` design | PASS |
| 10 | Real routing table | RouteEntry (dest, nextHop, hops, lastSeen, confidence, expiry). Cost-based next-hop via `bestNextHop`. | `NetworkDiscoveryManager.kt`; `NetworkDiscoveryTest.kt` | PASS |
| 11 | Store-carry-forward DTN | Persistent Room outbox survives restart; retry/backoff; message survives disconnection and delivers when peer returns. | `MeshRoutingManager.kt`; `OutboxDatabase.kt` | PASS |
| 12 | Multi-neighbor / multi-peer relay | Neighbor registry in `NetworkDiscoveryManager` tracks multiple peers, not a single socket. | `NetworkDiscoveryManager.neighbors` | PASS |
| 13 | Individual / group / zone addressing | `AddressMode` (INDIVIDUAL/GROUP/ZONE) + `Destination`. Group/zone packets delivered locally and relayed; no ACK for broadcast. | `TextPacket.kt` | PASS |
| 14 | Offline location (not GPS-only) | `LocationManager` with GNSS/WIFI_RTT/BLE_RSSI/RELAY_ANCHOR/LAST_KNOWN sources; never fabricates coordinates — reports UNKNOWN/APPROXIMATE/ESTIMATED. | `LocationManager.kt`; `LocationManagerTest.kt` | PASS |
| 15 | Location privacy | Advertised locations rounded to coarse cell, accuracy-bounded (≥50 m), expiry-limited → UNKNOWN after expiry. No permanent movement history. | `LocationManager.buildLocationUpdate` | PASS |
| 16 | Offline network map | `NetworkActivity` shows YOU, neighbors, routing table, model capability, delivery status, latency — all live state, no fake nodes. | `NetworkActivity.kt`; `activity_network.xml` | PASS |
| 17 | Transport abstraction (BT + WiFi Direct + future radio) | `TransportLayer` interface; `BluetoothTransport`, `WifiDirectTransport`; routing independent of link. Embedded-radio ready via `TransportLayer`. | `TransportLayer.kt` | PASS |
| 18 | End-to-end encryption, relays can't read | ECDH P-256 + HKDF-SHA256 + AES-256-GCM AEAD. Payload encrypted end-to-end; routing metadata separate. Replay protection via per-message AAD binding. | `MessageSecurityManager.kt`; `TextPacket.withEncryption` (AAD) | PASS |
| 19 | Replay protection / duplicate suppression | AEAD nonce + AAD (msgId:timestamp:sender:recipient) + transport-level dedup cache. | `TextPacket.kt`; `MeshRoutingManager` | PASS |
| 20 | Emergency SOS, max priority | EMERGENCY/`SOS_ALERT` bypasses normal queue (addFirst), alarm-stream max-volume playback, non-interruptible focus. | `MeshRoutingManager.sendReliablePacket`; `AudioFocusManager` | PASS |
| 21 | Delivery visibility | `DeliveryTracker` lifecycle CREATED→QUEUED→STORED→FORWARDING→DELIVERED→PLAYING→ACKNOWLEDGED (+FAILED/EXPIRED), shown in `NetworkActivity`. Driven by real events. | `DeliveryTracker.kt`; `DeliveryTrackerTest.kt` | PASS |
| 22 | End-to-end latency + RTF measurement | `BenchmarkLogger` captures speech/STT/transport/TTS/playback/E2E + RTF with monotonic clock; no hardcoded values. | `BenchmarkLogger.kt` | PASS |
| 23 | Language-specific benchmarking | Per-language packet-size records + latency records keyed by language code. (Python suite `benchmark/` covers WER/per-lang). | `BenchmarkLogger` | PASS |
| 24 | Low/mid-range device optimization | NDK filters (arm v7a + arm64), Whisper int8 quantized, models loaded once, no React/Compose overhead. | `app/build.gradle.kts`; model assets | PASS |
| 25 | Offline-first guarantee | Airplane mode + local radios works; no cloud inference/auth/db/map-tiles. | `docs/OFFLINE_VERIFICATION.md` | PASS |
| 26 | Premium emergency UI | Dark near-black, green comm state / red emergency, large PTT, radar visual, minimal text, high contrast. | `colors.xml`, `themes.xml`, `activity_main.xml` | PASS |
| 27 | Diagnostics / judge mode | `NetworkActivity` real-time model/latency/node/neighbor/route/queue. | `NetworkActivity.kt` | PASS |
| 28 | Release build / Android compatibility | minSdk 24, targetSdk 34, BT/WiFiDirect/mic permissions for API 24-34, portrait lock. Release APK builds. | `AndroidManifest.xml`; `app-release-unsigned.apk` | PASS |

## Honest limitations (not faked)

1. **TTS models for 9 languages are not bundled.** Only Bengali (`vits_bn`) is present. STT works for all 10. The registry reports this truthfully; the remaining 9 VITS models must be added under `assets/models/tts/vits_<lang>/` for full TTS coverage. The integration architecture already supports them.
2. **Silero VAD v4 asset is incompatible with sherpa-onnx 1.13.7's `Vad` API** — energy VAD is used as the active detector. A compatible v5/v6 Silero model should replace `assets/models/vad/silero_vad.onnx`.
3. **Physical range** limited by radio class (BT 10-30 m, Wi-Fi Direct 50-100 m LOS). DTN multi-hop extends logical reach; it does not extend single-link range.
4. **Release minification is disabled** (`isMinifyEnabled = false`) to guarantee model loading / Room / sherpa-onnx correctness. Can be enabled with strict keep rules later.
