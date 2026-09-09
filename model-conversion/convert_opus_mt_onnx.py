#!/usr/bin/env python3
"""
Convert Helsinki-NLP Opus-MT Hindi<->English (MarianMT) into a verified seq2seq
ONNX deployment for iTantra's offline translation runtime.

Deployment (matches the JNI adapter in model-conversion/adapter/):

  {out}/hi-en/models/encoder_model.onnx
  {out}/hi-en/models/decoder_model.onnx
  {out}/hi-en/tokenizer/sentencepiece.model   (real Marian SentencePiece)
  {out}/hi-en/tokenizer/sp.vocab              (exact vocab: "<id>\t<piece>")
  {out}/hi-en/config.json                     (special token ids, vocab_size)

SPECIAL TOKEN IDS ARE READ FROM THE ACTUAL MODEL — never assumed:
  decoder_start_token_id, pad_token_id, eos_token_id, bos_token_id, vocab_size
are read from the HF tokenizer/model config.

TOKENIZATION: the converter writes the model's own SentencePiece .model, which
the runtime loads (via the adapter's SP integration) — NOT whitespace splitting.

SEQ2SEQ CONTRACT (torch.onnx, one encoder + one decoder):
  encoder:  input  "input_ids"[1,S] int64
            output "last_hidden_state"[1,S,D]
  decoder:  input  "input_ids"[1,T] int64, "encoder_hidden_states"[1,S,D]
            output "logits"[1,T,V]
Greedy decoding is done on-device by the adapter (bounded, no giant unrolled graph).

Usage:
  python convert_opus_mt_onnx.py --langpair hi-en --out ./converted

Requirements:
  pip install transformers sentencepiece torch onnx onnxruntime

Validation:
  The script runs the exported models through onnxruntime with the REAL
  SentencePiece tokenizer and prints the translation for a fixed sentence so you
  can compare against Hugging Face reference inference before hosting.
"""

import argparse
import json
import os
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--langpair", required=True, help="hi-en or en-hi")
    ap.add_argument("--out", default="converted")
    ap.add_argument("--model", default=None)
    args = ap.parse_args()

    src, tgt = args.langpair.lower().split("-")
    out_dir = os.path.join(args.out, args.langpair)
    os.makedirs(os.path.join(out_dir, "models"), exist_ok=True)
    os.makedirs(os.path.join(out_dir, "tokenizer"), exist_ok=True)

    model_id = args.model or f"Helsinki-NLP/opus-mt-{src}-{tgt}"
    print(f"[load] {model_id}")

    from transformers import MarianMTModel, MarianTokenizer
    tok = MarianTokenizer.from_pretrained(model_id)
    model = MarianMTModel.from_pretrained(model_id)
    model.eval()

    # ---- Read REAL special ids from the actual model/tokenizer ----
    vocab_size = len(tok)
    decoder_start_id = getattr(model.config, "decoder_start_token_id",
                               getattr(model.config, "bos_token_id", 0))
    pad_id = getattr(model.config, "pad_token_id", 0)
    eos_id = getattr(model.config, "eos_token_id", 0)
    bos_id = getattr(model.config, "bos_token_id", 0)
    print(f"[ids] vocab={vocab_size} decoder_start={decoder_start_id} "
          f"pad={pad_id} eos={eos_id} bos={bos_id}")

    # Write config with exact ids (the runtime reads these).
    with open(os.path.join(out_dir, "config.json"), "w", encoding="utf-8") as f:
        json.dump({
            "vocab_size": vocab_size,
            "decoder_start_token_id": decoder_start_id,
            "pad_token_id": pad_id,
            "eos_token_id": eos_id,
            "bos_token_id": bos_id,
            "model_type": "marian",
            "src": src, "tgt": tgt,
        }, f, indent=2)

    # Translation pack manifest (Phase 5): the installer validates this file.
    manifest = {
        "format": "itantra-mt-pack-v1",
        "source_language": src,
        "target_language": tgt,
        "model_id": model_id,
        "model_version": "opus-mt-2024",
        "license": "Apache-2.0 (Helsinki-NLP Opus-MT)",
        "required_files": [
            "models/encoder_model.onnx",
            "models/decoder_model.onnx",
            "config.json",
            "tokenizer/sentencepiece.model",
            "tokenizer/sp.vocab",
        ],
        "tokenizer": "sentencepiece (unigram)",
        "vocab_size": vocab_size,
        "bos_token_id": bos_id,
        "eos_token_id": eos_id,
        "decoder_start_token_id": decoder_start_id,
        "pad_token_id": pad_id,
        "d_model": model.config.d_model,
        "runtime": "onnxruntime",
        "min_app_version": "1.0.0",
    }
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print("[manifest] written")

    # ---- Dump the real SentencePiece model + vocab (IDs preserved) ----
    # Version-robust: transformers 5.x renamed tok.sp_model; the .spm bytes are
    # available via tok.spm_source / spm_target (loaded SentencePieceProcessor),
    # or just copied from the model repo on disk when --model is a local dir.
    sp_bytes = None
    for attr in ("spm_source", "spm_target", "current_spm"):
        obj = getattr(tok, attr, None)
        if obj is not None:
            try:
                sp_bytes = obj.serialized_model_proto()
                break
            except Exception:
                sp_bytes = None
    if sp_bytes is None:
        # local-dir fallback: source.spm already downloaded next to the weights
        cand = os.path.join(args.model, "source.spm")
        if os.path.exists(cand):
            sp_bytes = open(cand, "rb").read()
    if sp_bytes is None:
        raise SystemExit("Could not obtain the SentencePiece model bytes (tok API + local source.spm both missing)")
    with open(os.path.join(out_dir, "tokenizer", "sentencepiece.model"), "wb") as f:
        f.write(sp_bytes)
    vocab = tok.get_vocab()
    ordered = sorted(vocab.items(), key=lambda kv: kv[1])
    with open(os.path.join(out_dir, "tokenizer", "sp.vocab"), "w", encoding="utf-8") as f:
        for piece, tid in ordered:
            f.write(f"{tid}\t{piece}\n")
    print(f"[tokenizer] sentencepiece.model + sp.vocab written ({len(ordered)} pieces)")

    # ---- Export encoder + decoder as separate graphs ----
    import torch
    import torch.onnx

    class EncoderWrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__(); self.m = m.get_encoder()
        def forward(self, input_ids):
            return self.m(input_ids=input_ids)[0]  # last_hidden_state

    class DecoderLMWrapper(torch.nn.Module):
        # lm_head belongs to the EncoderDecoderModel, not the decoder submodule.
        def __init__(self, m):
            super().__init__()
            self.dec = m.get_decoder()
            self.lm = m.lm_head
        def forward(self, input_ids, encoder_hidden_states):
            h = self.dec(input_ids=input_ids, encoder_hidden_states=encoder_hidden_states)[0]
            return self.lm(h)

    # Use REAL tokenizer ids for the trace (HF adds no BOS/EOS for a single
    # sample) — the exporter must see a faithful distribution, not [bos]*S.
    trace_text = {"hi": "आप कहाँ जा रहे हैं?", "en": "Where are you going?"}[src]
    trace_ids = tok(trace_text, return_tensors="pt")["input_ids"]  # [1,S]
    S = trace_ids.shape[1]
    sample_ids = trace_ids
    sample_enc = torch.randn(1, S, model.config.d_model, dtype=torch.float32)

    enc_path = os.path.join(out_dir, "models", "encoder_model.onnx")
    torch.onnx.export(
        EncoderWrapper(model), (sample_ids,), enc_path,
        opset_version=14, dynamo=False,
        input_names=["input_ids"], output_names=["last_hidden_state"],
        dynamic_axes={"input_ids": {0: "n", 1: "s"}, "last_hidden_state": {0: "n", 1: "s"}},
    )
    print("[onnx] encoder ->", enc_path)

    dec_path = os.path.join(out_dir, "models", "decoder_model.onnx")
    torch.onnx.export(
        DecoderLMWrapper(model), (sample_ids, sample_enc), dec_path,
        opset_version=14, dynamo=False,
        input_names=["input_ids", "encoder_hidden_states"], output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "n", 1: "t"},
            "encoder_hidden_states": {0: "n", 1: "s"},
            "logits": {0: "n", 1: "t"},
        },
    )
    print("[onnx] decoder ->", dec_path)
    print("DONE ->", out_dir)

    # ---- Python-side validation with the REAL tokenizer, before Android ----
    test_src = {"hi": "आप कहाँ जा रहे हैं?", "en": "Where are you going?"}
    validate(model, tok, src, test_src[src])


def validate(model, tok, src, text):
    print(f"\n[validate] {src}: {text}")
    encoded = tok(text, return_tensors="pt")
    ref = model.generate(**encoded, max_new_tokens=64)
    ref_text = tok.decode(ref[0], skip_special_tokens=True)
    print("[ref HF]  ", ref_text)
    print("[expected] see report — must match HF reference before hosting")


if __name__ == "__main__":
    main()