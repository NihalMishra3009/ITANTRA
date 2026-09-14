# Latency & RTF Results

**iTantra — ISRO Problem Statement 26173**

> **STATUS: NOT VERIFIED — the previous table was removed.**
>
> The old "530ms…612ms E2E, RTF 0.189–0.233" rows were not produced by any
> device measurement: `benchmark/evaluate_latency.py` held hard-coded values and
> the CSV was referenced from an unrelated local path. Per SIH Phase 12, real
> instrumentation is required before any latency number is published.

## 1. What is instrumented (code-level, real)

Stage timers exist and record real monotonic durations:

- STT (`RecordingPipeline`)
- Translation (native per-stage: tokenize / encoder / decoder)
- Packet encode/decode (`TextPacket`/`BinaryPacketCodec` unit-instrumented)
- TTS synthesis + first-audio (`TtsEngine`)

## 2. Honest status

| Stage | Instrumented | Measured on device | Value |
|---|---|---|---|
| STT latency | yes | no | NOT VERIFIED |
| Translation latency | yes | no | host-parity run only (see `model-conversion/verify_onnx_parity.py --bench`) |
| Transport latency | yes | no | NOT VERIFIED |
| TTS latency | yes | no | NOT VERIFIED |
| Full E2E (speech→audio) | no | no | NOT VERIFIED |
| RTF | — | — | NOT VERIFIED |

Publishing real numbers requires SIH Phase 12 (full E2E instrumentation with
`SystemClock.elapsedRealtimeNanos()`) + a physical 2-phone run. Until then this
page states nothing.