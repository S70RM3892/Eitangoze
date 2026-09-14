#!/usr/bin/env python3
"""How common each Japanese word is.

Input : raw/JMdict_e.gz
Output: cache/ja_rank.json   {"人々": 1, "ピープル": 60, ...}  lower is commoner

Not used for translation — reading a Japanese-English dictionary backwards
produces poor glosses, and the app does not do it. This is used only to *order*
the Japanese the other sources already chose.

The Japanese WordNet lists every lemma of a synset in no useful order, so
`talk about` comes out as 議する before 話し合う and `wait for` as 待ちのぞむ
before 待つ. JMdict says which headwords are frequent, and sorting by that puts
the word a learner would actually write first.

**A yes/no answer is not enough, and shipping one was a bug.** JMdict's priority
tags include `gai1`, which marks common loanwords, so ピープル and ワーク and
キッド were "common" exactly like 人々 and 仕事 and 子供 — they tied, kept the
arbitrary order they arrived in, and the app taught `child` as 「キッド」,
`money` as 「ゲル」 and `people` as 「ピープル」.

The ranking below separates them, because JMdict also carries frequency *bands*:

    nf01..nf48          the word's band in a newspaper frequency study
    ichi1/news1/spec1   frequent, but no band given — worth 20, see COMMON_RANK
    gai1                a common loanword

    人々 1    ピープル 60      子供 1   キッド 60     お金 4   ゲル 60

Loanwords that really are the ordinary Japanese keep their place, because they
carry the other tags too: バス is ichi1 (20) and 乗合自動車 has nothing (100),
so a bus is still バス.
"""
import gzip
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
CACHE = os.path.join(HERE, "cache")

KEEP_ENTITIES = ("amp", "lt", "gt", "quot", "apos")
ENTITY_RE = re.compile(r"&([A-Za-z0-9-]+);")

# Ranks are "where in the language this word sits". The numbers are only ever
# compared with each other.
COMMON = {"ichi1", "news1", "spec1"}
LOANWORD = "gai1"
LOANWORD_RANK = 60

#: What "common, but JMdict gives no band" is worth.
#:
#: The bands are sparse and uneven — 参る carries one and 行く does not — so
#: ranking every banded word above every unbanded one puts the humble verb in
#: front of the ordinary one. Twenty is where the two agree: measured against
#: 行く/参る, やる/致す, ほぼ/ざっと and 見る/ご覧になる, it is the only value
#: in 10..30 that reads correctly for all four.
COMMON_RANK = 20

#: A word JMdict says nothing about. Not evidence that it is rare — only that
#: there is no reason to move it up.
UNRANKED = 100


def flatten(chunk):
    def repl(m):
        name = m.group(1)
        return m.group(0) if name in KEEP_ENTITIES else name
    return ENTITY_RE.sub(repl, chunk)


def rank_of(priorities):
    """The best (lowest) rank the priority tags of one form justify."""
    bands = [int(p[2:]) for p in priorities if p.startswith("nf") and p[2:].isdigit()]
    if bands:
        return min(bands)  # nf01..nf48, the newspaper frequency band
    if priorities & COMMON:
        return COMMON_RANK
    if LOANWORD in priorities:
        return LOANWORD_RANK
    return None


def main():
    os.makedirs(CACHE, exist_ok=True)
    parser = ET.XMLPullParser(["end"])
    ranks = {}
    entries = 0

    with gzip.open(os.path.join(RAW, "JMdict_e.gz"), "rt", encoding="utf-8") as f:
        for line in f:
            parser.feed(flatten(line))
            for _, elem in parser.read_events():
                if elem.tag != "entry":
                    continue
                entries += 1
                for element, form_tag, pri_tag in (
                    ("k_ele", "keb", "ke_pri"), ("r_ele", "reb", "re_pri"),
                ):
                    for node in elem.findall(f"./{element}"):
                        text = node.findtext(f"./{form_tag}")
                        if not text:
                            continue
                        rank = rank_of({p.text for p in node.findall(f"./{pri_tag}")})
                        if rank is not None:
                            ranks[text] = min(ranks.get(text, UNRANKED), rank)
                elem.clear()

    banded = sum(1 for r in ranks.values() if r < COMMON_RANK)
    print(f"  {entries:,} JMdict entries -> {len(ranks):,} ranked Japanese words "
          f"({banded:,} with a frequency band)")
    with open(os.path.join(CACHE, "ja_rank.json"), "w", encoding="utf-8") as f:
        json.dump(ranks, f, ensure_ascii=False)


if __name__ == "__main__":
    sys.exit(main())
