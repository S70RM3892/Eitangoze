#!/usr/bin/env python3
"""Distil the English Wiktionary extract into the entries we actually teach.

Input : raw/kaikki-en.jsonl   (~3.2 GB, one JSON object per word+part-of-speech)
Output: cache/wikt.jsonl      (one compact object per kept entry)

Wiktionary is the only open source that carries, for the *same* sense, all of:
a definition, a usage example, grammar labels (transitive / countable / formal),
and a Japanese translation. Those four together are what a Japanese learner
needs per meaning, so it is the backbone of the database.

Two kinds of entry are kept:

  words    anything on a graded list or frequent enough in English Wikipedia
  phrases  multi-word entries Wiktionary itself files as a phrasal verb, an
           idiom or a proverb, whose component words are ordinary vocabulary
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
CACHE = os.path.join(HERE, "cache")

try:
    import orjson

    def loads(s):
        return orjson.loads(s)
except ImportError:  # pragma: no cover - orjson is only a speed-up
    def loads(s):
        return json.loads(s)

POS_KEEP = {
    "noun": "n", "verb": "v", "adj": "adj", "adv": "adv", "prep": "prep",
    "conj": "conj", "pron": "pron", "det": "det", "num": "num",
    "intj": "intj", "phrase": "phr", "prep_phrase": "phr", "proverb": "phr",
}

# Senses carrying any of these are not what a 2020s exam tests.
DROP_TAGS = {
    "obsolete", "archaic", "dated", "dialectal", "rare", "nonstandard",
    "misspelling", "eye-dialect", "pronunciation-spelling", "form-of",
    "alt-of", "abbreviation", "initialism", "acronym", "offensive", "slur",
    "vulgar", "romanization", "plural-of", "past-of", "participle-of",
}
# Grammar labels worth showing: this is the 語法 information.
KEEP_TAGS = {
    "transitive", "intransitive", "countable", "uncountable", "formal",
    "informal", "figurative", "literary", "colloquial", "idiomatic",
    "attributive", "predicative", "ditransitive", "reflexive", "passive",
    "usually", "chiefly", "British", "US", "plural-only", "singular-only",
    "comparable", "not-comparable", "auxiliary", "modal", "slang",
}

PHRASE_CATEGORIES = ("English phrasal verbs", "English idioms", "English proverbs")
WORD_RE = re.compile(r"^[a-z][a-z'\-]*$")
PHRASE_RE = re.compile(r"^[a-z][a-z' \-]*$")
GLOSS_CLEAN = re.compile(r"\s+")


def sense_tags(sense):
    return set(sense.get("tags") or [])


def sense_categories(sense):
    return [c.get("name", "") for c in (sense.get("categories") or []) if isinstance(c, dict)]


def pick_ipa(entry):
    """General-American first; anything with a transcription otherwise."""
    best = None
    for s in entry.get("sounds") or []:
        ipa = s.get("ipa")
        if not ipa:
            continue
        tags = set(s.get("tags") or [])
        if {"US", "General-American"} & tags:
            return ipa
        if best is None and not ({"UK", "Received-Pronunciation", "Australia"} & tags):
            best = ipa
        elif best is None:
            best = ipa
    return best or ""


FORM_TAGS = (
    ("plural", "plural"), ("past", "past"), ("participle", "participle"),
    ("comparative", "comparative"), ("superlative", "superlative"),
    ("third-person", "3sg"),
)


def pick_forms(entry):
    out = []
    for f in entry.get("forms") or []:
        word = f.get("form") or ""
        tags = set(f.get("tags") or [])
        if not word or word in ("-", "—") or "error" in tags:
            continue
        if {"dialectal", "obsolete", "archaic", "nonstandard", "rare"} & tags:
            continue
        for tag, label in FORM_TAGS:
            if tag in tags:
                if tag == "participle" and "present" in tags:
                    label = "ing"
                out.append([label, word])
                break
    seen, uniq = set(), []
    for label, word in out:
        if (label, word) in seen:
            continue
        seen.add((label, word))
        uniq.append([label, word])
    return uniq[:8]


ROOT_TEMPLATES = {"root", "inh", "der", "bor", "lbor", "learned borrowing"}
ROOT_LANGS = {
    "la": "ラテン語", "grc": "ギリシャ語", "ine-pro": "印欧祖語",
    "fr": "フランス語", "LL.": "後期ラテン語", "ML.": "中世ラテン語",
}


def pick_etymology(entry):
    """The Latin/Greek root, which is what makes families of words learnable."""
    for t in entry.get("etymology_templates") or []:
        if t.get("name") not in ROOT_TEMPLATES:
            continue
        args = t.get("args") or {}
        lang, form = args.get("2"), args.get("3")
        if lang in ("la", "grc") and form:
            gloss = args.get("4") or args.get("t") or ""
            return {"lang": ROOT_LANGS[lang], "form": form, "gloss": gloss}
    return None


def related_words(entry, key, limit=12):
    out = []
    for r in entry.get(key) or []:
        w = r.get("word")
        if w and WORD_RE.match(w) and w not in out:
            out.append(w)
        if len(out) >= limit:
            break
    return out


def japanese_translations(entry, n_senses):
    """Japanese translations, attached to a sense index where possible.

    Wiktionary hangs a translation table either off the whole entry or off one
    sense, so both places are read. Either way wiktextract adds a `_dis1`
    distribution over *all* of the entry's senses; where it is confident we
    trust its argmax, otherwise the translation is filed against the entry.
    """
    per_sense = {}
    entry_level = []
    tables = list(entry.get("translations") or [])
    for sense in entry.get("senses") or []:
        tables.extend(sense.get("translations") or [])
    for t in tables:
        if t.get("code") != "ja" and t.get("lang") != "Japanese":
            continue
        word = (t.get("word") or "").strip()
        if not word or len(word) > 24:
            continue
        item = {"ja": word, "kana": (t.get("alt") or "").strip(),
                "roman": (t.get("roman") or "").strip(),
                "sense": (t.get("sense") or "").strip()}
        dis = (t.get("_dis1") or "").split()
        if len(dis) == n_senses and n_senses:
            try:
                nums = [float(x) for x in dis]
            except ValueError:
                nums = []
            if nums and max(nums) >= 30:
                per_sense.setdefault(nums.index(max(nums)), []).append(item)
                continue
        entry_level.append(item)
    return per_sense, entry_level


def sense_matches(sense_key, gloss):
    """Does a translation table's `sense` label describe this gloss?

    The label is an abbreviated gloss ("to give up control of, surrender" for
    "To give up or relinquish control of..."), so content-word overlap is a far
    better test than any prefix match.
    """
    a = {w for w in re.findall(r"[a-z]+", sense_key.lower()) if len(w) > 2}
    b = {w for w in re.findall(r"[a-z]+", gloss.lower()) if len(w) > 2}
    if not a or not b:
        return False
    return len(a & b) / len(a) >= 0.6


def clean_gloss(g):
    return GLOSS_CLEAN.sub(" ", g).strip()


def take_senses(entry, max_senses=8):
    out = []
    for sense in entry.get("senses") or []:
        glosses = sense.get("glosses") or []
        if not glosses:
            continue
        tags = sense_tags(sense)
        if tags & DROP_TAGS or sense.get("form_of") or sense.get("alt_of"):
            continue
        gloss = clean_gloss(glosses[-1])
        if not gloss or len(gloss) > 240:
            continue
        examples = []
        for ex in sense.get("examples") or []:
            text = clean_gloss(ex.get("text") or "")
            if not text or not (12 <= len(text) <= 160):
                continue
            examples.append({"text": text, "ref": ex.get("ref", ""),
                             "quote": ex.get("type") == "quotation"})
            if len(examples) >= 4:
                break
        out.append({
            "gloss": gloss,
            "parent": clean_gloss(glosses[0]) if len(glosses) > 1 else "",
            "tags": sorted(tags & KEEP_TAGS),
            "topics": sorted(set(sense.get("topics") or []))[:3],
            "ex": examples,
            "syn": [s.get("word") for s in (sense.get("synonyms") or [])
                    if s.get("word") and WORD_RE.match(s["word"])][:6],
            "ant": [s.get("word") for s in (sense.get("antonyms") or [])
                    if s.get("word") and WORD_RE.match(s["word"])][:6],
            "cats": sense_categories(sense),
        })
        if len(out) >= max_senses:
            break
    return out


def phrase_kind(entry, senses):
    cats = set()
    for c in entry.get("categories") or []:
        if isinstance(c, dict):
            cats.add(c.get("name", ""))
    for s in senses:
        cats.update(s["cats"])
    joined = " | ".join(c for c in cats if c)
    if "phrasal verb" in joined.lower():
        return "phrasal_verb"
    if "English proverbs" in cats:
        return "proverb"
    if "idiom" in joined.lower() or any("idiomatic" in s["tags"] for s in senses):
        return "idiom"
    return None


def main():
    os.makedirs(CACHE, exist_ok=True)
    with open(os.path.join(CACHE, "wordlists.json"), encoding="utf-8") as f:
        candidates = set(json.load(f))

    kept = phrases = scanned = 0
    out_path = os.path.join(CACHE, "wikt.jsonl")
    with open(os.path.join(RAW, "kaikki-en.jsonl"), "rb") as src, \
            open(out_path, "w", encoding="utf-8") as dst:
        for line in src:
            scanned += 1
            if scanned % 200000 == 0:
                print(f"  {scanned:,} scanned, {kept:,} kept ({phrases:,} phrases)",
                      flush=True)
            try:
                entry = loads(line)
            except Exception:
                continue
            pos = POS_KEEP.get(entry.get("pos"))
            if not pos:
                continue
            word = (entry.get("word") or "").strip()
            multiword = " " in word
            if multiword:
                if not PHRASE_RE.match(word) or word.count(" ") > 4:
                    continue
            elif word not in candidates or not WORD_RE.match(word):
                continue

            senses = take_senses(entry)
            if not senses:
                continue
            kind = phrase_kind(entry, senses)
            if multiword and kind is None:
                continue
            if multiword:
                # Only phrases built from vocabulary the learner already meets.
                parts = word.split()
                if sum(1 for p in parts if p in candidates) < len(parts) - 1:
                    continue
                phrases += 1

            per_sense_ja, entry_ja = japanese_translations(entry, len(entry.get("senses") or []))
            # The Japanese index is over the *unfiltered* sense list, so re-map it
            # onto the senses that survived filtering by matching gloss text.
            kept_glosses = {clean_gloss((s.get("glosses") or [""])[-1]): i
                            for i, s in enumerate(entry.get("senses") or [])
                            if s.get("glosses")}
            survived = set()
            for s in senses:
                original = kept_glosses.get(s["gloss"])
                if original is not None and original in per_sense_ja:
                    s["ja"] = per_sense_ja[original]
                    survived.add(original)
            # Translations pinned to a sense we dropped are not thrown away;
            # they go back into the pool the gloss matcher below draws from.
            for idx, items in per_sense_ja.items():
                if idx not in survived:
                    entry_ja.extend(items)
            for s in senses:
                s.pop("cats", None)
                # A sense with no Japanese of its own still deserves one: fall
                # back to whichever entry-level translation names it.
                if not s.get("ja"):
                    matched = [t for t in entry_ja
                               if t["sense"] and sense_matches(t["sense"], s["gloss"])]
                    if matched:
                        s["ja"] = matched[:4]
            # A single-sense entry is unambiguous: everything belongs to it.
            if len(senses) == 1 and not senses[0].get("ja") and entry_ja:
                senses[0]["ja"] = entry_ja[:4]

            rec = {
                "w": word, "pos": pos, "kind": kind or "word",
                "ipa": pick_ipa(entry), "forms": pick_forms(entry),
                "etym": pick_etymology(entry),
                "senses": senses,
                "ja": entry_ja[:8],
                "derived": related_words(entry, "derived"),
                "related": related_words(entry, "related"),
                "syn": related_words(entry, "synonyms", 8),
                "ant": related_words(entry, "antonyms", 8),
            }
            dst.write(json.dumps(rec, ensure_ascii=False) + "\n")
            kept += 1

    print(f"scanned {scanned:,} entries, kept {kept:,} ({phrases:,} multi-word)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
