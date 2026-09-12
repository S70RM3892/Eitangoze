#!/usr/bin/env python3
"""Sense-aligned Japanese, from the Japanese WordNet.

Input : raw/wnjpn.db          (Japanese WordNet, synset-aligned to WordNet 3.0)
        raw/dict/index.sense  (Princeton WordNet: sense order + SemCor counts)
Output: cache/wordnet.json    { "lemma|pos": [sense, ...] }

Why this source and not a bilingual dictionary: a Japanese-English dictionary
maps *words* to words, so a polysemous word comes back as an undifferentiated
pile of Japanese. WordNet maps *senses*, and the Japanese WordNet attaches
Japanese lemmas, a Japanese definition and a translated example to the very
same synset. That is the only open way to say "this meaning of `spare` is
「予備の」and that one is 「惜しむ」".

SemCor tag counts ride along from index.sense. They are how the app knows which
meaning of a polysemous word is the one worth learning first.
"""
import collections
import json
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
CACHE = os.path.join(HERE, "cache")

POS_OF_SS = {"1": "n", "2": "v", "3": "adj", "4": "adv", "5": "adj"}
POS_OF_SUFFIX = {"n": "n", "v": "v", "a": "adj", "s": "adj", "r": "adv"}
SENSE_KEY_RE = re.compile(r"^(.+)%(\d):")


def read_tag_counts():
    """lemma|pos -> {synset_id: (sense_number, tag_count)} from index.sense."""
    counts = collections.defaultdict(dict)
    path = os.path.join(RAW, "dict", "index.sense")
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4:
                continue
            key, offset, sense_no, tag_cnt = parts[0], parts[1], parts[2], parts[3]
            m = SENSE_KEY_RE.match(key)
            if not m:
                continue
            lemma, ss_type = m.group(1).replace("_", " "), m.group(2)
            pos = POS_OF_SS.get(ss_type)
            if not pos:
                continue
            synset = f"{offset}-{'nvar'[int(ss_type) - 1] if ss_type != '5' else 's'}"
            try:
                counts[f"{lemma}|{pos}"][synset] = (int(sense_no), int(tag_cnt))
            except ValueError:
                continue
    print(f"  index.sense: {len(counts)} lemma/pos keys")
    return counts


def main():
    os.makedirs(CACHE, exist_ok=True)
    tag_counts = read_tag_counts()

    db = sqlite3.connect(os.path.join(RAW, "wnjpn.db"))
    db.row_factory = None

    print("  loading Japanese lemmas per synset")
    ja_by_synset = collections.defaultdict(list)
    for synset, lemma in db.execute(
            "SELECT s.synset, w.lemma FROM sense s JOIN word w ON w.wordid = s.wordid "
            "WHERE w.lang = 'jpn' ORDER BY s.synset, s.rank"):
        if lemma and len(lemma) <= 20 and lemma not in ja_by_synset[synset]:
            ja_by_synset[synset].append(lemma)

    print("  loading definitions and examples")
    def_en, def_ja = {}, {}
    for synset, lang, definition in db.execute(
            "SELECT synset, lang, def FROM synset_def WHERE lang IN ('eng','jpn')"):
        target = def_en if lang == "eng" else def_ja
        # A synset has several `def` rows: the first is the full definition and
        # the rest are the quoted examples split out. The first is what we want.
        if synset not in target:
            target[synset] = definition

    ex = collections.defaultdict(lambda: {"eng": [], "jpn": []})
    for synset, lang, sentence in db.execute(
            "SELECT synset, lang, def FROM synset_ex WHERE lang IN ('eng','jpn') ORDER BY sid"):
        ex[synset][lang].append(sentence)

    print("  walking English lemmas")
    out = collections.defaultdict(list)
    rows = db.execute(
        "SELECT w.lemma, s.synset, s.rank FROM sense s JOIN word w ON w.wordid = s.wordid "
        "WHERE w.lang = 'eng'")
    for lemma, synset, rank in rows:
        if not lemma or "_" in lemma and lemma.count("_") > 3:
            continue
        lemma = lemma.replace("_", " ").lower()
        pos = POS_OF_SUFFIX.get(synset.rsplit("-", 1)[-1])
        if not pos:
            continue
        ja = ja_by_synset.get(synset)
        if not ja:
            continue  # no Japanese for this synset: nothing to contribute
        key = f"{lemma}|{pos}"
        sense_no, tag_cnt = tag_counts.get(key, {}).get(synset, (int(rank or 99), 0))
        pairs = ex.get(synset)
        examples = []
        if pairs:
            for en, jp in zip(pairs["eng"], pairs["jpn"]):
                if 10 <= len(en) <= 140:
                    examples.append([en, jp])
        out[key].append({
            "synset": synset,
            "n": sense_no,
            "cnt": tag_cnt,
            "def_en": def_en.get(synset, ""),
            "def_ja": def_ja.get(synset, ""),
            "ja": ja[:6],
            "ex": examples[:2],
        })

    for key, senses in out.items():
        # Most-used meaning first: SemCor count, then WordNet's own sense order.
        senses.sort(key=lambda s: (-s["cnt"], s["n"]))
        del senses[8:]

    print(f"  {len(out)} lemma/pos keys with Japanese, "
          f"{sum(len(v) for v in out.values())} senses")
    with open(os.path.join(CACHE, "wordnet.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
