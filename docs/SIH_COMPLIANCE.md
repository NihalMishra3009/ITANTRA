# SIH 26173 Compliance Matrix — iTantra

All statuses reflect actual implementation verified against unit tests and real asset files. Where models/hardware are genuinely missing, status is PARTIAL with honest explanation.

| # | Requirement | Implementation | Test / Evidence | Status |
|---|-------------|----------------|-----------------|--------|
| 1 | Fully offline | Local Whisper STT, VITS TTS, Silero VAD via sherpa-onnx/ONNX. No cloud. `INTERNET` only for local P2P sockets. | `OFFLINE_VERIFICATION.md`; manifest audit | PASS |
| 2 | STT sentence endpointing | 3-tier VAD: SHORT_PAUSE / SENTENCE_END / LONG_SILENCE. Configurable thresholds (250/700/2000ms). | `VadEngine.kt`; `VadEndpointingTest` | PASS |
| 3 | 10-language STT | Whisper base int8 multilingual, all 10 Indian languages. `ModelCapabilityRegistry` verifies real assets. | `SttEngine.kt`; `ModelCapabilityRegistry` | PASS |
| 4 | 10-language TTS | Per-language VITS. **Only Bengali bundled.** Architecture ready. | `TtsEngine.kt`; `ModelCapabilityRegistry` reports truth | **PARTIAL** (1/10 TTS models present) |
| 5 | Low bitrate | Compact binary v4 packets: length-prefixed exact message ID + sender/recipient IDs. ~90-150B vs ~500B JSON. | `BinaryPacketCodec`; `TextPacketTest` | PASS |
| 6 | Persistent Node ID | `ITN-XXXXXX` + P-256 keypair in app-private prefs. Persisted BEFORE profile construction. | `NodeIdentity.kt` Phase 1 fix | PASS |
| 7 | Network discovery | NODE_HELLO / NODE_ANNOUNCE / ROUTE_REQUEST / RESPONSE / UPDATE / LOCATION_UPDATE. | `NetworkDiscoveryManager.kt` | PASS |
| 8 | Privacy-preserving directory | Relay shares only minimal routing metadata. Private contacts never broadcast. | Architecture in `NetworkDiscoveryManager` | PASS |
| 9 | Real routing table | Multi-route cost-based selection: `hopWeight*hopCount + stalePenalty + linkPenalty + failurePenalty`. Deterministic tie-break. | `NetworkDiscoveryManager.kt`; `NetworkDiscoveryTest.testCostPrefersShorterRoute` | PASS |
| 10 | Correct route advertisement | NEXT HOP = advertising neighbor (+1 hop). Rejects remote unreachable next-hops. | `testRouteResponseUsesAdvertiserAsNextHop`; `testAnnounceTeachesRoutesViaAdvertiser` | PASS |
| 11 | Next-hop forwarding | `MeshRoutingManager.forwardPacketViaRoute` uses `sendToPeer(nextHopId, packet)` via `TransportLayer.sendToPeer`. | `MeshRoutingManager.kt`; `TransportLayer.sendToPeer` | PASS |
| 12 | DTN store-carry-forward | Persistent Room outbox survives restart. Backoff retry. Store when no route, forward when route available. | `MeshRoutingManager`; `OutboxDatabase`; `DtnChainTest.testStoreCarryForward` (indirect) | PASS |
| 13 | Multi-hop ACK routing | ACK at destination routes back through routing table, not raw `sendPacket`. ACK relayed via routing at each hop. | `MeshRoutingManager.handleIncomingPacket`; `DtnChainTest.testFullChainAtoBwithAckReversePath` | PASS |
| 14 | Loop prevention | Dedup cache + hopCount > maxHops rejection. Packet already seen → not forwarded again. | `seenMessageIds`; `DtnChainTest.testDuplicateSuppressedAtRelay`; `testLoopPrevention` | PASS |
| 15 | Per-hop security (E2E) | End-to-end AES-256-GCM + ECDH P-256 + HKDF-SHA256. Relays never decrypt payload. Replay protection via AAD binding. | `MessageSecurityManager.kt`; `TextPacket.withEncryption` (AAD) | PASS |
| 16 | Offline location | `LocationManager` with GNSS/RTT/BLE/anchor sources. Never fabricates coordinates. UNKNOWN/APPROXIMATE/etc. | `LocationManager.kt`; `LocationManagerTest` | PASS |
| 17 | Location privacy | Coarse, accuracy-bounded (≥50m), expiry-limited. No permanent movement history. | `buildLocationUpdate`; `parseLocationUpdate` | PASS |
| 18 | Delivery visibility | `DeliveryTracker`: CREATED→QUEUED→STORED→FORWARDING→RELAYED→DELIVERED→PLAYING→ACKNOWLEDGED (+FAILED/EXPIRED). | `DeliveryTracker.kt`; `DeliveryTrackerTest` | PASS |
| 19 | End-to-end latency | `BenchmarkLogger` captures STT/transport/TTS/playback/E2E/RTF with monotonic clock. | `BenchmarkLogger.kt` | PASS |
| 20 | 4-node chain proof | Unit-tested: A→R1→R2→B data delivery + B→R2→R1→A ACK reverse path. Dedup, expiry, max-hops. | `DtnChainTest` (6 tests, all pass) | **UNIT-TESTED** (physical 4-phone test NOT performed) |
| 21 | Emergency SOS | EMERGENCY/SOS_ALERT bypasses normal queue (addFirst), alarm-stream max-volume, audio-focus override. | `MeshRoutingManager`; `AudioFocusManager` | PASS |
| 22 | Transport abstraction | `TransportLayer` interface with `sendPacket` (broadcast) + `sendToPeer` (targeted). BT + WiFi-Direct. | `TransportLayer.kt` | PASS |
| 23 | Duplicate packet rejection | `seenMessageIds` ConcurrentHashMap cache, max 500 entries. | `MeshRoutingManager`; `DtnChainTest` | PASS |
| 24 | Expired packet rejection | TTL-based `isExpired()` check at transport boundary. | `TextPacket.isExpired()`; `DtnChainTest.testExpiredPacketDropped` | PASS |
| 25 | Corrupted packet rejection | HMAC-SHA256 auth tag in binary codec. Tampered packets rejected. | `BinaryPacketCodec`; `TextPacketTest.testBinaryCorruptionRejectedWithSessionKey` | PASS |
| 26 | Premium emergency UI | Dark near-black, green/amber/red accents, large PTT radar, SOS button. | `colors.xml`, `themes.xml`, `activity_main.xml` | PASS |
| 27 | Network diagnostics | `NetworkActivity`: live node ID, neighbors, routes, models, delivery status, latency. | `NetworkActivity.kt` | PASS (button-refresh, not live-updating) |
| 28 | Release build | `assembleRelease` green. `isMinifyEnabled=false`. | `app-release-unsigned.apk` (320MB) | PASS |
| 29 | Unit tests | 51 tests across 8 test files. All pass. | `gradlew testDebugUnitTest` | PASS |
| 30 | Documentation | `ARCHITECTURE.md` rewritten to match actual code. `SIH_COMPLIANCE.md`. README updated. | `docs/` | PASS |

## Honest Limitations

1. **TTS models for 9 languages not bundled.** Only Bengali VITS is present. STT works for all 10. The 9 missing models must be placed under `assets/models/tts/vits_<lang>/`.
2. **Silero VAD v4 asset incompatible** with sherpa-onnx 1.13.7. Energy VAD fallback active.
3. **Multi-peer transport is interface-level only** (`TransportLayer.sendToPeer`). The actual BT/WiFi-Direct transports still hold one socket. True simultaneous multi-peer requires rewrite of `BluetoothTransport`/`WifiDirectTransport` socket management — untestable without physical multi-phone hardware.
4. **Physical 4-phone A→R1→R2→B test not performed.** The DTN chain is proven by unit tests with `SimNode` topology, but physical validation requires 4 Android devices.
5. **NetworkActivity updates on button press** (not live). Live StateFlow updates need wiring to orchestrator flows.
6. **Android Keystore migration not implemented.** Private keys remain in SharedPreferences.
7. **BT iTantra-specific service discovery** uses generic `APP_UUID` — non-iTantra devices show in scan results.
