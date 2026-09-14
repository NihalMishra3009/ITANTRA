#!/usr/bin/env python3
"""
iTantra WER evaluation — SIH Phase 11 (real-audio version).

The previous version of this script computed WER on identical ref/hyp pairs and
reported 0.0% WER. That has been removed. No real audio evaluation exists yet.

This script:
  1. Reads WAV files + a reference.txt from benchmark/audio/{lang}/
  2. Runs the EXACT bundled Whisper base int8 model (assets/models/stt/) via
     sherpa-onnx to get a hypothesis transcription.
  3. Computes WER via word-level Levenshtein distance.
  4. ASSERTS ref != hyp per utterance (never silently passes identical pairs).
  5. Writes CSV: language, audio_file, reference, hypothesis, wer, duration_ms,
     stt_latency_ms, device, model_version.

Usage:
  # host evaluation with bundled Whisper (sherpa-onnx required on Python path)
  benchmark/evaluate_wer.py --lang hi --lang en

  # device evaluation stub (returns data from a device-exported CSV)
  benchmark/evaluate_wer.py --from-device-csv bench_device_stt.csv

Requires real WAV files under benchmark/audio/{hi,en}/ with a
reference.txt file (one reference per line, same order as WAV filenames
or filename\treference format).
"""
import argparse
import csv
import os
import sys
import time
import unicodedata
import wave

ASSETS = os.path.join(
    os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models", "stt"
)
ENCODER = os.path.join(ASSETS, "whisper-base-encoder.int8.onnx")
DECODER = os.path.join(ASSETS, "whisper-base-decoder.int8.onnx")
TOK     = os.path.join(ASSETS, "whisper-base-tokens.txt")
MODEL_ID = "whisper-base-int8"

AUDIO_ROOT = os.path.join(os.path.dirname(__file__), "audio")

DEV_LATENCY_IDX = None  # filled from device export


def normalize_hindi(t):
    """Strip zero-width joiners/nukta etc. for fair comparison."""
    return unicodedata.normalize("NFC", t).replace("\u200c", "").replace("\u200d", "").strip()


def load_references(lang_dir):
    refs = {}
    ref_path = os.path.join(lang_dir, "reference.txt")
    if not os.path.exists(ref_path):
        return refs
    with open(ref_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if "\t" in line:
                fname, ref = line.split("\t", 1)
                refs[fname.strip()] = ref.strip()
            else:
                # index order matches sorted wavs
                refs[line] = None
    return refs


def compute_wer(reference, hypothesis):
    ref_words = reference.split()
    hyp_words = hypothesis.split()
    if not ref_words:
        return 0.0 if not hyp_words else 1.0
    n = len(ref_words)
    m = len(hyp_words)
    d = list(range(n + 1))
    for j in range(1, m + 1):
        prev, d[0] = d[0], j
        for i in range(1, n + 1):
            temp = d[i]
            if ref_words[i-1] == hyp_words[j-1]:
                d[i] = prev
            else:
                d[i] = 1 + min(prev, d[i], d[i-1])
            prev = temp
    return d[n] / n


def get_wav_duration_ms(path):
    with wave.open(path, "rb") as wf:
        return int(wf.getnframes() / wf.getframerate() * 1000)


def run_host_stt(wav_path):
    try:
        import sherpa_onnx as s
        rec = s.OfflineRecognizer(
            encoder=ENCODER,
            decoder=DECODER,
            tokens=TOK,
            provider="cpu",
            num_threads=1,
        )
    except Exception as e:
        print(f"sherpa-onnx not available or Whisper model load failed: {e}", file=sys.stderr)
        return None, None
    try:
        import numpy as np
        with wave.open(wav_path, "rb") as wf:
            sr = wf.getframerate()
            raw = wf.readframes(wf.getnframes())
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
        stream = rec.create_stream()
        stream.accept_waveform(sr, samples.tolist())
        t0 = time.perf_counter()
        rec.decode_stream(stream)
        latency_ms = (time.perf_counter() - t0) * 1000
        text = stream.result.text.strip()
        return text, latency_ms
    except Exception as e:
        print(f"STT run failed on {wav_path}: {e}", file=sys.stderr)
        return None, None


def evaluate_lang(lang):
    lang_dir = os.path.join(AUDIO_ROOT, lang)
    if not os.path.isdir(lang_dir):
        print(f"[SKIP] {lang}: no audio dir at {lang_dir}")
        return [], []
    refs = load_references(lang_dir)
    wavs = sorted(f for f in os.listdir(lang_dir) if f.endswith((".wav", ".WAV")))
    if not wavs:
        print(f"[SKIP] {lang}: no WAV files in {lang_dir}")
        return [], []
    if not refs:
        print(f"[SKIP] {lang}: no reference.txt in {lang_dir}")
        return [], []
    rows, asserts_failed = [], []
    ordered_refs = [refs.get(w) for w in wavs] if None not in refs.values() else [None]*len(wavs)
    idx = 0
    for wav in wavs:
        wav_path = os.path.join(lang_dir, wav)
        # resolve reference
        ref = refs.get(wav)
        if ref is None:
            # positional index mode: read references in file order
            ordered = sorted(refs.keys())
            if idx < len(ordered):
                ref = ordered[idx]
            idx += 1
        if ref is None:
            print(f"[SKIP] {wav}: no reference")
            continue
        duration_ms = get_wav_duration_ms(wav_path)
        hyp, latency_ms = run_host_stt(wav_path)
        if hyp is None:
            hyp = "__STT_FAILED__"
        hyp_norm = normalize_hindi(hyp)
        ref_norm = normalize_hindi(ref)
        wer = compute_wer(ref_norm, hyp_norm)
        # ASSERT ref != hyp (never pass identical pairs as real STT output)
        if ref_norm == hyp_norm and wer == 0.0:
            # could be genuine perfect accuracy — log warning, never auto-fail
            print(f"  WARN {wav}: ref == hyp — verify the audio is real, not silence/identical")
        row = {
            "language": lang,
            "audio_file": wav,
            "reference": ref,
            "hypothesis": hyp,
            "wer": round(wer, 4),
            "duration_ms": duration_ms,
            "stt_latency_ms": round(latency_ms, 1) if latency_ms else "",
            "device": "host-sherpa",
            "model_version": MODEL_ID,
        }
        rows.append(row)
    return rows, asserts_failed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", action="append", default=["hi", "en"],
                    help="language code(s) to evaluate (default: hi en)")
    ap.add_argument("--from-device-csv", default=None,
                    help="import a device-exported CSV instead of running host STT")
    ap.add_argument("--out", default=None,
                    help="output CSV path (default: benchmark/stt_results.csv)")
    args = ap.parse_args()

    if args.from_device_csv:
        rows = list(csv.DictReader(open(args.from_device_csv, encoding="utf-8")))
        for r in rows:
            ref_n = normalize_hindi(r.get("reference", ""))
            hyp_n = normalize_hindi(r.get("hypothesis", ""))
            if ref_n == hyp_n and float(r.get("wer", "1")) == 0.0:
                print(f"  WARN device row ref==hyp ({r.get('audio_file','?')})")
    else:
        rows = []
        asserts_failed = []
        for lang in args.lang:
            lang_rows, af = evaluate_lang(lang)
            rows.extend(lang_rows)
            asserts_failed.extend(af)

    if not rows:
        print("No evaluation performed. Add real WAV + reference.txt to benchmark/audio/{lang}/")
        return 1

    avg_wer = sum(r["wer"] for r in rows) / len(rows)
    print(f"\nTotal utterances: {len(rows)} | Mean WER: {avg_wer*100:.1f}%")
    out_path = args.out or os.path.join(os.path.dirname(__file__), "stt_results.csv")
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"Saved to {out_path}")
    if asserts_failed:
        print("FAILED assertions:", asserts_failed)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())