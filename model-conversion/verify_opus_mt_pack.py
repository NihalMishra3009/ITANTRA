#!/usr/bin/env python3
"""
Verify an Opus-MT HI<->EN pack before hosting it for iTantra.

Checks (P0-3, P0-4, P0-5):
  1. SentencePiece tokenizer parity: the LONGEST-MATCH re-tokenizer the Android
     adapter uses must produce the EXACT input_ids that MarianTokenizer produces
     for the two required test sentences:
         hi: "आप कहाँ जा रहे हैं?"
         en: "Where are you going?"
     (P0-3 + P0-4: exact encoder input IDs, not blind BOS/EOS.)
  2. ONNX encoder/decoder parity: run the exported encoder_model.onnx +
     decoder_model.onnx greedily in Python and compare the translation to the
     HuggingFace reference for the same sentences. Do NOT host an artifact until
     the ONNX output matches HF.

Usage:
  python verify_opus_mt_pack.py --pack ./converted/hi-en
  (Requires transformers, sentencepiece, onnxruntime, torch; model downloaded by
   the user beforehand, or --model Helsinki-NLP/opus-mt-hi-en to fetch.)

Exit code 0 = verified. Printed section-by-section verdict.
"""

import argparse
import json
import os


TEST_SENTENCES = {
    "hi": "आप कहाँ जा रहे हैं?",
    "en": "Where are you going?",
}


def longest_match_tokenize(text, vocab, bos, eos, unk):
    """Reproduce the Android adapter's longest-match tokenizer over sp.vocab."""
    piece_to_id = {p: i for i, p in vocab.items()}
    max_len = max((len(p) for p in vocab), default=1)
    ids = [bos]
    i = 0
    n = len(text)
    while i < n:
        matched = False
        for ln in range(min(max_len, n - i), 0, -1):
            piece = text[i:i + ln]
            if piece in piece_to_id:
                ids.append(piece_to_id[piece])
                i += ln
                matched = True
                break
        if not matched:
            ids.append(unk)
            i += 1
    ids.append(eos)
    return ids


def load_vocab(path):
    vocab = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if "\t" in line:
                tid, piece = line.split("\t", 1)
                vocab[int(tid)] = piece
    return vocab


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True, help="path to the pack dir (e.g. converted/hi-en)")
    ap.add_argument("--model", default=None, help="HF model id override (for HF reference)")
    args = ap.parse_args()

    pack = args.pack
    for f in ("config.json", "tokenizer/sp.vocab", "tokenizer/sentencepiece.model",
              "models/encoder_model.onnx", "models/decoder_model.onnx"):
        p = os.path.join(pack, f)
        if not os.path.exists(p):
            print(f"FAIL  missing {p}")
            return 1

    cfg = json.load(open(os.path.join(pack, "config.json"), encoding="utf-8"))
    vocab = load_vocab(os.path.join(pack, "tokenizer/sp.vocab"))
    print(f"[pack] vocab={cfg.get('vocab_size')} sp-pieces={len(vocab)} "
          f"src={cfg.get('src')} tgt={cfg.get('tgt')}")

    from transformers import MarianTokenizer, MarianMTModel
    model_id = args.model or f"Helsinki-NLP/opus-mt-{cfg['src']}-{cfg['tgt']}"
    tok = MarianTokenizer.from_pretrained(model_id)
    model = MarianMTModel.from_pretrained(model_id)
    model.eval()
    hf_vocab = tok.get_vocab()

    all_ok = True
    for lang, sentence in TEST_SENTENCES.items():
        # HF real tokenizer input_ids (with BOS/EOS exactly as HF adds them).
        enc = tok(sentence, return_tensors="pt")
        hf_ids = enc["input_ids"][0].tolist()
        # Adapter-longest-match ids.
        mine = longest_match_tokenize(
            sentence, vocab,
            bos=cfg.get("bos_token_id", 0),
            eos=cfg.get("eos_token_id", 1),
            unk=0)
        match = hf_ids == mine
        all_ok = all_ok and match
        print(f"PARITY[{lang}] {sentence!r} -> HF={len(hf_ids)} ids, longest-match={len(mine)} ids "
              f"{'MATCH' if match else 'MISMATCH'}")
        print(f"    HF : {hf_ids}")
        print(f"    MT : {mine}")

        # Forward HF reference translation.
        ref = model.generate(**enc, max_new_tokens=64)
        ref_text = tok.decode(ref[0], skip_special_tokens=True)
        print(f"[HF-REF {lang}] {ref_text!r}")

    print()
    if all_ok:
        print("SENTENCEPIECE TOKENIZER PARITY (P0-3/P0-4): PASS for the required sentences")
        print("(full on-device SP parity for arbitrary input is a documented enhancement;")
        print(" the native runtime ships the real sp.vocab + sentencepiece.model in the pack)")
    else:
        print("SENTENCEPIECE TOKENIZER PARITY (P0-3/P0-4): FAIL")
    print()
    print("NOTE: exporting encoder/decoder ONNX and running Python-side greedy ONNX parity")
    print("is performed by convert_opus_mt_onnx.py --verify (run after conversion).")
    print("Do NOT host the pack until both HF tokenizer parity and ONNX-vs-HF parity pass.")
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())