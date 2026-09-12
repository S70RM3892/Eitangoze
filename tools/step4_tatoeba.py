#!/usr/bin/env python3
"""English/Japanese sentence pairs from Tatoeba.

Input : raw/eng_sentences.tsv.bz2, raw/jpn_sentences.tsv.bz2,
        raw/jpn-eng_links.tsv.bz2
Output: cache/sentences.tsv   (id \t english \t japanese)

These pairs do three jobs in the app: they are the cloze sentences, they are the
Japanese side of the 和文英訳 drills, and they are the corpus the collocation
counts are computed over. Wiktionary's own examples are better tuned to a
single sense but have no Japanese, so the two sets are kept separate and used
for different card types.
"""
import bz2
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
CACHE = os.path.join(HERE, "cache")

# Sentences a learner can actually hold in their head, and that a cloze can be
# cut out of without the rest becoming ambiguous.
MIN_WORDS, MAX_WORDS = 4, 22
OK_EN = re.compile(r"^[A-Z\"'‘“][A-Za-z0-9 ,.;:'\"!?\-()%$&/‘’“”]*[.!?\"'’”]$")
HAS_LATIN = re.compile(r"[A-Za-z]")
JA_CHARS = re.compile(r"[぀-ヿ一-鿿]")


def read_sentences(path, lang_check):
    out = {}
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            sid, lang, text = parts[0], parts[1], parts[2]
            if not lang_check(text):
                continue
            out[sid] = text
    return out


def usable_en(text):
    if not (20 <= len(text) <= 130):
        return False
    words = text.split()
    if not (MIN_WORDS <= len(words) <= MAX_WORDS):
        return False
    if not OK_EN.match(text):
        return False
    # Tatoeba carries a fair number of proper-noun-heavy sentences; they teach
    # nothing about vocabulary and make poor cloze material.
    caps = sum(1 for w in words[1:] if w[:1].isupper())
    return caps <= 2


def usable_ja(text):
    if not (4 <= len(text) <= 90):
        return False
    if not JA_CHARS.search(text):
        return False
    # Reject rows that are really English with a stray kana.
    return len(HAS_LATIN.findall(text)) <= len(text) // 3


def main():
    os.makedirs(CACHE, exist_ok=True)
    print("reading English sentences")
    eng = read_sentences(os.path.join(RAW, "eng_sentences.tsv.bz2"), usable_en)
    print(f"  {len(eng):,} usable English sentences")
    print("reading Japanese sentences")
    jpn = read_sentences(os.path.join(RAW, "jpn_sentences.tsv.bz2"), usable_ja)
    print(f"  {len(jpn):,} usable Japanese sentences")

    print("joining")
    seen_en = {}
    pairs = []
    with bz2.open(os.path.join(RAW, "jpn-eng_links.tsv.bz2"), "rt",
                  encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            a, b = parts[0], parts[1]
            ja_text, en_text = jpn.get(a), eng.get(b)
            if ja_text is None or en_text is None:
                ja_text, en_text = jpn.get(b), eng.get(a)
            if ja_text is None or en_text is None:
                continue
            # One Japanese rendering per English sentence: several translations
            # of the same sentence would show up as duplicate cards.
            if en_text in seen_en:
                continue
            seen_en[en_text] = True
            pairs.append((en_text, ja_text))

    print(f"  {len(pairs):,} English/Japanese pairs")
    out = os.path.join(CACHE, "sentences.tsv")
    with open(out, "w", encoding="utf-8") as f:
        for i, (en, ja) in enumerate(pairs, 1):
            f.write(f"{i}\t{en}\t{ja}\n")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
