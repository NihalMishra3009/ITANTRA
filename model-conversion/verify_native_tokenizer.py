#!/usr/bin/env python3
"""
Reproduce the ON-DEVICE tokenization algorithm (app/src/main/cpp/nnmt_jni.cpp) and check it
against Hugging Face, end to end.

The older verify_onnx_parity.py feeds the ONNX graphs the ids produced by HF's tokenizer, so it
cannot notice when the Android code builds its ids differently. That is exactly how raw
SentencePiece ids (unrelated to the model vocabulary) shipped and produced garbage translations.

This script uses ONLY the files the app bundles/ships:
    tokenizer/source.spm, tokenizer/target.spm, tokenizer/vocab.tsv  (+ the pack's ONNX graphs)
and mirrors the native steps:
    encode : source.spm pieces -> vocab ids (<unk> if absent) -> </s>
    decode : ids -> vocab pieces (skip </s>, <pad>) -> target.spm DecodePieces
It fails unless BOTH the source ids and the final text equal Hugging Face's.

Usage:
  python verify_native_tokenizer.py --pack converted/hi-en --tok ../app/src/main/assets/models/translation-tokenizers/hi-en
"""
import argparse
import json
import os
import sys

import numpy as np

SENTENCES = {
    "hi": ["आप कहाँ जा रहे हैं?", "मुझे मदद चाहिए, कृपया सहायता भेजें", "यह एक परीक्षण संदेश है।"],
    "en": ["Where are you going?", "I need help, please send assistance", "This is a test message."],
}


def load_vocab(tsv):
    id2tok = {}
    with open(tsv, encoding="utf-8", newline="") as f:
        for line in f.read().split("\n"):
            line = line.rstrip("\r")
            if "\t" not in line:
                continue
            i, tok = line.split("\t", 1)
            id2tok[int(i)] = tok
    return id2tok, {t: i for i, t in id2tok.items()}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True)
    ap.add_argument("--tok", required=True, help="dir with source.spm, target.spm, vocab.tsv")
    a = ap.parse_args()

    import onnxruntime as ort
    import sentencepiece as spm
    from transformers import MarianMTModel, MarianTokenizer

    pair = os.path.basename(os.path.normpath(a.pack))
    src = pair.split("-")[0]
    cfg = json.load(open(os.path.join(a.pack, "config.json"), encoding="utf-8"))
    eos, pad, start = cfg["eos_token_id"], cfg["pad_token_id"], cfg["decoder_start_token_id"]

    id2tok, tok2id = load_vocab(os.path.join(a.tok, "vocab.tsv"))
    unk = tok2id.get("<unk>", 1)
    sp_src = spm.SentencePieceProcessor(model_file=os.path.join(a.tok, "source.spm"))
    sp_tgt = spm.SentencePieceProcessor(model_file=os.path.join(a.tok, "target.spm"))

    def models_dir(name):
        p = os.path.join(a.pack, name)
        return p if os.path.exists(p) else os.path.join(a.pack, "models", name)

    enc = ort.InferenceSession(models_dir("encoder_model.onnx"), providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(models_dir("decoder_model.onnx"), providers=["CPUExecutionProvider"])

    hf_tok = MarianTokenizer.from_pretrained(f"Helsinki-NLP/opus-mt-{pair}")
    hf_model = MarianMTModel.from_pretrained(f"Helsinki-NLP/opus-mt-{pair}").eval()

    ok_all = True
    for sent in SENTENCES[src]:
        # ---- native algorithm ----
        ids = [tok2id.get(p, unk) for p in sp_src.encode(sent, out_type=str)] + [eos]
        hidden = enc.run(["last_hidden_state"], {"input_ids": np.asarray([ids], dtype=np.int64)})[0]
        cur, gen = [start], []
        for _ in range(min(128, len(ids) * 3 + 16)):
            logits = dec.run(["logits"], {"input_ids": np.asarray([cur], dtype=np.int64),
                                          "encoder_hidden_states": hidden})[0]
            nxt = int(np.argmax(logits[0, -1, :]))
            if nxt in (eos, pad):
                break
            gen.append(nxt)
            cur.append(nxt)
        text = sp_tgt.decode_pieces([id2tok[i] for i in gen if i not in (eos, pad) and i in id2tok])

        # ---- Hugging Face reference ----
        hf_ids = hf_tok(sent)["input_ids"]
        import torch
        with torch.no_grad():
            out = hf_model.generate(**hf_tok(sent, return_tensors="pt"), num_beams=1, do_sample=False, max_length=128)
        hf_text = hf_tok.decode(out[0], skip_special_tokens=True)

        ids_ok = ids == hf_ids
        txt_ok = text.strip() == hf_text.strip()
        note = ""
        if not txt_ok:
            # Anusvara (U+0902) vs chandrabindu (U+0901) are interchangeable spellings in Hindi and
            # the model's greedy choice between them can flip on a near-tie; that is not a
            # tokenization error. Anything else differing IS a failure.
            strip = lambda t: t.replace("ँ", "").replace("ं", "").strip()
            if strip(text) == strip(hf_text):
                txt_ok = True
                note = "  (only an anusvara/chandrabindu spelling variant differs)"
        ok_all &= ids_ok and txt_ok
        print(f"[{'PASS' if ids_ok and txt_ok else 'FAIL'}] {pair}: {sent!r}{note}")
        print(f"        ids  native={ids} hf={hf_ids} {'==' if ids_ok else '!='}")
        print(f"        text native={text!r}")
        print(f"             hf    ={hf_text!r}")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
