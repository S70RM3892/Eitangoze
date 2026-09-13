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

# --- morphology -------------------------------------------------------------
#
# A word breaks into a stem that carries the meaning and affixes that operate on
# it: intro- + duc + -tion. Wiktionary writes those breaks down itself, in the
# etymology templates, which is the only reason this can be done without
# guessing — a rule that strips letters off the front of words turns `region`
# into re- + gion and teaches something false.

# Templates that state a decomposition. `prefix` and `suffix` name the affix in
# one argument and the rest in the others; `affix` and `confix` spell every part
# out with its own hyphens.
AFFIX_TEMPLATES = {
    "prefix": "prefix", "pre": "prefix",
    "suffix": "suffix", "suf": "suffix",
    "affix": "affix", "af": "affix",
    "confix": "confix", "con": "confix",
    "circumfix": "confix",
}
# Wiktionary annotates an argument in place: `non-<id:not>`, `back<t:rear>`.
ANNOTATION_RE = re.compile(r"<[^>]*>")
# Wiktionary's own entries for the affixes, which is where their meanings come
# from. Kept separately from the words: an affix is not a word to learn, it is
# the operator that explains a hundred of them.
AFFIX_POS = {"prefix", "suffix", "affix", "interfix", "infix", "circumfix"}
AFFIX_RE = re.compile(r"^(-[a-z][a-z-]{0,14}|[a-z][a-z-]{0,14}-)$")


def segment_of(form, gloss):
    """One piece of a word, labelled by where its hyphen is."""
    # Arguments sometimes carry a link fragment: `-y#etymology_3`.
    form = (form or "").split("#", 1)[0].strip().lower()
    if not form or form.strip("-") == "":
        return None
    if form.startswith("-") and form.endswith("-"):
        kind = "interfix"
    elif form.startswith("-"):
        kind = "suffix"
    elif form.endswith("-"):
        kind = "prefix"
    else:
        kind = "stem"
    return {"form": form, "kind": kind, "gloss": (gloss or "").strip()}


def pick_affixes(entry):
    """How this word breaks up, straight from Wiktionary's own etymology.

    Returns the pieces in the order they appear in the word, each tagged as a
    prefix, a stem or a suffix. Only the *first* decomposition template is read:
    an etymology may chain several, but the first one states how this word was
    built, and the rest describe how its parent was.
    """
    for t in entry.get("etymology_templates") or []:
        shape = AFFIX_TEMPLATES.get(t.get("name"))
        if not shape:
            continue
        args = t.get("args") or {}
        if (args.get("1") or "") != "en":
            continue
        # Argument 1 is the language and the parts run from 2 upwards, but the
        # glosses are numbered by *part*: the word at argument 2 is glossed by
        # t1 or gloss1. Getting that off by one silently labels every piece with
        # its neighbour's meaning.
        parts = []
        for i in range(2, 9):
            form = ANNOTATION_RE.sub("", args.get(str(i)) or "").strip()
            if not form:
                break
            gloss = args.get(f"t{i - 1}") or args.get(f"gloss{i - 1}") or ""
            parts.append((form, ANNOTATION_RE.sub("", gloss).strip()))
        if len(parts) < 2:
            continue
        # Each template writes its hyphens differently, and some write none at
        # all: {{suffix|en|connote|ation}} and {{suf|en|abduce|-tion}} are the
        # same statement. Put them back on from the template's own shape.
        if shape == "prefix":
            parts[0] = (parts[0][0].rstrip("-") + "-", parts[0][1])
        elif shape == "suffix":
            parts[1:] = [("-" + f.lstrip("-"), g) for f, g in parts[1:]]
        elif shape == "confix":
            # A confix is an affix at each end with, sometimes, a stem between.
            parts[0] = (parts[0][0].rstrip("-") + "-", parts[0][1])
            parts[-1] = ("-" + parts[-1][0].lstrip("-"), parts[-1][1])
        out = [s for s in (segment_of(f, g) for f, g in parts) if s]
        # A decomposition with no affix in it is a compound, not morphology we
        # can teach as an operator; leave those to the word-family view.
        if len(out) >= 2 and any(s["kind"] != "stem" for s in out):
            return out
    return None


def affix_entry(entry, word):
    """Wiktionary's entry *for* an affix, reduced to what the grid needs."""
    senses = []
    for s in entry.get("senses") or []:
        gloss = clean_gloss((s.get("glosses") or [""])[-1])
        if gloss and not any(t in DROP_TAGS for t in s.get("tags") or []):
            senses.append(gloss)
    if not senses:
        return None
    _, entry_ja = japanese_translations(entry, len(entry.get("senses") or []))
    return {
        "w": word,
        "pos": entry.get("pos"),
        "gloss": senses[:3],
        "ja": [t["ja"] for t in entry_ja][:4],
    }
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


SOURCE_TEMPLATES = {"inh", "der", "bor", "lbor", "af"}
SOURCE_LANGS = {"la": "ラテン語", "grc": "ギリシャ語"}


def pick_etymology(entry):
    """What unites a family of English words, and what each member came from.

    Two different things are wanted here and they come from two places.

    The *grouping* key is Wiktionary's `root` template, which names the
    Proto-Indo-European root: `perspective` and `conspicuous` share `*speḱ-`
    while sharing no Latin word, and grouping on the Latin form would separate
    them. Earlier versions of this took the first Latin argument it found, which
    is usually a prefix, and produced "families" whose shared element was `dis-`.

    The *label* is the Latin or Greek word this particular English word is
    descended from, with its gloss, which is the part a learner can read.
    """
    root = None
    source = None
    for t in entry.get("etymology_templates") or []:
        name = t.get("name")
        args = t.get("args") or {}
        if name == "root" and root is None:
            form = args.get("3") or ""
            if form:
                root = form
        elif name in SOURCE_TEMPLATES and source is None:
            lang, form = args.get("2"), args.get("3")
            if lang in SOURCE_LANGS and form and not form.startswith("-") \
                    and not form.endswith("-") and len(form) >= 3:
                source = {
                    "lang": SOURCE_LANGS[lang],
                    "form": form,
                    "gloss": (args.get("5") or args.get("4") or args.get("t") or "").strip(),
                }
    if root is None and source is None:
        return None
    out = dict(source or {"lang": "", "form": "", "gloss": ""})
    out["root"] = root or ""
    return out


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
    affixes = {}
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
            word = (entry.get("word") or "").strip().lower()
            # Affixes come through this same file, as entries of their own, and
            # are collected before the part-of-speech filter drops them: they
            # are not words to teach, they are what explains the words.
            if entry.get("pos") in AFFIX_POS and AFFIX_RE.match(word):
                found = affix_entry(entry, word)
                if found and word not in affixes:
                    affixes[word] = found
                continue
            pos = POS_KEEP.get(entry.get("pos"))
            if not pos:
                continue
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
                "affix": pick_affixes(entry),
                "senses": senses,
                "ja": entry_ja[:8],
                "derived": related_words(entry, "derived"),
                "related": related_words(entry, "related"),
                "syn": related_words(entry, "synonyms", 8),
                "ant": related_words(entry, "antonyms", 8),
            }
            dst.write(json.dumps(rec, ensure_ascii=False) + "\n")
            kept += 1

    with open(os.path.join(CACHE, "affixes.jsonl"), "w", encoding="utf-8") as dst:
        for word in sorted(affixes):
            dst.write(json.dumps(affixes[word], ensure_ascii=False) + "\n")

    print(f"scanned {scanned:,} entries, kept {kept:,} ({phrases:,} multi-word)")
    print(f"affixes: {len(affixes):,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
