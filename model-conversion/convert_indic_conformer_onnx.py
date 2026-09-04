#!/usr/bin/env python3
"""
Convert ai4bharat/indic-conformer-600m-multilingual -> a single self-contained ONNX
graph + INT8 quantized for iTantra (ONNX Runtime / sherpa-onnx compatible).

WHY IT EXISTS
-------------
The published model is a CUSTOM-SPLIT ONNX (encoder.onnx 2.9MB stub + ~500 per-tensor
files + a custom model_onnx.py loader). It cannot be loaded as-is by ONNX Runtime.
Producing a loadable artifact is a REAL build-machine task; the steps below are the
actual pipeline. This script verifies its environment and explains exactly what to do;
the exporter itself must run where torch + the (gated) checkpoint are installed.

REQUIREMENTS (build machine, not the Android build):
    pip install torch torchaudio onnx onnxruntime onnxruntime-quantization huggingface_hub
    huggingface-cli login   # the repo ai4bharat/indic-conformer-600m-multilingual is gated

BECAUSE THE CHECKPOINT IS GATED AND REQUIRES TORCH, NONE OF THIS RUNS IN THE APP'S
BUILD. We never fake the conversion; the app keeps the bundled Whisper STT until a
converted artifact with a pinned SHA-256 is added under
app/src/main/assets/models/stt/indic_conformer_<lang>/model.onnx  .
"""
import argparse
import os
import shutil
import sys

LANGUAGES = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn"]
DEFAULT_OUT = os.path.join(
    os.path.dirname(__file__), "..", "app", "src", "main", "assets",
    "models", "stt", "indic_conformer_{lang}", "model.onnx")


def check_torch() -> bool:
    for m in ("torch", "torchaudio", "onnx", "onnxruntime", "huggingface_hub"):
        try:
            __import__(m)
        except ImportError:
            print(f"[iTantra] missing: {m}", file=sys.stderr)
            return False
    return True


def convert(lang: str, src: str, dst: str) -> None:
    """
    REAL export, guarded: only runs when torch + the repo assets are present.
    Uses the model card's own export strategy (IndicASR 80-dim mel, 16 kHz).
    Adapt input_names/output_names/shapes to the exact model card before running.
    """
    if lang not in LANGUAGES:
        raise SystemExit(f"[iTantra] IndicConformer has no {lang} checkpoint.")
    if not check_torch():
        raise SystemExit(
            "[iTantra] torch is not installed. This converter must run on a "
            "build machine: pip install torch torchaudio onnx onnxruntime "
            "onnxruntime-quantization huggingface_hub, then huggingface-cli login."
        )
    import torch  # noqa
    from onnxruntime.quantization import quantize_dynamic, QuantType

    # 1) Acquire the gated checkpoint (requires HF auth on this machine).
    from huggingface_hub import snapshot_download
    ckpt_dir = snapshot_download(repo_id=src, allow_patterns=["*"])
    sys.path.insert(0, ckpt_dir)

    # 2) Load the torch module. The IndicASR repo exposes its model via the model
    #    card code (e.g. model_ts.py / model_onnx.py). Import the loader from the
    #    downloaded path — the exact symbol is defined by the model card.
    #    (Adjust to the model card's loader before running.)
    from model_ts import IndicConformer  # type: ignore  (name from model card)
    model = IndicConformer.from_pretrained(ckpt_dir)  # see model card
    model.eval()

    # 3) Export a single self-contained ONNX (80-dim mel frames, batch, time).
    dummy_mel = torch.zeros(1, 80, 320)  # adapt to model card's spec
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    fp32 = dst + ".fp32.onnx"
    torch.onnx.export(
        model, (dummy_mel,), fp32,
        input_names=["mel"], output_names=["tokens"],
        opset_version=17,
        dynamic_axes={"mel": {0: "batch", 2: "time"}, "tokens": {0: "batch"}},
    )

    # 4) INT8 dynamic quantization.
    quantize_dynamic(fp32, dst, weight_type=QuantType.QUint8)
    os.remove(fp32)
    shutil.rmtree(os.path.join(os.path.dirname(ckpt_dir), "ic_tmp"), ignore_errors=True)
    print(f"[OK] {os.path.getsize(dst)/1e6:.1f} MB -> {dst}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True, choices=LANGUAGES)
    ap.add_argument("--src", default="ai4bharat/indic-conformer-600m-multilingual")
    ap.add_argument("--output", default=None)
    a = ap.parse_args()
    dst = a.output or DEFAULT_OUT.format(lang=a.lang)

    if not check_torch():
        print(
            "[iTantra] IndicConformer conversion is a BUILD-MACHINE step:\n"
            "  1) pip install torch torchaudio onnx onnxruntime onnxruntime-quantization huggingface_hub\n"
            "  2) huggingface-cli login            (gated repo)\n"
            "  3) python convert_indic_conformer_onnx.py --lang hi\n"
            "The app cannot fabricate this. Until a converted artifact with a pinned\n"
            "SHA-256 is added, the bundled Whisper STT remains the offline STT.",
            file=sys.stderr)
        return 1
    try:
        convert(a.lang, a.src, dst)
        return 0
    except SystemExit as e:
        print(e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())