#!/usr/bin/env python3
"""
Convert ai4bharat/IndicF5 (safetensors) -> self-contained ONNX for iTantra TTS.

WHY IT EXISTS
-------------
IndicF5 is published as PyTorch safetensors, not ONNX. sherpa-onnx / ONNX Runtime
cannot run it as-is. Producing a loadable artifact is a REAL build-machine task.

LANGUAGE HONESTY
----------------
IndicF5 checkpoint languages: hi, gu, mr, kn, ml, ta, te, or, bn (also as, pa).
There is NO English checkpoint. English TTS is never claimed. Use a separate
lightweight English TTS candidate instead.

BUILD-MACHINE REQUIREMENTS
    pip install torch torchaudio onnx onnxruntime onnxruntime-quantization huggingface_hub

BECAUSE THE CHECKPOINT REQUIRES TORCH (and contains a vocoder), NONE OF THIS RUNS
IN THE APP BUILD. We never fake it; the app keeps the bundled Bengali VITS as the
offline TTS until a converted artifact with a pinned SHA-256 is added under
app/src/main/assets/models/tts/indicf5_<lang>/model.onnx.
"""
import argparse
import os
import sys

LANGUAGES = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn"]
DEFAULT_OUT = os.path.join(
    os.path.dirname(__file__), "..", "app", "src", "main", "assets",
    "models", "tts", "indicf5_{lang}", "model.onnx")


def convert(lang: str, src: str, dst: str) -> None:
    if lang == "en":
        raise SystemExit("[iTantra] IndicF5 has NO English checkpoint.")
    if lang not in LANGUAGES:
        raise SystemExit(f"[iTantra] IndicF5 has no {lang} checkpoint.")
    try:
        import torch  # noqa
        import torchaudio  # noqa
        from onnxruntime.quantization import quantize_dynamic, QuantType
        from huggingface_hub import snapshot_download
    except ImportError:
        raise SystemExit(
            "[iTantra] Requires torch/onnx on a build machine: "
            "pip install torch torchaudio onnx onnxruntime onnxruntime-quantization huggingface_hub")
    ckpt = snapshot_download(repo_id=src, allow_patterns=["safetensors"])

    # Load IndicF5 via the model card's custom loader. Export:
    #   - text-to-latent/acoustic model -> model.onnx
    #   - vocoder                      -> vocoder.onnx
    # The exact module/symbols come from the model card code; adapt before running.
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    # torch.onnx.export(text_to_latent, dummy, dst, opset_version=17)
    # quantize_dynamic(dst_fp32, dst, weight_type=QuantType.QUint8)
    raise SystemExit(
        "[iTantra] Adapt this file to the model card loader, then run on a build "
        "machine to produce the real ONNX. The app cannot fabricate the conversion; "
        "the bundled Bengali VITS stays the offline TTS until then.")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True)
    ap.add_argument("--src", default="ai4bharat/IndicF5")
    ap.add_argument("--output", default=None)
    a = ap.parse_args()
    dst = a.output or DEFAULT_OUT.format(lang=a.lang)
    try:
        convert(a.lang, a.src, dst)
        return 0
    except SystemExit as e:
        print(e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())