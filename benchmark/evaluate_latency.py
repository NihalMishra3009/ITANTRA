#!/usr/bin/env python3
"""
iTantra latency & RTF measurement runner — SIH Phase 12 (honest version).

The previous version of this script contained hard-coded latency numbers and has
been removed. Real latency is produced ONLY by on-device instrumentation using
SystemClock.elapsedRealtimeNanos() in the Android app (see
docs/LATENCY_RESULTS.md, status NOT VERIFIED until a physical run exists).

This script does NOT fabricate numbers. When a physical/instrumented run exists,
point it at the exported JSON/CSV and it renders a summary; otherwise it exits
cleanly stating no measured data exists.
"""
import argparse
import csv
import json
import os
import sys


def load_and_render(path):
    if path.endswith(".csv"):
        rows = list(csv.DictReader(open(path, encoding="utf-8")))
    elif path.endswith(".json"):
        rows = json.load(open(path, encoding="utf-8"))
    else:
        raise ValueError("only .csv/.json supported")
    if not rows:
        print("no measured latency rows found")
        return 1
    keys = list(rows[0].keys())
    for r in rows:
        parts = {k: r.get(k, "") for k in keys}
        print("  ".join(f"{k}={v}" for k, v in parts.items()))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=None,
                    help="path to an exported latency CSV/JSON from device instrumentation")
    args = ap.parse_args()

    if args.data and os.path.exists(args.data):
        return load_and_render(args.data)

    print("NO measured latency data. Hard-coded results were removed from this repo.")
    print("To produce real numbers: run the app's instrumented pipeline on-device")
    print("(SystemClock.elapsedRealtimeNanos timelines exported to CSV/JSON), then")
    print("re-run: benchmark/evaluate_latency.py --data <export>")
    return 0


if __name__ == "__main__":
    sys.exit(main())