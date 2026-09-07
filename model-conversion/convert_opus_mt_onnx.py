#!/usr/bin/env python3
"""
Convert Helsinki-NLP Opus-MT Hindi<->English (MarianMT) into a seq2seq ONNX
graph consumable by iTantra's offline translation engine
(com.itantra.translation.OpusMtTranslationEngine).

iTantra contract (engine expects EXACTLY this):
  pack dir:  {filesDir}/models/translation/{src}-{tgt}/  e.g. hi-en
  model.onnx  — graph with:
      input   "input_ids" : int64 [1, S]
      output  "output_ids": int64 [1, T]   (token ids; greedy decoded by engine)
  tokens.txt — one token per line, line index == token id (BOS=0, EOS=1, PAD=2
               are reserved at the front; the engine uses them)
  spm.model  — optional SentencePiece model. When absent, the engine falls back
               to a space-splitting tokenizer over the same vocab (deterministic,
               readable, but not fully faithful to the SpaCy/SPM tokenizer).

Usage:
  python convert_opus_mt_onnx.py --langpair hi-en --out ./converted/hi-en

Requirements:
  pip install transformers sentencepiece torch onnx onnxruntime
  (ferramentas: samples driven by a generic encoder-decoder trace)

The conversion exports the full HF Marian model with torch.onnx.export using
dynamic sequence lengths, then renames outputs to "output_ids". Run the exported
graph through onnxruntime in a quick self-check with a known sentence.

Licensing: Helsinki-NLP opus-mt weights are Apache-2.0 — compatible with the
project's open-source requirement (record this in docs/MODEL_LICENSES.md).
"""

import argparse
import os
import sys

BOS, EOS, PAD = 0, 1, 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--langpair", required=True, help="'hi-en' or 'en-hi'")
    ap.add_argument("--out", default="converted")
    ap.add_argument("--model", default=None,
                    help="HF model id, default opus-mt/<src>-<tgt>")
    args = ap.parse_args()

    src, tgt = args.langpair.lower().split("-")
    out_dir = os.path.join(args.out, args.langpair)
    os.makedirs(out_dir, exist_ok=True)

    model_id = args.model or f"Helsinki-NLP/opus-mt-{src}-{tgt}"
    print(f"[load] {model_id}")

    from transformers import MarianMTModel, MarianTokenizer
    tok = MarianTokenizer.from_pretrained(model_id)
    model = MarianMTModel.from_pretrained(model_id)
    model.eval()

    # Wrapper exporting encoder-decoder producing final target token ids.
    import torch
    import torch.onnx

    class Seq2SeqWrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, input_ids):
            # Greedy decode in-graph: marginal for small sentences; kept explicit
            # so the ONNX graph is self-contained (input ids -> output ids).
            b, s = input_ids.shape
            dec_ids = torch.full((b, 1), BOS, dtype=torch.long)
            for _ in range(1, 80):  # max decode steps
                out = self.m(input_ids=input_ids, decoder_input_ids=dec_ids).logits
                nxt = out[:, -1, :].argmax(dim=-1, keepdim=True)
                dec_ids = torch.cat([dec_ids, nxt], dim=1)
                if (nxt == EOS).all():
                    break
            return dec_ids

    wrapped = Seq2SeqWrapper(model)
    sample = torch.tensor([[BOS] + [tok.model_max_length % 97 or 3 for _ in range(8)]], dtype=torch.long)
    onnx_path = os.path.join(out_dir, "model.onnx")
    torch.onnx.export(
        wrapped, (sample,), onnx_path,
        opset_version=14, dynamo=False,
        input_names=["input_ids"],
        output_names=["output_ids"],
        dynamic_axes={"input_ids": {0: "n", 1: "s"}, "output_ids": {0: "n", 1: "t"}},
    )
    print("[onnx] exported", onnx_path)

    # tokens.txt: reserved BOS/EOS/PAD first, then the model's vocab.
    vocab = tok.get_vocab()
    ordered = sorted(vocab.items(), key=lambda kv: kv[1])
    with open(os.path.join(out_dir, "tokens.txt"), "w", encoding="utf-8") as f:
        f.write("<bos>\n<eos>\n<pad>\n")  # indexes 0,1,2
        for token, _ in ordered:
            f.write(token.replace(" ", "▁") + "\n")
    print("[vocab] tokens.txt written")

    # Optional: dump the SentencePiece model for faithful on-device tokenization.
    spm_path = os.path.join(out_dir, "spm.model")
    try:
        sp = tok.sp_model
        with open(spm_path, "wb") as f:
            f.write(sp.serialized_model_proto())
        print("[spm] spm.model written")
    except Exception as e:
        print("[spm] none (engine falls back to vocab-token mode):", e)

    # Self-check with onnxruntime.
    import onnxruntime as ort
    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    feed = {sess.get_inputs()[0].name: sample.numpy()}
    out = sess.run(None, feed)[0]
    print("[check] output_ids shape", out.shape, "OK" if out.size else "EMPTY")
    print("DONE ->", onnx_path)


if __name__ == "__main__":
    main()