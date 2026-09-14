#!/usr/bin/env python3
"""Re-order the Japanese of a database that has already been built.

Usage:  python3 tools/step6_japanese_common.py      # needs raw/JMdict_e.gz
        python3 tools/reorder_ja.py                 # rewrites the shipped .dbz

`build_db.py` orders each meaning's Japanese as it writes it, so a full rebuild
needs nothing else. A full rebuild also needs ~3.5 GB of sources and several
hours, and the fix this applies is worth shipping on its own: every card in the
app showed the wrong Japanese first.

    child  キッド|小児|子      ->  子|小児|キッド
    money  ゲル|お金|銭        ->  お金|銭|ゲル
    people ピープル|人々|人達   ->  人々|人達|ピープル

Nothing is added, dropped or rewritten — the glosses are the same glosses, in
the order JMdict's frequency bands put them. Both this script and `build_db.py`
call the same `order_japanese`, so a later rebuild lands in the same place.
"""
import gzip
import os
import shutil
import sqlite3
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
DBZ = os.path.join(ASSETS, "content.dbz")

sys.path.insert(0, HERE)
from build_db import load_common_japanese, order_japanese  # noqa: E402

#: How many glosses the headline on an entry carries. Matches build_db.py.
HEADLINE = 3


def reorder(db, common):
    senses = db.execute(
        "SELECT id, entry_id, ord, ja FROM sense ORDER BY entry_id, ord"
    ).fetchall()
    changed = 0
    by_entry = {}
    updates = []
    for sense_id, entry_id, _ord, ja in senses:
        words = ja.split("|") if ja else []
        ordered = order_japanese(words, common)
        if ordered != words:
            changed += 1
            updates.append(("|".join(ordered), sense_id))
        by_entry.setdefault(entry_id, []).append(ordered)
    db.executemany("UPDATE sense SET ja = ? WHERE id = ?", updates)

    # The headline on an entry is the first few glosses of its meanings, in
    # order — so it has to be rebuilt from the new order rather than re-sorted,
    # or a word whose second meaning is commoner keeps the old first word.
    headlines = []
    for entry_id, lists in by_entry.items():
        headline = []
        for words in lists:
            for word in words:
                if word and word not in headline:
                    headline.append(word)
            if len(headline) >= HEADLINE:
                break
        headlines.append(("|".join(headline[:HEADLINE]), entry_id))
    before = dict(db.execute("SELECT id, ja FROM entry").fetchall())
    db.executemany("UPDATE entry SET ja = ? WHERE id = ?", headlines)
    moved = sum(1 for ja, entry_id in headlines if before.get(entry_id) != ja)
    return changed, moved


def main():
    common = load_common_japanese()
    if not common:
        print("cache/ja_rank.json is missing — run step6_japanese_common.py first")
        return 1

    work = tempfile.mkdtemp()
    plain = os.path.join(work, "content.db")
    with gzip.open(DBZ, "rb") as src, open(plain, "wb") as out:
        shutil.copyfileobj(src, out)

    db = sqlite3.connect(plain)
    samples = ("child", "money", "people", "friend", "work", "bus", "television")
    was = {w: db.execute(
        "SELECT ja FROM entry WHERE lemma = ? ORDER BY rank LIMIT 1", (w,)
    ).fetchone() for w in samples}

    changed, moved = reorder(db, common)
    db.commit()
    db.execute("VACUUM")
    db.commit()

    for word in samples:
        now = db.execute(
            "SELECT ja FROM entry WHERE lemma = ? ORDER BY rank LIMIT 1", (word,)
        ).fetchone()
        mark = "  " if was[word] == now else "->"
        print(f"  {mark} {word:<12} {was[word][0] if was[word] else '-':<24} "
              f"{now[0] if now else '-'}")
    db.close()

    with open(plain, "rb") as src, gzip.open(DBZ, "wb", compresslevel=9) as out:
        shutil.copyfileobj(src, out)
    size = os.path.getsize(DBZ) / 1024 / 1024
    print(f"\n  {changed:,} meanings re-ordered, {moved:,} headlines moved")
    print(f"  {DBZ} is now {size:.1f} MB")
    shutil.rmtree(work)
    return 0


if __name__ == "__main__":
    sys.exit(main())
