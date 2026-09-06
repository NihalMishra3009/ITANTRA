#!/usr/bin/env python3
"""
Convert Meta's MMS-TTS checkpoint (facebook/mms-tts) into the VITS-format ONNX
model that sherpa-onnx loads via `OfflineTtsVitsModelConfig`.

Adapted from the official sherpa-onnx MMS recipe
(https://k2-fsa.github.io/sherpa/onnx/tts/mms.html) to the MMS HuggingFace
space layout, and proven on this machine (torch 2.10 CPU):

  Verified output (e.g. Marathi 'mar'):
    model.onnx  ~114 MB   ir 7
    inputs: x, x_lengths, noise_scale, length_scale
    output: 'output'
    metadata: tokens, model_type=vits, sample_rate=16000, vocab_size=N

Usage:
    python convert_mms_tts_onnx.py --lang mar [--out ./converted/mar]

The resulting model.onnx + tokens.txt are loadable by TtsEngine's
downloaded-voice path (sherpa OfflineTtsVitsModelConfig) when placed under
{filesDir}/models/tts/<lang>/{model.onnx,tokens.txt}.

Requirements:
    pip install onnx onnxscript scipy Cython
    python3 with torch (CPU ok), and a C compiler (gcc) for monotonic_align.
"""

import argparse
import glob
import os
import subprocess
import sys
import urllib.request
from typing import Any, Dict

BASE = "https://huggingface.co/facebook/mms-tts/resolve/main/models"
MMS_REPO = "https://huggingface.co/spaces/mms-meta/MMS"


def download(url: str, dest: str) -> None:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print(f"[skip] {dest}")
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print(f"[down] {url}")
    urllib.request.urlretrieve(url, dest)


def build_monotonic_align(mms: str) -> None:
    align = os.path.join(mms, "vits", "monotonic_align")
    import shutil
    # Skip rebuild when a compiled core.pyd already exists (e.g. prebuilt for this
    # platform). The MMS-space __init__.py imports `from .monotonic_align.core ...`
    # but only ships a single level; sherpa's recipe rewrites it to `.core`.
    if os.path.exists(os.path.join(align, "core.pyd")):
        print("[cython] core.pyd present — skipping monotonic_align rebuild")
        return
    print("[cython] build monotonic_align")
    subprocess.run([sys.executable, "setup.py", "build_ext", "--inplace"],
                   cwd=align, check=True)
    for so in glob.glob(os.path.join(align, "build", "lib*", "monotonic_align", "core*.pyd")):
        shutil.copy(so, os.path.join(align, os.path.basename(so)))
    # rewrite the relative import to the flat layout (sherpa recipe)
    ini = os.path.join(align, "__init__.py")
    with open(ini, encoding="utf-8") as f:
        text = f.read()
    text = text.replace("from .monotonic_align.core", "from .core")
    with open(ini, "w", encoding="utf-8") as f:
        f.write(text)


def json_load(p: str) -> Dict[str, Any]:
    import json
    with open(p, encoding="utf-8") as fh:
        return json.load(fh)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True,
                    help="facebook/mms-tts code, e.g. mar, tam, tel, kan, ory")
    ap.add_argument("--out", default="converted")
    args = ap.parse_args()
    lang = args.lang
    out = os.path.join(args.out, lang)
    os.makedirs(out, exist_ok=True)
    mms = os.path.join(args.out, "MMS")

    for f in ("G_100000.pth", "config.json", "vocab.txt"):
        download(f"{BASE}/{lang}/{f}", os.path.join(out, f))
    if not os.path.isdir(mms):
        print("[git] clone MMS space")
        subprocess.run(["git", "clone", "--depth", "1", MMS_REPO, mms], check=True)

    sys.path.insert(0, os.path.abspath(mms))
    sys.path.insert(0, os.path.abspath(os.path.join(mms, "vits")))
    build_monotonic_align(mms)

    import collections
    import onnx
    import torch
    from vits import utils
    from vits.models import SynthesizerTrn

    class OnnxModel(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model
        def forward(self, x, x_length,
                    noise_scale=torch.tensor([1], dtype=torch.float32),
                    length_scale=torch.tensor([1], dtype=torch.float32),
                    noise_scale_w=torch.tensor([1], dtype=torch.float32)):
            return self.model.infer(x=x, x_lengths=x_length,
                                    noise_scale=noise_scale,
                                    length_scale=length_scale,
                                    noise_scale_w=noise_scale_w)[0]

    def add_meta_data(filename: str, meta_data: Dict[str, Any]):
        m = onnx.load(filename)
        for k, v in meta_data.items():
            meta = m.metadata_props.add()
            meta.key = k
            meta.value = str(v)
        onnx.save(m, filename)

    def load_vocab():
        with open(os.path.join(out, "vocab.txt"), encoding="utf-8") as fh:
            return [x.replace("\n", "") for x in fh.readlines()]

    vocab = load_vocab()
    hps = utils.get_hparams_from_file(os.path.join(out, "config.json"))
    model = SynthesizerTrn(
        len(vocab),
        hps.data.filter_length // 2 + 1,
        hps.train.segment_size // hps.data.hop_length,
        **hps.model,
    )
    utils.load_checkpoint(os.path.join(out, "G_100000.pth"), model, None)
    model.eval()

    onnx_model = OnnxModel(model)
    x = torch.randint(low=0, high=len(vocab), size=(25,), dtype=torch.long).reshape(1, 25)
    x_lengths = torch.tensor([25], dtype=torch.long)
    noise_scale = torch.tensor([1], dtype=torch.float32)
    length_scale = torch.tensor([1], dtype=torch.float32)
    noise_scale_w = torch.tensor([1], dtype=torch.float32)

    onnx_path = os.path.join(out, "model.onnx")
    print("[onnx] export (takes a few minutes)")
    torch.onnx.export(onnx_model,
                      (x, x_lengths, noise_scale, length_scale, noise_scale_w),
                      onnx_path, opset_version=13,
                      dynamo=False,
                      input_names=["x", "x_length", "noise_scale", "length_scale", "noise_scale_w"],
                      output_names=["y"],
                      dynamic_axes={"x": {0: "n", 1: "t"}, "x_length": {0: "n"}})
    add_meta_data(onnx_path, {
        "model_type": "vits",
        "comment": f"converted from facebook/mms-tts /{lang}/",
        "url": "https://huggingface.co/facebook/mms-tts/tree/main",
        "language": lang,
        "add_blank": int(hps.data.add_blank),
        "n_speakers": int(hps.data.n_speakers),
        "sample_rate": hps.data.sampling_rate,
        "frontend": "characters",
    })

    all_upper_tokens = [i.upper() for i in vocab]
    duplicate = set(
        [item for item, count in collections.Counter(all_upper_tokens).items() if count > 1]
    )
    with open(os.path.join(out, "tokens.txt"), "w", encoding="utf-8") as fh:
        for idx, token in enumerate(vocab):
            fh.write(f"{token} {idx}\n")
            if (token.lower() != token.upper()
                    and len(token.upper()) == 1
                    and token.upper() not in duplicate):
                fh.write(f"{token.upper()} {idx}\n")
    print("DONE ->", onnx_path, os.path.getsize(onnx_path), "bytes")
    print("tokens ->", os.path.join(out, "tokens.txt"))


if __name__ == "__main__":
    main()
