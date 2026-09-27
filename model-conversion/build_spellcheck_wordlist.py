#!/usr/bin/env python3
"""
Build offline, per-language spelling-mistake word lists for iTantra's on-device
spell checker (English, Hindi, Marathi -- the three languages this repo has
reliable, open-licensed Hunspell dictionaries for).

Why not a flat word list scraped from a corpus: Hindi and Marathi are
morphologically rich (case suffixes, postpositions, plural/oblique forms
glued onto the stem). A flat list of "dictionary-form" roots would flag a
huge fraction of ordinary, correctly-spelled inflected words as typos. This
script instead expands every Hunspell root into ALL of its valid surface
forms using the dictionary's own affix (.aff) rules, so the shipped word
list actually recognizes real inflected words, not just citation forms.

Source dictionaries (GitHub `LibreOffice/dictionaries`, GPL-2.0), pinned to
a specific commit SHA for reproducibility (see PINNED_COMMIT below; resolved
via `GET https://api.github.com/repos/LibreOffice/dictionaries/commits/master`
on 2026-09-27, matching the sha256-pinning pattern used elsewhere in this
project -- see ModelCatalog.kt):

    hi_IN/hi_IN.dic + hi_IN/hi_IN.aff   (Hindi,    GPL-2.0, hi_IN/COPYING)
    mr_IN/mr_IN.dic + mr_IN/mr_IN.aff   (Marathi,  GPL-2.0, mr_IN/COPYING)
    en/en_US.dic    + en/en_US.aff      (English,  GPL-2.0, en/license.txt)

Expansion method (spylls, a pure-Python Hunspell reimplementation, MPL-2.0,
BUILD-TIME ONLY -- never bundled into the app):
  1. Parse the .dic file into (stem, flags) entries and the .aff file into
     PFX/SFX affix tables (spylls does this for us: `Dictionary.from_files`,
     `dictionary.dic.words`, `dictionary.aff.SFX` / `.PFX`).
  2. For each root, for each of its flags, apply every matching suffix
     and prefix rule from the .aff tables (condition-regex checked, per
     the standard Hunspell affix format) to generate candidate surface
     forms, including prefix+suffix combinations where both sides declare
     `crossproduct`.
  3. Validate EVERY candidate against `dictionary.lookup()` -- spylls' own
     Hunspell-accuracy lookup implementation -- and keep only candidates it
     accepts. This makes the result self-consistent with spylls' own notion
     of "valid word", rather than trusting our own regex application to be
     100% correct in every affix-stacking edge case.
  4. Sanity-check known inflected (non-root) forms are present, and refuse
     to write a list whose expansion ratio looks degenerate (i.e. affix
     application silently did nothing).

Output format -- Bloom filter, not a flat word list: the validated Marathi surface-form set
alone is ~2.9 million words. Stored as a HashSet<String> in the app that would be many tens of
megabytes of Kotlin object/String overhead in RAM, and its gzip-compressed asset came out to
8.7 MiB, over this project's ~5 MiB low-end-device budget. A Bloom filter is the right structure
here: querying it can only ever answer "definitely not present" (correct rejection -> flag as a
possible typo) or "probably present" (a tunable, small false-positive rate -> an occasional
missed typo, an acceptable failure mode) -- it can NEVER answer "not present" for a word that
actually IS in the set, so it structurally cannot cause the one unacceptable failure mode: a
real, correctly-spelled word getting flagged as a typo. At a 1% target false-positive rate this
needs ~1.2 bytes/word (~9.6 bits), so even Marathi's 2.9M words fit in ~3.3 MiB, and English/
Hindi shrink too. Applied uniformly to all three languages for one consistent runtime path in
com.itantra.stt.SpellCheckEngine.

Hashing: two independent 64-bit FNV hashes (FNV-1a and FNV-1 -- same constants, different
mix order, so they diverge) combined via Kirsch-Mitzenmacher double hashing
(g_i(x) = h1(x) + i*h2(x) mod m) to derive k bit positions per word. This exact scheme is
reimplemented bit-for-bit in Kotlin (com.itantra.stt.SpellCheckEngine) -- it has to match
exactly, or the filter built here would not agree with the filter queried on-device.

Output: one binary Bloom filter per language at
app/src/main/assets/spellcheck/{en,hi,mr}.bloom (format: see write_bloom_filter below).

Usage:
    pip install spylls==0.1.7
    python model-conversion/build_spellcheck_wordlist.py
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import math
import os
import struct
import sys
import urllib.request

# Resolved 2026-09-27 via GitHub API (GET /repos/LibreOffice/dictionaries/commits/master).
# Pinned so re-running this script later reproduces the exact same source dictionaries.
PINNED_COMMIT = "32b006a2c22a4ac7e8ed3f03346f7b3d85a970a4"
RAW_BASE = f"https://raw.githubusercontent.com/LibreOffice/dictionaries/{PINNED_COMMIT}"

# language code (as used by the rest of this app) -> (repo path stem, expected sha256 of .dic, .aff)
LANGUAGES = {
    "hi": {
        "repo_path": "hi_IN/hi_IN",
        "dic_sha256": "1e01f962a02638ef73e3f8de3c44bfd854f7059d31c9fa96cff0b73a2840f9d9",
        "aff_sha256": "3ab96772dc3d1cdbec4141798efb8b7a091b92c9acbeb5dfd3c4998a5c508302",
        # A handful of real INFLECTED (non-root) Hindi forms that must survive expansion.
        "must_recognize": ["लड़कियाँ", "किताबों", "लड़कों"],
    },
    "mr": {
        "repo_path": "mr_IN/mr_IN",
        "dic_sha256": "fe1112d88208b928dc2cca76f18bc3161b99c67b483778ed67fe5877461eb25a",
        "aff_sha256": "f870dde7dc0e50b15467b159c3c4c893c339936f1aa3642a4ab3303a13510ed9",
        "must_recognize": ["मुलांचा", "घरात", "पुस्तकांची"],
    },
    "en": {
        "repo_path": "en/en_US",
        "dic_sha256": "f0b1a234bd178bdd01875b2a392a9647f888b8fe879f79c52aae62c2759b3647",
        "aff_sha256": "e746c882dd6f303c2c46e7452804b9201115a6942cfeb15f18f8edf774d2e24e",
        "must_recognize": ["running", "children", "houses"],
    },
}

# Refuse to write a list whose expansion looks like the affix logic never ran.
MIN_EXPANSION_RATIO = 1.5

HERE = os.path.dirname(os.path.abspath(__file__))
DOWNLOAD_DIR = os.path.join(HERE, "converted", "spellcheck_src")
ASSETS_DIR = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "assets", "spellcheck"))


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: str) -> None:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print(f"  downloading {url}")
    with urllib.request.urlopen(url, timeout=60) as resp, open(dest, "wb") as out:
        out.write(resp.read())


def fetch_dict_files(lang: str, cfg: dict) -> str:
    """Downloads (or reuses a cached, hash-verified copy of) the .dic/.aff pair. Returns the
    local path stem (without extension) spylls' Dictionary.from_files expects."""
    local_stem = os.path.join(DOWNLOAD_DIR, lang)
    dic_path, aff_path = local_stem + ".dic", local_stem + ".aff"

    for path, ext, expected_sha in ((dic_path, "dic", cfg["dic_sha256"]), (aff_path, "aff", cfg["aff_sha256"])):
        if not os.path.exists(path):
            download(f"{RAW_BASE}/{cfg['repo_path']}.{ext}", path)
        actual_sha = sha256_of(path)
        if actual_sha != expected_sha:
            raise SystemExit(
                f"SHA-256 mismatch for {path}: expected {expected_sha}, got {actual_sha}. "
                f"The pinned commit {PINNED_COMMIT} should be immutable -- refusing to use "
                f"unverified dictionary data."
            )
    return local_stem


# Hunspell suffixes can chain: applying one suffix can leave the resulting word eligible for a
# further suffix, via the applied affix's OWN continuation flags (Affix.flags) -- not the
# original root's flags. This matters a lot for Marathi/Hindi: e.g. mr_IN's root "घर" (house)
# carries flag 'f' (oblique-stem marker, -> "घरा"), and suffix 'f' itself carries continuation
# flag 'd' (case clitic, -> "त" = "in"), so the real word "घरात" (in the house) only exists two
# suffix-applications deep. A single-level expansion misses this entirely.
#
# Chaining depth is capped at 2 suffix applications, and any flag in EXCLUDE_CHAIN_CONTINUATION
# is only ever applied as a WORD's own (level-0) flag, never followed as a second-level
# continuation flag. Without this, expansion combinatorially explodes: mr_IN's flag 'A' alone
# carries ~300 compound-postposition suffixes, and letting every oblique form additionally take
# every one of those blows the candidate set up to >20 million (mostly compound function-word
# attachments like "घराबद्दल" / "घरापर्यंत", not basic inflection) -- infeasible to validate at
# build time and unnecessary for catching ordinary misspellings. Ordinary case/number/oblique
# forms (the vast majority of real inflected words a spell-checker needs) are all reached well
# within depth 2 through the smaller flag tables.
MAX_SUFFIX_CHAIN_DEPTH = 2
EXCLUDE_CHAIN_CONTINUATION = {"A"}


def _expand_suffix_chain(d, stem: str, flags: set, max_depth: int = MAX_SUFFIX_CHAIN_DEPTH) -> set:
    forms = {stem}
    frontier = [(stem, flags, 0)]
    while frontier:
        cur_stem, cur_flags, depth = frontier.pop()
        if depth >= max_depth:
            continue
        for fl in cur_flags:
            if depth >= 1 and fl in EXCLUDE_CHAIN_CONTINUATION:
                continue
            for sfx in d.aff.SFX.get(fl, []):
                if sfx.cond_regexp.search(cur_stem):
                    new_stem = cur_stem[: -len(sfx.strip)] if sfx.strip else cur_stem
                    form = new_stem + sfx.add
                    if form not in forms:
                        forms.add(form)
                        frontier.append((form, sfx.flags, depth + 1))
    return forms


def expand_language(local_stem: str) -> tuple[set, int]:
    """Returns (validated surface-form set, root word count)."""
    from spylls.hunspell import Dictionary

    d = Dictionary.from_files(local_stem)
    roots = d.dic.words
    root_count = len(roots)

    candidates: set[str] = set()
    for word in roots:
        stem = word.stem
        flags = word.flags

        sfx_forms = _expand_suffix_chain(d, stem, flags)
        candidates |= sfx_forms

        pfx_hits = []
        for fl in flags:
            for pfx in d.aff.PFX.get(fl, []):
                if pfx.cond_regexp.search(stem):
                    new_stem = stem[len(pfx.strip):] if pfx.strip else stem
                    form = pfx.add + new_stem
                    candidates.add(form)
                    if pfx.crossproduct:
                        pfx_hits.append(pfx)

        # Cross-product: a suffixed form can also take a crossproduct-eligible prefix (standard
        # Hunspell PFX+SFX combination on the same stem).
        if pfx_hits:
            for sfx_form in sfx_forms:
                if sfx_form == stem:
                    continue
                for pfx in pfx_hits:
                    if pfx.cond_regexp.search(sfx_form):
                        new_stem = sfx_form[len(pfx.strip):] if pfx.strip else sfx_form
                        candidates.add(pfx.add + new_stem)

    # Ground-truth filter: keep only candidates spylls' own lookup() accepts. This is
    # self-consistent (spylls' lookup is the reference for "valid Hunspell form") and catches
    # any case where our own affix-condition application above was subtly wrong.
    validated = {w for w in candidates if d.lookup(w)}
    return validated, root_count


def _cache_path(lang: str) -> str:
    return os.path.join(DOWNLOAD_DIR, f"{lang}.validated_cache.txt.gz")


def expand_language_cached(lang: str, local_stem: str, force: bool) -> tuple[set, int]:
    """Wraps expand_language with an on-disk cache of the validated set, keyed only by
    language (the .dic/.aff are themselves pinned+hash-verified, so their content can't
    change under us). Marathi's lookup-validation pass alone takes several minutes; this lets
    downstream storage-format iteration (e.g. tuning the Bloom filter) skip re-running it."""
    cache_path = _cache_path(lang)
    if not force and os.path.exists(cache_path):
        with gzip.open(cache_path, "rt", encoding="utf-8") as f:
            root_count = int(f.readline().strip())
            validated = {line.rstrip("\n") for line in f if line.strip()}
        print(f"[{lang}] loaded {len(validated)} validated forms from cache ({cache_path})")
        return validated, root_count

    validated, root_count = expand_language(local_stem)
    with gzip.open(cache_path, "wt", encoding="utf-8") as f:
        f.write(f"{root_count}\n")
        for w in sorted(validated):
            f.write(w + "\n")
    return validated, root_count


def sanity_check(lang: str, cfg: dict, validated: set, root_count: int) -> None:
    missing = [w for w in cfg["must_recognize"] if w not in validated]
    if missing:
        raise SystemExit(
            f"[{lang}] sanity check FAILED: known inflected forms not recognized after "
            f"expansion: {missing}. Affix expansion is broken -- refusing to write output."
        )
    ratio = len(validated) / max(root_count, 1)
    if ratio < MIN_EXPANSION_RATIO:
        raise SystemExit(
            f"[{lang}] expansion looks degenerate: {root_count} roots -> {len(validated)} "
            f"surface forms (ratio {ratio:.2f}x < {MIN_EXPANSION_RATIO}x minimum). This usually "
            f"means the affix rules did not actually apply -- refusing to write a list that is "
            f"effectively just the flat root list (it would falsely flag valid inflected words)."
        )
    print(f"[{lang}] sanity OK: {root_count} roots -> {len(validated)} surface forms "
          f"({ratio:.2f}x), all {len(cfg['must_recognize'])} known inflected forms recognized")


# Target false-positive rate for "is this word in the dictionary?" queries. A false positive
# here means an actual typo occasionally slips through unflagged (acceptable -- flagging is
# informational only). A Bloom filter can never produce a false NEGATIVE, so a real word is
# never, ever flagged as a typo -- see the module docstring.
BLOOM_TARGET_FPR = 0.01

FNV64_OFFSET_BASIS = 0xCBF29CE484222325
FNV64_PRIME = 0x100000001B3
MASK64 = (1 << 64) - 1
BLOOM_MAGIC = b"ITBF1"


def _fnv1a64(data: bytes) -> int:
    h = FNV64_OFFSET_BASIS
    for b in data:
        h ^= b
        h = (h * FNV64_PRIME) & MASK64
    return h


def _fnv1_64(data: bytes) -> int:
    """Same constants as _fnv1a64 but multiply-then-xor (FNV-1, not FNV-1a) so it's an
    independent hash from _fnv1a64 -- required for Kirsch-Mitzenmacher double hashing below.
    Must stay bit-for-bit identical to the Kotlin implementation in SpellCheckEngine.kt."""
    h = FNV64_OFFSET_BASIS
    for b in data:
        h = (h * FNV64_PRIME) & MASK64
        h ^= b
    return h


def _bloom_bit_indices(word: str, k: int, m: int):
    data = word.encode("utf-8")
    h1 = _fnv1a64(data)
    h2 = _fnv1_64(data) | 1  # force odd so it's never 0 and never shares a factor with a power-of-two m
    for i in range(k):
        # MUST mask to 64 bits here: Python ints are arbitrary-precision, so h1 + i*h2 never
        # overflows on this side, but Kotlin's Long is a fixed-width 64-bit two's-complement
        # type that silently wraps on overflow (i*h2 alone can already exceed 2**64 once
        # i >= 1). Without this mask, the writer (here) and the Kotlin reader compute DIFFERENT
        # bit positions for every i >= 1 the moment h1 + i*h2 exceeds 2**64 -- which is most
        # words -- so a real, correctly-spelled word would fail lookup at runtime (a false
        # negative, the one failure mode a Bloom filter must never have). Masking here
        # reproduces the exact same wraparound Kotlin's Long arithmetic does implicitly.
        yield ((h1 + i * h2) & MASK64) % m


def bloom_params(n: int, target_fpr: float = BLOOM_TARGET_FPR) -> tuple[int, int]:
    n = max(n, 1)
    m = math.ceil(-(n * math.log(target_fpr)) / (math.log(2) ** 2))
    k = max(1, round((m / n) * math.log(2)))
    return m, k


def write_bloom_filter(lang: str, validated: set) -> str:
    os.makedirs(ASSETS_DIR, exist_ok=True)
    out_path = os.path.join(ASSETS_DIR, f"{lang}.bloom")
    n = len(validated)
    m, k = bloom_params(n)
    bits = bytearray((m + 7) // 8)
    for word in validated:
        for idx in _bloom_bit_indices(word, k, m):
            bits[idx // 8] |= 1 << (idx % 8)

    with open(out_path, "wb") as f:
        f.write(BLOOM_MAGIC)
        f.write(struct.pack(">B", k))
        f.write(struct.pack(">Q", m))
        f.write(struct.pack(">Q", n))
        f.write(bytes(bits))

    size = os.path.getsize(out_path)
    print(f"[{lang}] wrote {out_path} ({size / 1024:.1f} KiB, n={n}, m={m} bits, k={k} hashes, "
          f"target FPR={BLOOM_TARGET_FPR * 100:.1f}%)")
    if size > 5 * 1024 * 1024:
        print(f"[{lang}] WARNING: asset is {size / (1024*1024):.2f} MiB, over the ~5 MiB "
              f"low-end-device budget -- reconsider (e.g. raise BLOOM_TARGET_FPR) for this language.")

    # Self-check: every validated word must query as present in the filter we just wrote (a
    # Bloom filter can never false-negative; if this fails, the writer/reader math disagrees).
    sample = list(validated)[:5000] if n > 5000 else list(validated)
    for w in sample:
        bit_positions = list(_bloom_bit_indices(w, k, m))
        if not all(bits[p // 8] & (1 << (p % 8)) for p in bit_positions):
            raise SystemExit(f"[{lang}] Bloom filter self-check FAILED for '{w}' -- refusing output")
    return out_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--langs", nargs="+", default=list(LANGUAGES.keys()), choices=list(LANGUAGES.keys()))
    parser.add_argument("--force-reexpand", action="store_true",
                         help="Ignore the cached validated-word-set and rerun affix expansion + lookup validation.")
    args = parser.parse_args()

    print(f"Pinned LibreOffice/dictionaries commit: {PINNED_COMMIT}")
    for lang in args.langs:
        cfg = LANGUAGES[lang]
        print(f"\n=== {lang} ({cfg['repo_path']}) ===")
        local_stem = fetch_dict_files(lang, cfg)
        validated, root_count = expand_language_cached(lang, local_stem, args.force_reexpand)
        sanity_check(lang, cfg, validated, root_count)
        write_bloom_filter(lang, validated)

    print("\nDone.")


if __name__ == "__main__":
    main()
