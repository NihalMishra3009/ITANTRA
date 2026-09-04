# iTantra Model Licenses

Licenses for models referenced by the repository. Publicly downloadable does not automatically mean unrestricted redistribution; each entry notes the actual license and any use caveat.

## Bundled Assets

| Model | Language | License | Notes |
|-------|----------|---------|-------|
| Whisper base (encoder/decoder int8 ONNX) | all 10 | MIT | OpenAI Whisper code/weights MIT |
| VITS `vits_bn` | Bengali | MIT | Bundled `model.onnx` + `tokens.txt` |
| Silero VAD | — | MIT | Silero model weights MIT |

## Candidate Models (declared in registry, NOT yet bundled)

These are declared as candidate packs. Before bundling, their redistribution license MUST be confirmed against the weights' actual license (check the HuggingFace/GitHub model-card each time):

| Model | Family | License status to verify |
|-------|--------|--------------------------|
| IndicConformer (AI4Bharat) | Paraformer CTC | Verify per-checkpoint; AI4Bharat models commonly CC-BY-NC / or specific licences — confirm before bundling/redistribution |
| IndicF5 (AI4Bharat) | Flow-matching TTS | Verify per-checkpoint; confirm weights + vocoder license (often CC-BY-NC) |
| Lightweight VITS / Piper voices | VITS | piper voices vary (many CC-BY / MIT); verify each voice |

## Rule

Do **not** include a model merely because its page is public. Confirm the **weights license**, **tokenizer license**, and **vocoder license** allow redistribution for a hackathon. This file will be updated once candidate weights are actually selected and bundled.
