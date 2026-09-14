# SIH FINAL TEST MATRIX — iTantra

Status: **PASS**: physically verified · **UNIT TESTED**: automated test · **IMPLEMENTED**: code/build · **NOT VERIFIED**: requires physical device.

| # | Requirement | Implementation | Unit Test | Integration Test | Physical Test | Evidence | Status | Notes |
|---|-------------|---------------|-----------|------------------|---------------|----------|--------|-------|
| 1 | Offline STT | Whisper base INT8 via sherpa-onnx (bundled) | SttTtsEngineTest, SpeechModelSelectionTest | build green | none | – | IMPLEMENTED | real WER = NOT VERIFIED |
| 2 | Offline TTS | Piper/VITS host packs + Bengali bundled | SttTtsEngineTest | build green | none | – | IMPLEMENTED | playback/RTF = NOT VERIFIED |
| 3 | Offline translation | Opus-MT hi↔en ONNX + JNI | NativeResultParseTest, TranslationPackValidationTest | verify_onnx_parity 12/12 | none | parity script | IMPLEMENTED | on-device run pending pack install |
| 4 | Low bitrate packetization | BinaryPacketCodec v4 | TextPacketTest, codec security tests | – | – | tests | UNIT TESTED | exact msg-id, sender/recip |
| 5 | Encryption | per-peer ECDH/HKDF/AES-GCM/HMAC + replay | SecurityTest, BinaryPacketCodecSecurityTest | – | – | tests | UNIT TESTED | no global key |
| 6 | Bluetooth | TransportManager + BT layer | CompositeTransportTest | – | none | – | IMPLEMENTED | physical = NOT VERIFIED |
| 7 | Wi-Fi | Wi-Fi Direct layer | CompositeTransportTest | – | none | – | IMPLEMENTED | physical = NOT VERIFIED |
| 8 | Mesh relay | MeshRoutingManager | MeshRoutingTest (relay/dup/expired) | – | none | – | UNIT TESTED | A→R→B physical = NOT VERIFIED |
| 9 | Store-and-forward | persistent outbox (DtnChain) | DtnChainTest, MeshDtnIntegrationTest | – | none | – | UNIT TESTED | reconnect-forward physical = NOT VERIFIED |
| 10 | ACK/retry | delivery tracker + retry worker | DeliveryTrackerTest, CompositeTransportTest | – | – | tests | UNIT TESTED | – |
| 11 | SOS priority | EMERGENCY packet, queue preempt, translation bypass | SosPipelineTest, MeshRoutingTest(emergency-jump) | – | none | – | UNIT TESTED | receiver alarm physical = NOT VERIFIED |
| 12 | Hindi→English | Opus-MT hi-en pack | parser tests | parity 12/12 | none | – | NOT VERIFIED(device) | needs 2 phones |
| 13 | English→Hindi | Opus-MT en-hi pack | parser tests | parity 12/12 | none | – | NOT VERIFIED(device) | needs 2 phones |
| 14 | Same-language | bypass pipeline | CrossLanguagePipelineTest | – | none | – | UNIT TESTED | device E2E pending |
| 15 | Latency | BenchmarkLogger (monotonic) + export (P50/P95) | BenchmarkLoggerTest | – | none | – | IMPLEMENTED | device numbers NOT VERIFIED |
| 16 | WER | evaluate_wer.py (real audio) | BenchmarkLoggerTest | honest-skip runs | none | – | NOT VERIFIED | needs 20+ real utterances/lang |
| 17 | TTS RTF | instrumented logger | – | – | none | – | NOT VERIFIED | – |
| 18 | RAM | – | – | – | none | – | NOT VERIFIED | no profiler numbers |
| 19 | CPU | – | – | – | none | – | NOT VERIFIED | – |
| 20 | APK/model size | measured at build | – | assembleDebug/Release | – | 340-342 MB debug & release APK | IMPLEMENTED | 4-ABI unified APK |
| 21 | Offline mode | no runtime network call (source audit) | – | source audit: only ModelDistributionManager uses sockets | none | audit | IMPLEMENTED | airplane-mode physical = NOT VERIFIED |
| 22 | Low/mid-range device | quantized INT8 + mid-class budget | ModelDistributionSelectionTest | – | RMX3870 (native load only) | NATIVE_TEST_OK log | PARTIAL | only native-load verified |
| 23 | Lifecycle stability | full-scope release, idempotent | MeshRoutingTest, VadStateMachineTest | – | none | – | UNIT TESTED | 50× device stress = NOT VERIFIED |

Not a single row is marked PASS without a physical procedure executed. All
device-dependent rows are NOT VERIFIED or PARTIAL.