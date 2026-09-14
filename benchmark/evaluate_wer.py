#!/usr/bin/env python3
"""
iTantra WER evaluation — SIH Phase 4/11 (real-audio version).

The previous version computed WER on identical ref/hyp pairs and reported 0.0%.
It is removed. This runner only reports real measurements.

Pipeline:
  1. Real WAV in benchmark/audio/{lang}/sampleXXX.wav
  2. Reference from a SIBLING sampleXXX.txt (one line, exactly one reference per
     audio file). A manifest.csv (audio_path, language, reference_text) is also
     accepted.
  3. Hypothesis from the EXACT bundled Whisper base int8 model via sherpa-onnx.
  4. Word-level Levenshtein WER with S/D/I/N and total-error counts.
  5. ref == hyp is reported (with a manual-verification warning), never rejected
     and never fabricated.

Usage:
  benchmark/evaluate_wer.py                    # hi + en
  benchmark/evaluate_wer.py --lang en          # English only (no duplication)
  benchmark/evaluate_wer.py --from-device-csv stt_export.csv

Deterministic pairing:
  benchmark/audio/hi/sample001.wav  <->  benchmark/audio/hi/sample001.txt
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
TOK = os.path.join(ASSETS, "whisper-base-tokens.txt")
MODEL_ID = "whisper-base-int8"

AUDIO_ROOT = os.path.join(os.path.dirname(__file__), "audio")

_FIELD_NAMES = [
    "language", "audio_file", "reference", "hypothesis", "wer",
    "substitutions", "deletions", "insertions", "ref_words", "total_errors",
    "duration_ms", "stt_latency_ms", "device", "model_version",
]


def normalize_hindi(t):
    return unicodedata.normalize("NFC", t).replace("\u200c", "").replace("\u200d", "").strip()


def load_references(lang_dir, manifest):
    """Exactly one reference per audio file. Sibling .txt wins; else manifest."""
    refs = {}
    # manifest.csv preferred when present (audio_path, language, reference_text)
    if manifest:
        with open(manifest, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                a = os.path.basename(row.get("audio_path", "")).strip()
                r = (row.get("reference_text", "") or "").strip()
                if a and r:
                    if a in refs:
                        raise SystemExit(f"manifest has duplicate audio entry: {a}")
                    refs[a] = r
    # sibling sampleXXX.txt overrides / fills gaps
    for name in sorted(os.listdir(lang_dir)):
        base, ext = os.path.splitext(name)
        if ext.lower() == ".wav":
            ref_txt = os.path.join(lang_dir, base + ".txt")
            if os.path.exists(ref_txt):
                with open(ref_txt, encoding="utf-8") as f:
                    lines = [ln.strip() for ln in f if ln.strip()]
                if len(lines) != 1:
                    raise SystemExit(f"{ref_txt} must contain exactly one reference line (got {len(lines)})")
                if name in refs:
                    raise SystemExit(f"duplicate reference source for {name}")
                refs[name] = lines[0]
    return refs


def compute_wer(reference, hypothesis):
    """Return (wer, substitutions, deletions, insertions)."""
    ref_words = reference.split()
    hyp_words = hypothesis.split()
    n, m = len(ref_words), len(hyp_words)
    if not ref_words:
        return (0.0 if not hyp_words else 1.0), 0, 0 if not hyp_words else m, 0 if not hyp_words else m
    # full DP matrix to recover counts
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if ref_words[i - 1] == hyp_words[j - 1]:
                d[i][j] = d[i - 1][j - 1]
            else:
                d[i][j] = 1 + min(d[i - 1][j], d[i][j - 1], d[i - 1][j - 1])
    i, j = n, m
    subs = dels = ins = 0
    while i > 0 or j > 0:
        if i > 0 and j > 0 and ref_words[i - 1] == hyp_words[j - 1]:
            i -= 1
            j -= 1
        elif i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + 1:
            subs += 1
            i -= 1
            j -= 1
        elif i > 0 and d[i][j] == d[i - 1][j] + 1:
            dels += 1
            i -= 1
        else:
            ins += 1
            j -= 1
    return d[n][m] / n, subs, dels, ins


def get_wav_duration_ms(path):
    with wave.open(path, "rb") as wf:
        return int(wf.getnframes() / wf.getframerate() * 1000)


def run_host_stt(wav_path):
    try:
        import sherpa_onnx as s
        rec = s.OfflineRecognizer(
            encoder=ENCODER, decoder=DECODER, tokens=TOK,
            provider="cpu", num_threads=1,
        )
    except Exception as e:
        print(f"sherpa-onnx unavailable or model load failed: {e}", file=sys.stderr)
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
        return stream.result.text.strip(), latency_ms
    except Exception as e:
        print(f"STT run failed on {wav_path}: {e}", file=sys.stderr)
        return None, None


def evaluate_lang(lang):
    lang_dir = os.path.join(AUDIO_ROOT, lang)
    if not os.path.isdir(lang_dir):
        print(f"[SKIP] {lang}: no audio dir at {lang_dir}")
        return []
    manifest = os.path.join(lang_dir, "manifest.csv")
    refs = load_references(lang_dir, manifest if os.path.exists(manifest) else None)
    wavs = sorted(f for f in os.listdir(lang_dir) if f.lower().endswith(".wav"))
    if not wavs:
        print(f"[SKIP] {lang}: no WAV files in {lang_dir}")
        return []
    if not refs:
        print(f"[SKIP] {lang}: no references (sibling .txt or manifest.csv) in {lang_dir}")
        return []
    missing = [w for w in wavs if w not in refs]
    if missing:
        raise SystemExit(f"[{lang}] audio files WITHOUT an exact reference: {missing[:5]}")
    extra = [r for r in refs if r not in wavs]
    if extra:
        raise SystemExit(f"[{lang}] references WITH no audio file: {extra[:5]}")

    rows = []
    for wav in wavs:
        wav_path = os.path.join(lang_dir, wav)
        ref = refs[wav]
        duration_ms = get_wav_duration_ms(wav_path)
        hyp, latency_ms = run_host_stt(wav_path)
        if hyp is None:
            hyp = "__STT_FAILED__"
        ref_n, hyp_n = normalize_hindi(ref), normalize_hindi(hyp)
        wer, subs, dels, ins = compute_wer(ref_n, hyp_n)
        if ref_n == hyp_n:
            print(f"  WARN {wav}: ref==hyp — verify the audio is real (manual check recommended)")
        rows.append({
            "language": lang, "audio_file": wav,
            "reference": ref, "hypothesis": hyp,
            "wer": round(wer, 4),
            "substitutions": subs, "deletions": dels, "insertions": ins,
            "ref_words": len(ref_n.split()), "total_errors": subs + dels + ins,
            "duration_ms": duration_ms,
            "stt_latency_ms": round(latency_ms, 1) if latency_ms else "",
            "device": "host-sherpa", "model_version": MODEL_ID,
        })
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", action="append", default=None,
                    help="language code(s) to evaluate; empty => hi, en")
    ap.add_argument("--from-device-csv", default=None,
                    help="import a device-exported CSV (already-transcribed hypotheses)")
    ap.add_argument("--out", default=None, help="output CSV (default benchmark/stt_results.csv)")
    args = ap.parse_args()
    langs = args.lang or ["hi", "en"]
    # never duplicate a language even if the user repeats a flag
    langs = list(dict.fromkeys(langs))

    if args.from_device_csv:
        rows = list(csv.DictReader(open(args.from_device_csv, encoding="utf-8")))
        for r in rows:
            ref_n = normalize_hindi(r.get("reference", ""))
            hyp_n = normalize_hindi(r.get("hypothesis", ""))
            if ref_n == hyp_n and float(r.get("wer", "1")) == 0.0:
                print(f"  WARN device row ref==hyp ({r.get('audio_file', '?')})")
    else:
        rows = []
        for lang in langs:
            rows.extend(evaluate_lang(lang))

    if not rows:
        print("No evaluation performed. Add real WAV + sibling .txt (or manifest.csv)")
        print("under benchmark/audio/{hi,en}/")
        return 1

    avg_wer = sum(r["wer"] for r in rows) / len(rows)
    ok = 0
    for r in rows:
        if r["wer"] is not None and r["wer"] != "":
            ok += 1
    print(f"\nTotal utterances: {len(rows)} | Mean WER: {avg_wer * 100:.1f}%")
    out_path = args.out or os.path.join(os.path.dirname(__file__), "stt_results.csv")
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=_FIELD_NAMES)
        writer.writeheader()
        for r in rows:
            writer.writerow({k: r.get(k, "") for k in _FIELD_NAMES})
    print(f"Saved to {out_path}")
    # "not measured" is a truthful answer; a failed STT is not a silent 0.0
    for r in rows:
        if r["hypothesis"] == "__STT_FAILED__":
            print(f"  FAIL {r['audio_file']}: STT did not produce a hypothesis (not counted as 0.0)")
    return 0


if __name__ == "__main__":
    sys.exit(main())