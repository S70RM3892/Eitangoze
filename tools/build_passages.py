#!/usr/bin/env python3
"""Assemble the parsed passages into the database the app ships.

Input : cache/syntax.jsonl, app/src/main/assets/content.dbz
Output: app/src/main/assets/passages.dbz

Kept apart from content.dbz on purpose. The vocabulary database is expensive to
rebuild and rarely changes; the passage library is the opposite — it is meant to
keep growing — and separating them means a release that only adds reading does
not have to ship 16 MB of unchanged dictionary with it. The study history is
already a third file, so nothing here can disturb anyone's review schedule.

Each passage carries the level it is written at, computed the same way the
reader screen computes coverage: the CEFR band at which 95% of the running words
are accounted for. That is what lets the app hand out a passage instead of
asking the learner to pick one (docs/DESIGN.md ⑧).
"""
import collections
import gzip
import json
import os
import re
import shutil
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")

CEFR_ORDER = ["A1", "A2", "B1", "B2", "C1", "C2"]
# The share of running words a level has to account for before the passage is
# called that level. 95% is the line below which reading stops being reading;
# 98% is where it becomes unassisted (Hu & Nation 2000), and that second number
# is what the app uses at runtime to decide whether a passage is fast-reading
# practice or vocabulary work.
LEVEL_COVERAGE = 0.95

TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z'\-]*")

SCHEMA = """
PRAGMA page_size = 4096;
CREATE TABLE passage (
  id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  genre TEXT NOT NULL,
  source TEXT NOT NULL,
  url TEXT NOT NULL,
  license TEXT NOT NULL,
  words INTEGER NOT NULL,
  cefr TEXT NOT NULL,
  -- Share of running words the vocabulary database can account for at all.
  -- The rest are names and jargon: the passage's own ceiling.
  coverable REAL NOT NULL,
  text TEXT NOT NULL
);
CREATE INDEX passage_genre ON passage(genre, cefr);
CREATE INDEX passage_cefr ON passage(cefr, words);

-- One row per sentence. `agreed` is whether a second parser confirmed the main
-- verb and the tokenisation; only those sentences carry folds, and a sentence
-- without folds is still perfectly readable.
CREATE TABLE sentence (
  id INTEGER PRIMARY KEY,
  passage_id INTEGER NOT NULL,
  ord INTEGER NOT NULL,
  start INTEGER NOT NULL,
  end INTEGER NOT NULL,
  agreed INTEGER NOT NULL,
  -- Comma-separated, one entry per token, all indices relative to the sentence.
  offsets TEXT NOT NULL,   -- "start:end,start:end,…" into passage.text
  heads TEXT NOT NULL,
  deps TEXT NOT NULL,
  pos TEXT NOT NULL,
  folds TEXT NOT NULL,     -- "start:end:label,…", widest first
  roles TEXT NOT NULL      -- "index:S,index:V,…" on the main clause
);
CREATE INDEX sentence_passage ON sentence(passage_id, ord);

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


def load_levels():
    """lemma → CEFR band, from the vocabulary database that already shipped."""
    packed = os.path.join(ASSETS, "content.dbz")
    plain = os.path.join(CACHE, "content.db")
    if not os.path.exists(plain):
        if not os.path.exists(packed):
            print("build content.dbz first", file=sys.stderr)
            return {}, {}
        with gzip.open(packed, "rb") as src, open(plain, "wb") as dst:
            shutil.copyfileobj(src, dst, 1 << 20)
    db = sqlite3.connect(plain)
    levels = {}
    for lemma, cefr, kind in db.execute("SELECT lemma, cefr, kind FROM entry"):
        levels.setdefault(lemma, cefr)
    # Inflections resolve to their headword, the same index the reader uses.
    surface = {}
    for form, entry_id in db.execute("SELECT form, entry_id FROM surface WHERE words = 1"):
        surface.setdefault(form, entry_id)
    by_id = {}
    for entry_id, lemma in db.execute("SELECT id, lemma FROM entry"):
        by_id[entry_id] = lemma
    forms = {form: by_id[eid] for form, eid in surface.items() if eid in by_id}
    db.close()
    return levels, forms


def level_of(text, levels, forms):
    """The CEFR band covering 95% of the words, and how much is in the dictionary.

    Words the dictionary has never heard of are counted apart rather than
    thrown in at the top. A Wikipedia article on teleconnection is thick with
    place names and jargon, and letting those vote makes every passage C2 —
    which is what happened the first time this ran: 34 out of 34. A proper noun
    is not a hard word, it is a name, and the reader meets it with the text.

    The second number is the passage's own ceiling: the share of its running
    words that any amount of study could ever cover.
    """
    counts = collections.Counter()
    total = known = 0
    for token in TOKEN_RE.findall(text.lower()):
        total += 1
        band = levels.get(forms.get(token, token)) or levels.get(token)
        if band:
            known += 1
            counts[band] += 1
    if not known:
        return "C2", 0.0
    running = 0
    for band in CEFR_ORDER:
        running += counts.get(band, 0)
        if running / known >= LEVEL_COVERAGE:
            return band, known / max(1, total)
    return "C2", known / max(1, total)


def pack_offsets(offsets):
    return ",".join(f"{a}:{b}" for a, b in offsets)


def pack_folds(folds):
    return ",".join(f"{f['start']}:{f['end']}:{f['label']}" for f in folds)


def pack_roles(roles):
    return ",".join(f"{index}:{role}" for index, role in sorted(
        ((int(k), v) for k, v in roles.items())))


def main():
    src = os.path.join(CACHE, "syntax.jsonl")
    if not os.path.exists(src):
        print("run tools/step8_syntax.py first", file=sys.stderr)
        return 1
    with open(src, encoding="utf-8") as f:
        passages = [json.loads(line) for line in f]
    print(f"{len(passages):,} passages")

    levels, forms = load_levels()
    print(f"  {len(levels):,} headwords and {len(forms):,} inflections for levelling")

    os.makedirs(ASSETS, exist_ok=True)
    tmp = os.path.join(CACHE, "passages.db")
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript(SCHEMA)

    passage_rows, sentence_rows = [], []
    by_level = collections.Counter()
    by_genre = collections.Counter()
    sentence_id = 0
    agreed = 0
    for index, passage in enumerate(passages, 1):
        text = passage["text"]
        cefr, coverable = level_of(text, levels, forms)
        by_level[cefr] += 1
        by_genre[passage["genre"]] += 1
        passage_rows.append((
            index, passage["title"], passage["genre"], passage["source"],
            passage["url"], passage["license"], len(text.split()), cefr,
            round(coverable, 4), text,
        ))
        for ord_, sentence in enumerate(passage["sentences"]):
            sentence_id += 1
            agreed += bool(sentence["agreed"])
            sentence_rows.append((
                sentence_id, index, ord_, sentence["start"], sentence["end"],
                int(bool(sentence["agreed"])),
                pack_offsets(sentence["offsets"]),
                ",".join(str(h) for h in sentence["heads"]),
                ",".join(sentence["deps"]),
                ",".join(sentence["pos"]),
                pack_folds(sentence["folds"]),
                pack_roles(sentence["roles"]),
            ))

    db.executemany("INSERT INTO passage VALUES (?,?,?,?,?,?,?,?,?,?)", passage_rows)
    db.executemany("INSERT INTO sentence VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", sentence_rows)
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("passages", str(len(passage_rows))),
        ("sentences", str(len(sentence_rows))),
        ("agreed", str(agreed)),
        ("schema", "1"),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()

    out = os.path.join(ASSETS, "passages.dbz")
    with open(tmp, "rb") as raw, gzip.open(out, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(raw, dst, 1 << 20)

    words = sum(row[6] for row in passage_rows)
    ceiling = sum(row[8] for row in passage_rows) / max(1, len(passage_rows))
    folds = sum(len(s["folds"]) for p in passages for s in p["sentences"])
    print(f"\n{len(passage_rows):,} passages / {words:,} running words")
    print(f"{len(sentence_rows):,} sentences, {agreed:,} confirmed by both parsers "
          f"({agreed * 100 // max(1, len(sentence_rows))}%), {folds:,} folds")
    print(f"  dictionary covers {ceiling * 100:.1f}% of running words on average")
    print("  levels " + "  ".join(f"{k} {by_level[k]}" for k in CEFR_ORDER if by_level[k]))
    print("  genres " + "  ".join(f"{k} {v}" for k, v in by_genre.most_common()))
    print(f"{os.path.getsize(tmp) / 1e6:.1f} MB -> "
          f"{os.path.getsize(out) / 1e6:.1f} MB gzipped at {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
