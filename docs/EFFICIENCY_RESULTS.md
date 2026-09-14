# Device Efficiency & Resource Footprint Results

**iTantra — ISRO Problem Statement 26173**

> **STATUS: NOT VERIFIED — the previous RAM/CPU/APK-size table was removed.**
>
> The old values (42.5 MB idle, 142.6 MB STT peak, 28.5% CPU, etc.) were not
> measured on any physical device. They were hard-coded values in a now-deleted
> `benchmark/evaluate_efficiency.py` and do not appear in this repo.

## What can be stated honestly

| Metric | Status | Evidence |
|--------|--------|----------|
| RAM idle | NOT VERIFIED | requires Android profiler run |
| RAM STT peak | NOT VERIFIED | — |
| CPU VAD | NOT VERIFIED | — |
| Debug APK size | IMPLEMENTED | measured at build time (assembleDebug output) |
| Release APK size | IMPLEMENTED | measured at build time (assembleRelease output) |
| Battery drain | NOT VERIFIED | — |

### APK size (build-time honest)

Debug APK includes all 4 ABIs + all bundled assets (Whisper int8 encoder/decoder,
Bengali VITS, Silero VAD). Release APK with ABI split = single-ABI only.

To measure real efficiency: use Android Profiler, record RAM/CPU from a single
instrumented session, export the CSV, then update this page. Per SIH Phase 12,
no number is published before a physical measurement exists.