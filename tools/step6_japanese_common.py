#!/usr/bin/env python3
"""Which Japanese words are everyday words.

Input : raw/JMdict_e.gz
Output: cache/ja_common.json   ["走る", "話し合う", ...]

Not used for translation — reading a Japanese-English dictionary backwards
produces poor glosses, and the app does not do it. This is used only to *order*
the Japanese the other sources already chose.

The Japanese WordNet lists every lemma of a synset in no useful order, so
`talk about` comes out as 議する before 話し合う and `wait for` as 待ちのぞむ
before 待つ. JMdict marks which headwords are frequent (the ichi/news/spec
priority tags from newspaper and frequency studies), and sorting by that puts
the word a learner would actually write first.
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
# ichi1/news1/spec1 mark the frequent half of each priority list; the `2`
# variants are a long tail that would let almost anything through.
PRIORITY = {"ichi1", "news1", "spec1", "gai1"}


def flatten(chunk):
    def repl(m):
        name = m.group(1)
        return m.group(0) if name in KEEP_ENTITIES else name
    return ENTITY_RE.sub(repl, chunk)


def main():
    os.makedirs(CACHE, exist_ok=True)
    parser = ET.XMLPullParser(["end"])
    common = set()
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
                        priorities = {p.text for p in node.findall(f"./{pri_tag}")}
                        if not (priorities & PRIORITY):
                            continue
                        text = node.findtext(f"./{form_tag}")
                        if text and len(text) <= 16:
                            common.add(text)
                elem.clear()

    print(f"  {entries:,} JMdict entries -> {len(common):,} everyday Japanese words")
    with open(os.path.join(CACHE, "ja_common.json"), "w", encoding="utf-8") as f:
        json.dump(sorted(common), f, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
