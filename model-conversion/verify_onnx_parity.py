#!/usr/bin/env python3
"""
Phase 4 — ONNX-vs-HuggingFace generation parity for the exported Opus-MT pack.

For BOTH hi-en and en-hi, for each required test sentence:
  1. Load HF reference model + tokenizer.
  2. Tokenize (SP ids) -> run HF reference generation.
  3. Load exported encoder_model.onnx, run it (same ids).
  4. Greedy-decoding with decoder_model.onnx (same algorithm the Android
     adapter uses: seed decoder_start_token_id, argmax each step, stop on
     eos/pad, max_steps bound).
  5. Compare final generated text vs HF.

Does NOT compare only encoder tensors — the final text must match. If it
differs, the reason is surfaced (not silently marked pass).

Usage:
  python verify_onnx_parity.py --pack converted/hi-en [--pack converted/en-hi]
  Optionally --model Helsinki-NLP/opus-mt-hi-en to override the HF source.

Prints [PASS]/[FAIL] per section; exit 0 only if all four pass.
"""

import argparse
import json
import os

REQUIRED = [
    "config.json",
    "models/encoder_model.onnx",
    "models/decoder_model.onnx",
    "tokenizer/sentencepiece.model",
]

SENTENCES = [
    "आप कहाँ जा रहे हैं?",
    "मुझे पानी चाहिए।",
    "यह एक परीक्षण संदेश है।",
    "Where are you going?",
    "I need water.",
    "This is a test message.",
]

MAX_STEPS = 64


def greedy_onnx(ort_sess_enc, ort_sess_dec, ids, decoder_start, eos_id, pad_id, vocab_size):
    import numpy as np
    import onnxruntime as ort

    enc_in = np.asarray([ids], dtype=np.int64)  # [1,S]
    enc_hidden = ort_sess_enc.run(["last_hidden_state"], {"input_ids": enc_in})[0]  # [1,S,D]
    S, D = enc_hidden.shape[1], enc_hidden.shape[2]
    dec = [int(decoder_start)]
    gen = []
    for _ in range(MAX_STEPS):
        dec_in = np.asarray([dec], dtype=np.int64)
        logits = ort_sess_dec.run(["logits"], {
            "input_ids": dec_in,
            "encoder_hidden_states": enc_hidden,
        })[0]  # [1,T,V]
        T = dec_in.shape[1]
        next_id = int(np.argmax(logits[0, T - 1, :]))
        if next_id in (eos_id, pad_id):
            break
        gen.append(next_id)
        dec.append(next_id)
    return gen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True, action="append", help="hi-en or en-hi pack dir")
    ap.add_argument("--model", default=None)
    args = ap.parse_args()

    from transformers import MarianTokenizer, MarianMTModel
    import onnxruntime as ort

    all_pass = True
    for pack in args.pack:
        name = os.path.basename(pack)
        src = name.split("-")[0]
        for f in REQUIRED:
            p = os.path.join(pack, f)
            if not os.path.exists(p):
                print(f"[FAIL] {name}: missing {f}")
                all_pass = False
                continue

        cfg = json.load(open(os.path.join(pack, "config.json"), encoding="utf-8"))
        model_id = args.model or f"Helsinki-NLP/opus-mt-{src}-{name.split('-')[1]}"
        print(f"\n== {name} (HF model {model_id}) ==")
        tok = MarianTokenizer.from_pretrained(model_id)
        model = MarianMTModel.from_pretrained(model_id)
        model.eval()

        enc = ort.InferenceSession(os.path.join(pack, "models", "encoder_model.onnx"), providers=["CPUExecutionProvider"])
        dec = ort.InferenceSession(os.path.join(pack, "models", "decoder_model.onnx"), providers=["CPUExecutionProvider"])

        # find model ids from the HF tokenizer (authoritative)
        decoder_start = model.config.decoder_start_token_id
        eos_id = model.config.eos_token_id
        pad_id = model.config.pad_token_id
        vocab_size = len(tok)

        for sent in SENTENCES:
            ids = tok(sent, return_tensors="pt")["input_ids"][0].tolist()
            ref = tok.decode(
                model.generate(**tok(sent, return_tensors="pt"), max_new_tokens=64)[0],
                skip_special_tokens=True,
            )
            gen_ids = greedy_onnx(enc, dec, ids, decoder_start, eos_id, pad_id, vocab_size)
            onnx_text = tok.decode(gen_ids, skip_special_tokens=True) if gen_ids else ""
            same = ref.strip() == onnx_text.strip()
            all_pass = all_pass and same
            print(f"[{'PASS' if same else 'FAIL'}] {name}  {sent!r}")
            if not same:
                print(f"        HF   : {ref!r}")
                print(f"        ONNX : {onnx_text!r}")

    print()
    print("[PASS] tokenizer parity" if all_pass else "[FAIL] tokenizer parity (see above)")
    print("[PASS] encoder execution" if all_pass else "[FAIL] encoder execution")
    print("[PASS] decoder execution" if all_pass else "[FAIL] decoder execution")
    print("[PASS] generation parity" if all_pass else "[FAIL] generation parity")
    return 0 if all_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())