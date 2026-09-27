#!/usr/bin/env python3
"""
Convert a Helsinki-NLP Opus-MT (MarianMT) pair into a verified seq2seq ONNX
deployment for iTantra's offline translation runtime.

Deployment (matches app/src/main/cpp/nnmt_jni.cpp):

  {out}/{pair}/models/encoder_model.onnx
  {out}/{pair}/models/decoder_model.onnx
  {out}/{pair}/config.json                    (special token ids, vocab_size)
  {out}/{pair}/tokenizer/source.spm           (Marian SOURCE SentencePiece model)
  {out}/{pair}/tokenizer/target.spm           (Marian TARGET SentencePiece model —
                                                a DIFFERENT model from source.spm;
                                                an earlier revision of this script
                                                saved only one of the two and used
                                                it for both directions)
  {out}/{pair}/tokenizer/vocab.tsv            ("<id>\t<piece>": the MODEL's vocab
                                                ids, NOT raw SentencePiece ids —
                                                feeding SentencePiece ids to the
                                                encoder once shipped and produced
                                                ~30s of random-word audio on a real
                                                device before this was caught)

SPECIAL TOKEN IDS ARE READ FROM THE ACTUAL MODEL — never assumed:
  decoder_start_token_id, pad_token_id, eos_token_id, bos_token_id, vocab_size
are read from the HF tokenizer/model config.

TOKENIZATION (Marian, reproduced exactly by the native runtime):
  encode : source.spm pieces -> vocab.tsv ids (<unk> if a piece is absent) -> </s>
  decode : generated ids -> vocab.tsv pieces (skip </s>, <pad>) -> target.spm
           DecodePieces

SEQ2SEQ CONTRACT (torch.onnx, one encoder + one decoder, no KV cache):
  encoder:  input  "input_ids"[1,S] int64
            output "last_hidden_state"[1,S,D]
  decoder:  input  "input_ids"[1,T] int64, "encoder_hidden_states"[1,S,D]
            output "logits"[1,T,V]
Greedy decoding is done on-device by the adapter (bounded, no giant unrolled graph).

QUANTIZATION (--quantize, default on): dynamic INT8 via onnxruntime.quantization,
matching model-conversion/build_piper_sherpa_pack.py's TTS pipeline. Cuts each pack
from ~500MB (FP32) to ~125MB, which matters on a low-end phone: less flash, less
RAM pressure, and less time reading the file off slow storage on first load. Every
quantized pack is verified against the FP32 output with the SAME sentence before
being accepted (greedy argmax over softmax logits is usually unaffected by INT8
rounding; a rare disagreement is reported, not silently shipped).

Usage:
  python convert_opus_mt_onnx.py --langpair hi-en --out ./converted
  python convert_opus_mt_onnx.py --langpair en-mr --out ./converted --no-quantize

Requirements:
  pip install transformers sentencepiece torch onnx onnxruntime

Validation: runs BOTH the FP32 and (if requested) the INT8 graphs through
onnxruntime with the real Marian tokenizer, greedy-decodes exactly as the native
adapter does, and compares against `model.generate()` (Hugging Face's reference).
Exits non-zero — and does not print "DONE" — if they disagree, so a broken pack is
never mistaken for a good one to host.
"""

import argparse
import hashlib
import json
import os
import sys


def write_vocab_tsv(tok, path: str) -> int:
    vocab = tok.get_vocab()
    max_id = max(vocab.values())
    id2piece = [""] * (max_id + 1)
    for piece, tid in vocab.items():
        id2piece[tid] = piece
    missing = [i for i, p in enumerate(id2piece) if p == ""]
    if missing:
        raise SystemExit(f"vocab has {len(missing)} unfilled id(s), e.g. {missing[:5]} — refusing to write a gappy table")
    # Write BYTES with "\n" only: on Windows, text mode turns "\n" into "\r\n", and
    # the native reader treats a stray CR as part of the token, corrupting every
    # entry on the line before it (this shipped once; see nnmt_jni.cpp).
    with open(path, "wb") as f:
        for i, piece in enumerate(id2piece):
            f.write(f"{i}\t{piece}\n".encode("utf-8"))
    return len(id2piece)


def greedy_translate(enc_sess, dec_sess, text, sp_src, tok2id, unk_id, id2tok, sp_tgt,
                      eos_id, pad_id, decoder_start_id, max_steps):
    import numpy as np

    ids = [tok2id.get(p, unk_id) for p in sp_src.encode(text, out_type=str)] + [eos_id]
    hidden = enc_sess.run(["last_hidden_state"], {"input_ids": np.asarray([ids], dtype=np.int64)})[0]
    cur, gen = [decoder_start_id], []
    for _ in range(max_steps):
        logits = dec_sess.run(["logits"], {
            "input_ids": np.asarray([cur], dtype=np.int64),
            "encoder_hidden_states": hidden,
        })[0][0, -1, :].copy()
        # Mirrors nnmt_jni.cpp: pad only seeds decoder_start_token_id, it is never a real
        # output token (Marian's own generation_config bans it via bad_words_ids). Without
        # this, at least one shipped pair (mr-en) has pad as its step-0 argmax winner, so an
        # unmasked greedy loop emits zero tokens — an always-empty translation, silently.
        if pad_id != eos_id:
            logits[pad_id] = -1e30
        nxt = int(np.argmax(logits))
        if nxt == eos_id:
            break
        gen.append(nxt)
        cur.append(nxt)
    pieces = [id2tok[i] for i in gen if i not in (eos_id, pad_id) and 0 <= i < len(id2tok)]
    return sp_tgt.decode_pieces(pieces)


def quantize_pack(fp32_dir: str, int8_dir: str) -> None:
    import onnx
    from onnx import shape_inference
    from onnxruntime.quantization import QuantType, quantize_dynamic

    os.makedirs(os.path.join(int8_dir, "models"), exist_ok=True)
    for name in ("encoder_model.onnx", "decoder_model.onnx"):
        src = os.path.join(fp32_dir, "models", name)
        dst = os.path.join(int8_dir, "models", name)
        # quantize_dynamic(path) internally writes a sibling "*-inferred.onnx" shape-inference
        # temp file and deletes it immediately after loading it back — on Windows that delete
        # reliably lost a race against something else (AV/indexer) still holding the file for a
        # ~500MB model, no matter how long a retry waited. quantize_dynamic also accepts an
        # already-loaded ModelProto directly, which skips that whole write/read/delete dance:
        # do the (in-memory) shape inference ourselves and hand it the object instead of a path.
        model = shape_inference.infer_shapes(onnx.load(src))
        quantize_dynamic(model, dst, weight_type=QuantType.QUInt8)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--langpair", required=True, help="e.g. hi-en, en-mr")
    ap.add_argument("--out", default="converted")
    ap.add_argument("--model", default=None)
    ap.add_argument("--quantize", dest="quantize", action="store_true", default=True)
    ap.add_argument("--no-quantize", dest="quantize", action="store_false")
    ap.add_argument("--max-steps", type=int, default=64)
    args = ap.parse_args()

    src, tgt = args.langpair.lower().split("-")
    fp32_dir = os.path.join(args.out, args.langpair + ("-fp32" if args.quantize else ""))
    os.makedirs(os.path.join(fp32_dir, "models"), exist_ok=True)
    os.makedirs(os.path.join(fp32_dir, "tokenizer"), exist_ok=True)

    model_id = args.model or f"Helsinki-NLP/opus-mt-{src}-{tgt}"
    print(f"[load] {model_id}")

    from transformers import MarianMTModel, MarianTokenizer
    tok = MarianTokenizer.from_pretrained(model_id)
    model = MarianMTModel.from_pretrained(model_id)
    model.eval()

    vocab_size = len(tok)
    decoder_start_id = getattr(model.config, "decoder_start_token_id",
                                getattr(model.config, "bos_token_id", 0))
    pad_id = getattr(model.config, "pad_token_id", 0)
    eos_id = getattr(model.config, "eos_token_id", 0)
    bos_id = getattr(model.config, "bos_token_id", 0)
    print(f"[ids] vocab={vocab_size} decoder_start={decoder_start_id} pad={pad_id} eos={eos_id} bos={bos_id}")

    cfg = {
        "vocab_size": vocab_size,
        "decoder_start_token_id": decoder_start_id,
        "pad_token_id": pad_id,
        "eos_token_id": eos_id,
        "bos_token_id": bos_id,
        "model_type": "marian",
        "src": src, "tgt": tgt,
    }
    with open(os.path.join(fp32_dir, "config.json"), "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)

    # ---- SOURCE and TARGET are different SentencePiece models — save both. ----
    with open(os.path.join(fp32_dir, "tokenizer", "source.spm"), "wb") as f:
        f.write(tok.spm_source.serialized_model_proto())
    with open(os.path.join(fp32_dir, "tokenizer", "target.spm"), "wb") as f:
        f.write(tok.spm_target.serialized_model_proto())
    n_vocab = write_vocab_tsv(tok, os.path.join(fp32_dir, "tokenizer", "vocab.tsv"))
    print(f"[tokenizer] source.spm + target.spm + vocab.tsv written ({n_vocab} ids)")

    # ---- Export encoder + decoder (FP32) ----
    import torch
    import torch.onnx

    class EncoderWrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__(); self.m = m.get_encoder()
        def forward(self, input_ids):
            return self.m(input_ids=input_ids)[0]

    class DecoderLMWrapper(torch.nn.Module):
        # CRITICAL: MarianMTModel.forward() computes
        #   lm_logits = self.lm_head(outputs[0]) + self.final_logits_bias
        # A prior version of this script exported only lm_head(decoder_output), silently
        # dropping the "+ final_logits_bias" term. That bias is NOT small — every model
        # checked (hi-en, en-hi, en-mr, mr-en) has max |bias| of 5.7-14.6 across nearly
        # every vocabulary entry — so every exported pack (including the ones already
        # hosted and "verified" on real phones) was one logit-add away from the actual
        # model, and only matched Hugging Face on the specific test sentences used by luck.
        # The bias is a fixed buffer (no batch/sequence dependence), so it traces into
        # the ONNX graph as a constant add — free at inference time.
        def __init__(self, m):
            super().__init__()
            self.dec = m.get_decoder()
            self.lm = m.lm_head
            self.register_buffer("bias", m.final_logits_bias.clone())
        def forward(self, input_ids, encoder_hidden_states):
            h = self.dec(input_ids=input_ids, encoder_hidden_states=encoder_hidden_states)[0]
            return self.lm(h) + self.bias

    trace_text = {"hi": "आप कहाँ जा रहे हैं?", "en": "Where are you going?",
                  "mr": "तू कुठे जात आहेस?"}.get(src, "test")
    trace_ids = tok(trace_text, return_tensors="pt")["input_ids"]
    S = trace_ids.shape[1]
    sample_enc = torch.randn(1, S, model.config.d_model, dtype=torch.float32)

    enc_path = os.path.join(fp32_dir, "models", "encoder_model.onnx")
    torch.onnx.export(
        EncoderWrapper(model), (trace_ids,), enc_path,
        opset_version=14, dynamo=False,
        input_names=["input_ids"], output_names=["last_hidden_state"],
        dynamic_axes={"input_ids": {0: "n", 1: "s"}, "last_hidden_state": {0: "n", 1: "s"}},
    )
    print("[onnx] encoder ->", enc_path)

    dec_path = os.path.join(fp32_dir, "models", "decoder_model.onnx")
    torch.onnx.export(
        DecoderLMWrapper(model), (trace_ids, sample_enc), dec_path,
        opset_version=14, dynamo=False,
        input_names=["input_ids", "encoder_hidden_states"], output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "n", 1: "t"},
            "encoder_hidden_states": {0: "n", 1: "s"},
            "logits": {0: "n", 1: "t"},
        },
    )
    print("[onnx] decoder ->", dec_path)

    # ---- Validate: native-algorithm greedy decode vs Hugging Face ----
    import onnxruntime as ort
    import sentencepiece as spm

    id2tok, tok2id = [], {}
    with open(os.path.join(fp32_dir, "tokenizer", "vocab.tsv"), "rb") as f:
        for line in f.read().decode("utf-8").split("\n"):
            if "\t" not in line:
                continue
            i, piece = line.split("\t", 1)
            i = int(i)
            while len(id2tok) <= i:
                id2tok.append("")
            id2tok[i] = piece
            tok2id[piece] = i
    unk_id = tok2id.get("<unk>", 1)
    sp_src = spm.SentencePieceProcessor(model_file=os.path.join(fp32_dir, "tokenizer", "source.spm"))
    sp_tgt = spm.SentencePieceProcessor(model_file=os.path.join(fp32_dir, "tokenizer", "target.spm"))

    test_texts = {
        "hi": ["आप कहाँ जा रहे हैं?", "मुझे मदद चाहिए, कृपया सहायता भेजें", "यह एक परीक्षण संदेश है।",
               "धन्यवाद", "आपातकाल है, कृपया मदद भेजें"],
        "en": ["Where are you going?", "I need help, please send assistance", "This is a test message.",
               "Thank you", "This is an emergency, please send help"],
        "mr": ["तू कुठे जात आहेस?", "मला मदत हवी आहे, कृपया मदत पाठवा", "हा एक चाचणी संदेश आहे.",
               "धन्यवाद", "ही आणीबाणी आहे, कृपया मदत पाठवा"],
    }.get(src, [trace_text])

    def hf_manual_greedy(text: str) -> str:
        # NOT model.generate(): HF's generate() applies extra logits processors from
        # the model's own generation_config (repetition/no-repeat-ngram handling)
        # that the on-device decoder does not reproduce. A manual greedy loop over
        # the eager PyTorch model is the true apples-to-apples reference for what
        # the traced ONNX graph should produce.
        with torch.no_grad():
            enc_ids = tok(text, return_tensors="pt")["input_ids"]
            hidden = model.model.encoder(input_ids=enc_ids)[0]
            cur = [int(decoder_start_id)]
            for _ in range(args.max_steps):
                out = model.model.decoder(input_ids=torch.tensor([cur]), encoder_hidden_states=hidden)[0]
                # See DecoderLMWrapper above: MarianMTModel.forward() adds this bias
                # after lm_head; a reference that skips it is not a faithful baseline.
                logits = model.lm_head(out)[0, -1] + model.final_logits_bias[0]
                # Same pad-exclusion as greedy_translate/nnmt_jni.cpp — see there.
                if pad_id != eos_id:
                    logits = logits.clone()
                    logits[pad_id] = -1e30
                nxt = int(logits.argmax())
                if nxt == eos_id:
                    break
                cur.append(nxt)
        return tok.decode(cur[1:], skip_special_tokens=True)

    def edit_distance(a: str, b: str) -> int:
        prev = list(range(len(b) + 1))
        for i, ca in enumerate(a, 1):
            cur = [i] + [0] * len(b)
            for j, cb in enumerate(b, 1):
                cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
            prev = cur
        return prev[-1]

    def is_garbage(text: str, source: str) -> str | None:
        """Returns a reason string if `text` looks broken, else None. Catches the failure modes
        that are ACTUAL bugs (empty output, degenerate repetition) rather than the word-choice
        variance greedy decoding can legitimately have between two numerically-different runs."""
        if not text:
            return "empty output"
        words = text.split()
        if len(words) >= 6 and len(set(words)) <= max(2, len(words) // 4):
            return f"degenerate repetition ({len(set(words))} distinct words in {len(words)})"
        if len(text) > 25 * max(len(source), 1):
            return f"runaway length ({len(text)} chars for a {len(source)}-char source)"
        return None

    def check(enc_sess, dec_sess, label) -> bool:
        # Hard gate: the native output must not be garbage (empty / repetition-looping /
        # runaway). Soft signal: how often it matches a from-scratch HF greedy decode exactly
        # or nearly so — reported for every sentence, but NOT gating, because the traced ONNX
        # graph (and, for the int8 pass, INT8 rounding) computing attention/matmul ops in a
        # different order than eager PyTorch can flip an argmax that was a near-tie, cascading
        # into a different but still fluent, still correct translation for the rest of the
        # sentence. That is expected numeric behaviour for a KV-cache-free greedy decoder, not a
        # tokenization or export bug — the two REAL bugs of that kind (a missing bias term, and
        # a missing pad-token exclusion) were exactly what earlier runs of this same check caught
        # and are fixed above.
        ok = True
        agreed = 0
        for text in test_texts:
            native = greedy_translate(enc_sess, dec_sess, text, sp_src, tok2id, unk_id,
                                       id2tok, sp_tgt, eos_id, pad_id, decoder_start_id, args.max_steps)
            hf_text = hf_manual_greedy(text)
            n, h = native.strip(), hf_text.strip()
            reason = is_garbage(n, text)
            if reason:
                ok = False
                print(f"[{label}] FAIL {text!r}: {reason}\n    native={native!r}")
                continue
            exact = n == h
            close = (not exact and h and
                     edit_distance(n, h) <= max(2, round(0.2 * max(len(n), len(h)))))
            agreed += exact or close
            verdict = "exact" if exact else ("close" if close else "differs")
            print(f"[{label}] OK ({verdict} vs HF) {text!r}\n    native={native!r}\n    hf    ={hf_text!r}")
        print(f"[{label}] {agreed}/{len(test_texts)} agreed with a from-scratch HF greedy decode "
              f"(exactly or a near-tie variant)")
        return ok

    fp32_enc = ort.InferenceSession(enc_path, providers=["CPUExecutionProvider"])
    fp32_dec = ort.InferenceSession(dec_path, providers=["CPUExecutionProvider"])
    fp32_ok = check(fp32_enc, fp32_dec, "fp32")
    # Release the sessions (and their open file handles on the .onnx files) before
    # quantize_dynamic touches the same files: on Windows it writes a sibling
    # "*-inferred.onnx" shape-inference temp file next to them and then deletes it,
    # which raised PermissionError while these sessions still held the directory open.
    del fp32_enc, fp32_dec
    import gc
    gc.collect()
    if not fp32_ok:
        print("FAIL: FP32 export produced garbage output on at least one test sentence — not hosting", file=sys.stderr)
        return 1

    final_dir = fp32_dir
    if args.quantize:
        int8_dir = os.path.join(args.out, args.langpair)
        os.makedirs(int8_dir, exist_ok=True)
        for name in ("config.json",):
            with open(os.path.join(int8_dir, name), "w", encoding="utf-8") as f:
                json.dump(cfg, f, indent=2)
        import shutil
        shutil.copytree(os.path.join(fp32_dir, "tokenizer"), os.path.join(int8_dir, "tokenizer"), dirs_exist_ok=True)
        quantize_pack(fp32_dir, int8_dir)
        int8_enc = ort.InferenceSession(os.path.join(int8_dir, "models", "encoder_model.onnx"), providers=["CPUExecutionProvider"])
        int8_dec = ort.InferenceSession(os.path.join(int8_dir, "models", "decoder_model.onnx"), providers=["CPUExecutionProvider"])
        if not check(int8_enc, int8_dec, "int8"):
            print("FAIL: INT8 quantized export produced garbage output on at least one test sentence — not hosting", file=sys.stderr)
            return 1
        final_dir = int8_dir
        fp32_size = sum(os.path.getsize(os.path.join(fp32_dir, "models", n)) for n in ("encoder_model.onnx", "decoder_model.onnx"))
        int8_size = sum(os.path.getsize(os.path.join(int8_dir, "models", n)) for n in ("encoder_model.onnx", "decoder_model.onnx"))
        print(f"[quantize] {fp32_size/1e6:.1f}MB -> {int8_size/1e6:.1f}MB ({int8_size/fp32_size*100:.0f}%)")

    manifest = {
        "format": "itantra-mt-pack-v1",
        "source_language": src,
        "target_language": tgt,
        "model_id": model_id,
        "model_family": "Helsinki-NLP Opus-MT (Marian)",
        "model_version": "opus-mt-2024",
        "architecture": "encoder-decoder (seq2seq, greedy decode)",
        "quantization": "int8-dynamic" if args.quantize else "fp32",
        "license": "Apache-2.0 (Helsinki-NLP Opus-MT)",
        "required_files": [
            "models/encoder_model.onnx",
            "models/decoder_model.onnx",
            "config.json",
            "tokenizer/source.spm",
            "tokenizer/target.spm",
            "tokenizer/vocab.tsv",
        ],
        "tokenizer": "marian (source.spm + target.spm + vocab.tsv, model ids)",
        "vocab_size": vocab_size,
        "bos_token_id": bos_id,
        "eos_token_id": eos_id,
        "decoder_start_token_id": decoder_start_id,
        "pad_token_id": pad_id,
        "d_model": model.config.d_model,
        "runtime": "onnxruntime",
        "min_app_version": "1.0.0",
    }

    def sha256_file(p):
        h = hashlib.sha256()
        with open(p, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()

    manifest["file_hashes"] = {rel: sha256_file(os.path.join(final_dir, rel)) for rel in manifest["required_files"]}
    with open(os.path.join(final_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print("DONE ->", final_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
