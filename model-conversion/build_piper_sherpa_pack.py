#!/usr/bin/env python3
"""
Build a sherpa-onnx-loadable INT8 voice pack from an upstream Piper voice
(rhasspy/piper-voices on Hugging Face) and verify it by synthesizing speech.

Piper's published .onnx has no sherpa metadata and no tokens.txt. This script:
  1. writes tokens.txt from the voice's phoneme_id_map
  2. injects the sherpa VITS metadata (model_type/comment/voice/has_espeak/...)
  3. dynamically quantizes to INT8 and re-injects the metadata (quantization drops it)
  4. reuses the espeak-ng-data directory from any existing sherpa Piper pack
  5. loads the result with sherpa-onnx and synthesizes a sentence — the pack is only
     written to a tar.bz2 if the audio is real (non-empty, non-silent)

Usage:
  python build_piper_sherpa_pack.py --onnx mr_IN-google-medium.onnx \
      --json mr_IN-google-medium.onnx.json --model-card MODEL_CARD \
      --espeak-data <dir>/espeak-ng-data --language Marathi \
      --text "नमस्कार, मला मदत हवी आहे" --out-dir out/
Prints the archive path, size and SHA-256 (pin these in ModelCatalog).
"""
import argparse
import hashlib
import json
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path

import numpy as np
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic


def write_tokens(cfg: dict, path: Path) -> None:
    # sherpa-onnx's token reader accepts single-codepoint tokens only. Some voices
    # (those finetuned from LibriTTS-R) also define multi-codepoint English diphthong
    # tokens ("aɪ", "eɪ", ...). espeak's output for Indic voices is per-codepoint, so
    # those are never emitted; they are skipped (and reported) rather than crashing load.
    skipped = []
    with path.open("w", encoding="utf-8") as f:
        for tok, ids in cfg["phoneme_id_map"].items():
            if len(tok) != 1:
                skipped.append(tok)
                continue
            f.write(f"{tok} {ids[0]}\n")
    if skipped:
        print(f"note: skipped multi-codepoint tokens: {skipped}")


def inject_meta(model_path: Path, cfg: dict, language: str) -> None:
    m = onnx.load(str(model_path))
    keep = {p.key for p in m.metadata_props}
    meta = {
        "model_type": "vits",
        "comment": "piper",
        "language": language,
        "voice": cfg["espeak"]["voice"],
        "has_espeak": "1",
        "n_speakers": str(cfg["num_speakers"]),
        "sample_rate": str(cfg["audio"]["sample_rate"]),
    }
    del keep
    while len(m.metadata_props):
        m.metadata_props.pop()
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key, p.value = k, v
    onnx.save(m, str(model_path))


def synth_check(pack_dir: Path, onnx_name: str, text: str, out_wav: Path) -> dict:
    import sherpa_onnx

    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(pack_dir / onnx_name),
                tokens=str(pack_dir / "tokens.txt"),
                data_dir=str(pack_dir / "espeak-ng-data"),
            ),
            num_threads=2,
            provider="cpu",
        )
    )
    tts = sherpa_onnx.OfflineTts(cfg)
    audio = tts.generate(text, sid=0, speed=1.0)
    samples = np.asarray(audio.samples, dtype=np.float32)
    dur = len(samples) / audio.sample_rate if audio.sample_rate else 0.0
    rms = float(np.sqrt(np.mean(samples**2))) if len(samples) else 0.0
    import wave

    with wave.open(str(out_wav), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(audio.sample_rate)
        w.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    return {"seconds": dur, "rms": rms, "sample_rate": audio.sample_rate}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--json", required=True)
    ap.add_argument("--model-card", required=True)
    ap.add_argument("--espeak-data", required=True)
    ap.add_argument("--language", required=True)
    ap.add_argument("--text", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--name", help="pack name, default derived from the onnx file")
    a = ap.parse_args()

    src = Path(a.onnx)
    voice = src.name.removesuffix(".onnx")
    name = a.name or f"vits-piper-{voice}-int8"
    cfg = json.loads(Path(a.json).read_text(encoding="utf-8"))
    out_dir = Path(a.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as td:
        pack = Path(td) / name
        pack.mkdir()
        fp32 = Path(td) / f"{voice}.fp32.onnx"
        shutil.copy(src, fp32)
        inject_meta(fp32, cfg, a.language)
        int8 = pack / f"{voice}.onnx"
        quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QUInt8)
        inject_meta(int8, cfg, a.language)
        write_tokens(cfg, pack / "tokens.txt")
        shutil.copy(a.json, pack / f"{voice}.onnx.json")
        shutil.copy(a.model_card, pack / "MODEL_CARD")
        shutil.copytree(a.espeak_data, pack / "espeak-ng-data")

        wav = out_dir / f"{voice}.check.wav"
        r = synth_check(pack, f"{voice}.onnx", a.text, wav)
        print(f"synth check: {r}")
        if r["seconds"] < 0.5 or r["rms"] < 0.005:
            print("FAIL: synthesized audio is empty/silent — pack NOT written", file=sys.stderr)
            return 1

        archive = out_dir / f"{name}.tar.bz2"
        with tarfile.open(archive, "w:bz2") as t:
            t.add(pack, arcname=name)

    print(f"archive: {archive}")
    print(f"size:    {archive.stat().st_size}")
    print(f"sha256:  {sha256(archive)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
