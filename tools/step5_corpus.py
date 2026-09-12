#!/usr/bin/env python3
"""Harvest an English corpus to count collocations over.

Input : raw/kaikki-en.jsonl
Output: cache/corpus.txt  (one sentence per line)

Collocations are the part of vocabulary that a bilingual gloss cannot teach:
knowing that 提案する is `propose` does not tell you that it is `put forward a
proposal` and not `say a proposal`. Counting them needs a corpus.

Tatoeba alone is conversational, which is the wrong register for an exam built
on expository essays. Wiktionary's usage examples and quotations come from
books, journalism and reference prose, so the two together cover both.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
CACHE = os.path.join(HERE, "cache")

try:
    import orjson as _json

    def loads(s):
        return _json.loads(s)
except ImportError:  # pragma: no cover
    import json as _json

    def loads(s):
        return _json.loads(s)

WS = re.compile(r"\s+")
OK = re.compile(r"^[A-Za-z\"'‘“][^|\t]*$")
LATIN = re.compile(r"^[\x20-\x7e‘’“”—–]+$")


def usable(text):
    if not (20 <= len(text) <= 200):
        return False
    if not LATIN.match(text) or not OK.match(text):
        return False
    words = text.split()
    return 4 <= len(words) <= 32


def main():
    os.makedirs(CACHE, exist_ok=True)
    out_path = os.path.join(CACHE, "corpus.txt")
    seen = set()
    written = scanned = 0
    with open(os.path.join(RAW, "kaikki-en.jsonl"), "rb") as src, \
            open(out_path, "w", encoding="utf-8") as dst:
        for line in src:
            scanned += 1
            if b'"examples"' not in line:
                continue
            try:
                entry = loads(line)
            except Exception:
                continue
            if entry.get("lang_code") != "en":
                continue
            for sense in entry.get("senses") or []:
                for ex in sense.get("examples") or []:
                    text = WS.sub(" ", (ex.get("text") or "")).strip()
                    if not usable(text):
                        continue
                    key = text.lower()
                    if key in seen:
                        continue
                    seen.add(key)
                    dst.write(text + "\n")
                    written += 1
            if scanned % 300000 == 0:
                print(f"  {scanned:,} entries, {written:,} sentences", flush=True)

    print(f"wrote {written:,} sentences to {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
