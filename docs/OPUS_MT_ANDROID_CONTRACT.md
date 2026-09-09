# Opus-MT Android Contract

Verified against the real HuggingFace model configs (Helsinki-NLP opus-mt).

## Architecture: EN-pivot for all 10 languages

Every iTantra language has a real Opus-MT model with ENGLISH
(`opus-mt-{lang}-en` and `opus-mt-en-{lang}`). Any pair X→Y translates offline
through ENGLISH with exactly two hops:

```
Tamil → "வணக்கம்" → tamil→EN → EN→Marathi → Marathi text
```

- **Direct pairs** (real model): `en↔hi`, `en↔gu`, `en↔mr`, `en↔kn`,
  `en↔ml`, `en↔ta`, `en↔te`, `en↔or`, `en↔bn` → 18 directed model packs.
- **Pivoted pairs** (X↔Y, neither English): two hops through EN, each hop a
  real model. Never claimed as a single direct model.
- Same-language: bypass (no translation).

Pack layout (runtime must match)

HOST layout (converter `convert_opus_mt_onnx.py` output / hosted archive body,
top-level dir is the pair):

```
{hi-en}/
    manifest.json      (itantra-mt-pack-v1)
    models/
        encoder_model.onnx
        decoder_model.onnx
    config.json
    tokenizer/
        sentencepiece.model
        sp.vocab
```

DEVICE layout (installer flattens `models/*.onnx` to the pack root; that is what
the JNI adapter loads — `encoder_model.onnx` at root, not under models/):

```
models/translation/{src}-{tgt}/
    encoder_model.onnx
    decoder_model.onnx
    config.json
    manifest.json            (present when the archive carried one)
    tokenizer/
        sentencepiece.model
        sp.vocab
```

The device-contract required files are enforced by
`ModelStorageManager.translationRequiredFiles`. `manifest.json` is validated when
present (format marker `itantra-mt-pack-v1` + all contract files); legacy hosted
archives that predate the manifest still install via the contract-file check.

## Native result envelope (structured, no string sniffing)

- Success: `__MT_OK__:<translated text>`
- Failure: `__MT_ERR__:<code>|<human message>`, codes 101 empty-input, 102 no-ort,
  103 bad-pack, 104 no-env, 105 optlevel, 106 session-load, 107 no-mem,
  108 enc-run, 109 shape, 110 dec-run, 111 tokenize-fail, 150 runtime.

## Special token IDs (read from config.json by the native adapter)

| Field | hi-en | en-hi (verified at pack build) |
|-------|-------|-------|
| vocab_size | 61127 | 61950 |
| decoder_start_token_id | 61126 | 61949 |
| pad_token_id | 61126 | 61949 |
| eos_token_id | 0 | 0 |
| bos_token_id | 0 | 0 |
| d_model | 512 | 512 |

- `decoder_start_token_id` = 61126 (the pad id) — the decoder is seeded with pad.
  The adapter MUST use this value, NOT bos.
- eos = 0; generation stops when argmax id == eos (or pad).

## Tokenizer contract (Phase 2, verified)

- Marian uses a unigram SentencePiece model (`source.spm` for the encoder side,
  `target.spm` for the decoder side). The pack ships `tokenizer/sentencepiece.model`.
- The adapter uses the REAL vendored sentencepiece library
  (`third_party/sentencepiece`) → `EncodeAsIds(text)` / `DecodeIds(ids)`.
- HF `MarianTokenizer` does NOT add extra BOS/EOS around the SP ids for a single
  sample; the raw `EncodeAsIds` output is the exact encoder input.
- **Verified parity 6/6** for:
  - आप कहाँ जा रहे हैं?  → [71,875,135,84,15,29]
  - मुझे पानी चाहिए।      → [106,410,123,28]
  - यह एक परीक्षण संदेश है। → [34,23,5080,245,4,28]
  - Where are you going? → [4703,20323,987,5188,2848,16696,29]
  - I need water.        → [3227,769,40,22519,30]
  - This is a test message. → [9686,2781,449,14180,4930,10433,30]

## ONNX graph contract

| Graph | Inputs | Outputs |
|-------|--------|---------|
| encoder_model.onnx | `input_ids` int64 [1,S] | `last_hidden_state` fp32 [1,S,D] |
| decoder_model.onnx | `input_ids` int64 [1,T]; `encoder_hidden_states` fp32 [1,S,D] | `logits` fp32 [1,T,V] |

- S: tokenized source length; D: d_model; V: vocab_size.
- Greedy decoding: at each step take last-row argmax over logits; stop on eos/pad
  or maxSteps; the adapter keeps decoder_start as the first decoder input id.

## Native behavior notes

- Sessions (env + encoder + decoder) are cached per `{src}-{tgt}` dir and reused;
  released on model switch or `nnRelease()`.
- Errors are structured codes: 10x returned as `code:message`.
- Per-stage monotonic timings (steady_clock, µs): tokenizer / encoder / decoder / total.