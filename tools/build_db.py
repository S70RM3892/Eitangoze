#!/usr/bin/env python3
"""Merge every cached source into the content database the app ships.

Input : cache/{wordlists.json,wikt.jsonl,wordnet.json,sentences.tsv,corpus.txt}
Output: app/src/main/assets/content.dbz

The database is read-only at runtime. Study progress lives in a separate file
the app owns, so the content database can be replaced wholesale by a new
release without touching anyone's review history.
"""
import collections
import gzip
import json
import math
import os
import re
import shutil
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")

CEFR_ORDER = {"A1": 0, "A2": 1, "B1": 2, "B2": 3, "C1": 4, "C2": 5}
CONTENT_POS = ("n", "v", "adj", "adv")

STOP = set("""a an the of to in for on with at by from as is are was were be been being
and or but if then than that this these those it its his her their our your my
one two some any all each every no not do does did have has had will would can
could may might must shall should into over under about after before between
who whom which what when where why how such other more most many much very so
too also only just even still yet both either neither there here""".split())

PREPS = ("about", "above", "across", "after", "against", "along", "among", "around",
         "as", "at", "before", "behind", "below", "beneath", "beside", "between",
         "beyond", "by", "down", "during", "for", "from", "in", "into", "like",
         "near", "of", "off", "on", "onto", "out", "over", "through", "to",
         "toward", "towards", "under", "until", "up", "upon", "with", "within",
         "without")
DETS = ("a", "an", "the", "his", "her", "their", "its", "your", "my", "our")

TOKEN_RE = re.compile(r"[a-z][a-z'\-]*")
RAW_TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z'\-]*")
WORD_CHARS = re.compile(r"[A-Za-z]")


# --------------------------------------------------------------------------
# loading


JA_SUFFIXES = ("する", "した", "している", "な", "に", "の", "たる", "と")


def load_common_japanese():
    """Everyday Japanese, used to order glosses. Empty if the step was skipped."""
    path = os.path.join(CACHE, "ja_common.json")
    if not os.path.exists(path):
        return set()
    with open(path, encoding="utf-8") as f:
        return set(json.load(f))


def is_common_japanese(word, common):
    if word in common:
        return True
    # JMdict lists 延期 rather than 延期する, so a verbal or adjectival ending is
    # stripped before asking.
    for suffix in JA_SUFFIXES:
        if word.endswith(suffix) and word[:-len(suffix)] in common:
            return True
    return False


def order_japanese(words, common):
    """Everyday words first, original order kept within each group.

    The Japanese WordNet lists a synset's lemmas in no useful order, so 待つ can
    come out behind 待ちのぞむ. This is the only thing the ordering fixes: no
    word is added, dropped or rewritten.
    """
    if not common:
        return words
    return sorted(words, key=lambda w: 0 if is_common_japanese(w, common) else 1)


def load_cache():
    with open(os.path.join(CACHE, "wordlists.json"), encoding="utf-8") as f:
        lists = json.load(f)
    wikt = []
    with open(os.path.join(CACHE, "wikt.jsonl"), encoding="utf-8") as f:
        for line in f:
            wikt.append(json.loads(line))
    with open(os.path.join(CACHE, "wordnet.json"), encoding="utf-8") as f:
        wn = json.load(f)
    affixes = {}
    path = os.path.join(CACHE, "affixes.jsonl")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                a = json.loads(line)
                affixes[a["w"]] = a
    return lists, wikt, wn, affixes


# --------------------------------------------------------------------------
# sense merging


def content_words(text):
    return {w for w in TOKEN_RE.findall(text.lower()) if w not in STOP and len(w) > 2}


def gloss_similarity(a, b):
    """Overlap between a Wiktionary gloss and a WordNet definition."""
    wa, wb = content_words(a), content_words(b)
    if not wa or not wb:
        return 0.0
    shared = wa & wb
    if len(shared) < 2:
        return 0.0
    return len(shared) / min(len(wa), len(wb))


def merge_senses(entry, wn_senses, max_senses=6, prefer_wikt=False):
    """Wiktionary senses enriched with the Japanese of the WordNet sense they match.

    Wiktionary carries the example and the grammar label; the Japanese WordNet
    carries the Japanese and a usage frequency. Neither alone is enough, and
    they are aligned only through their definitions, so the definitions are what
    we match on. Unmatched WordNet senses with a real SemCor count are appended:
    a meaning that shows up in a tagged corpus is a meaning worth knowing, even
    when Wiktionary files it somewhere we could not see.
    """
    senses = []
    used_wn = set()
    for ord_, s in enumerate(entry["senses"]):
        best, best_score = None, 0.30
        for i, w in enumerate(wn_senses):
            if i in used_wn:
                continue
            score = gloss_similarity(s["gloss"], w["def_en"])
            if score > best_score:
                best, best_score = i, score
        merged = {
            "ord": ord_,
            "gloss": s["gloss"],
            "parent": s.get("parent", ""),
            "tags": s["tags"],
            "topics": s.get("topics") or [],
            "syn": s["syn"],
            "ant": s["ant"],
            "ja": [t["ja"] for t in (s.get("ja") or [])],
            "ja_def": "",
            "semcor": 0,
            "synset": "",
            "ex": [[e["text"], ""] for e in s["ex"]],
            "src": "wikt",
        }
        if best is not None:
            w = wn_senses[best]
            used_wn.add(best)
            merged["synset"] = w["synset"]
            merged["semcor"] = w["cnt"]
            merged["ja_def"] = w["def_ja"]
            for ja in w["ja"]:
                if ja not in merged["ja"]:
                    merged["ja"].append(ja)
            merged["ex"].extend(w["ex"])
        senses.append(merged)

    have_ja = any(s["ja"] for s in senses)
    for i, w in enumerate(wn_senses):
        if i in used_wn or not w["ja"]:
            continue
        # A WordNet sense that matched nothing is still the only Japanese this
        # entry has. Phrasal verbs live here: Wiktionary rarely translates them,
        # and their definitions are worded too differently to align.
        if w["cnt"] < 1 and senses and have_ja:
            continue
        senses.append({
            "ord": len(senses), "gloss": w["def_en"], "parent": "",
            "tags": [], "topics": [], "syn": [], "ant": [],
            "ja": list(w["ja"]), "ja_def": w["def_ja"], "semcor": w["cnt"],
            "synset": w["synset"], "ex": list(w["ex"]), "src": "wn",
        })

    # The most-used meaning first. Where no meaning is corpus-tagged this is a
    # no-op and Wiktionary's own ordering survives.
    #
    # Multi-word entries lead with the senses Wiktionary itself defines. WordNet
    # knows `come back` as a member of half a dozen synsets and tags none of
    # them, so ranking it by SemCor count puts 甦る in front of 戻る.
    if prefer_wikt:
        senses.sort(key=lambda s: (s["src"] != "wikt", -s["semcor"], s["ord"]))
    else:
        senses.sort(key=lambda s: (-s["semcor"], s["ord"]))
    for i, s in enumerate(senses):
        s["ord"] = i
        s["ja"] = s["ja"][:5]
        s["ex"] = s["ex"][:3]
    return senses[:max_senses]


# --------------------------------------------------------------------------
# entry selection and ordering


def estimate_cefr(info, has_ja):
    """CEFR-J stops at B2. Above it, frequency in expository prose is the guide."""
    if info["cefr"]:
        return info["cefr"], 0
    rank = info["wiki_rank"] or 10 ** 9
    if "nawl" in info["lists"]:
        return "B2", 1
    if rank <= 12000:
        return "C1", 1
    if rank <= 30000 and has_ja:
        return "C2", 1
    return "C2", 1


def inflection_map(wikt):
    """Surface form -> lemma, so a phrase can be counted however it is conjugated."""
    out = {}
    for entry in wikt:
        lemma = entry["w"]
        if " " in lemma:
            continue
        for _, form in entry["forms"]:
            form = (form or "").lower()
            if form and " " not in form and form != lemma:
                out.setdefault(form, lemma)
    return out


def forms_of_lemma(wikt):
    """lemma -> every inflected spelling, for expanding phrase surfaces."""
    out = collections.defaultdict(set)
    for entry in wikt:
        lemma = entry["w"]
        if " " in lemma:
            continue
        for _, form in entry["forms"]:
            form = (form or "").lower()
            if form and " " not in form:
                out[lemma].add(form)
    return out


def count_phrases(phrases, forms):
    """How often each multi-word entry actually turns up in running English.

    Wiktionary lists every idiom anyone ever wrote down, including centuries-old
    ones. Counting them in a modern corpus is what separates `look up to` from
    `gussy up`. The first token is folded back to its lemma so `looked up to`
    and `looks up to` count for the same entry.
    """
    wanted = collections.defaultdict(set)
    max_len = 1
    for phrase in phrases:
        parts = phrase.split()
        wanted[len(parts)].add(phrase)
        max_len = max(max_len, len(parts))
    counts = collections.Counter()

    def feed(text):
        tokens = TOKEN_RE.findall(text.lower())
        n_tokens = len(tokens)
        for i in range(n_tokens):
            head = forms.get(tokens[i], tokens[i])
            for n in range(2, max_len + 1):
                if i + n > n_tokens:
                    break
                gram = " ".join([head] + tokens[i + 1:i + n])
                if gram in wanted.get(n, ()):
                    counts[gram] += 1

    for name in ("corpus.txt",):
        path = os.path.join(CACHE, name)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                for line in f:
                    feed(line)
    with open(os.path.join(CACHE, "sentences.tsv"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3:
                feed(parts[1])
    return counts


# Closed-class words carry grammar, not meaning. Several have no translatable
# gloss in any of our sources ("is", "an", "how"), so they would be dropped — and
# then a page of ordinary English would come back one seventh "not in the
# dictionary", which is both wrong and useless for measuring reading.
FUNCTION_POS = {"prep", "conj", "pron", "det", "num", "intj", "prt"}
FUNCTION_WORDS = set("""
be am is are was were been being have has had having do does did doing
will would shall should can could may might must ought need dare
not no yes there here own very too also just only even still yet such
""".split())


def is_function_word(lemma, pos, info):
    """A grammar word frequent enough that a reader is assumed to know it."""
    if pos not in FUNCTION_POS and lemma not in FUNCTION_WORDS:
        return False
    rank = info.get("wiki_rank") or 10 ** 9
    return rank <= 1000 or bool(set(info.get("lists") or ()) & {"cefrj", "ngsl"})


def teachable(entry):
    """Is there a meaning here worth putting on a card?

    Every entry needs Japanese. A multi-word entry needs more than that: its
    *first* meaning has to be one Wiktionary itself states. WordNet lists `go to`
    as a member of synsets it never tags, so taking its first meaning on trust
    glosses it 連なる. Extra WordNet senses still ride along underneath.
    """
    senses = entry["senses"]
    if entry["kind"] == "function":
        return True
    if not any(s["ja"] for s in senses):
        return False
    if entry["kind"] == "word":
        return True
    return senses[0]["src"] == "wikt" and bool(senses[0]["ja"])


def borrow_japanese(entries):
    """Give a phrase the Japanese of the single word that means the same thing.

    `put off` is defined as "to postpone"; `postpone` already carries 延期する.
    Wiktionary states that synonymy itself, so this is following a link rather
    than guessing, and the borrowed reading is marked as such for the UI.
    """
    by_lemma = {}
    for e in entries:
        if e["kind"] != "word":
            continue
        # Only a word with a single meaning can lend it. `postpone` means one
        # thing, so `put off` may take 延期する from it; `address` means six, and
        # borrowing its first would gloss `deal with` as 呼びかける.
        translated = [s for s in e["senses"] if s["ja"]]
        if len(translated) != 1 or len(e["senses"]) > 2:
            continue
        by_lemma.setdefault((e["lemma"], e["pos"]), translated[0]["ja"])
    borrowed = 0
    for e in entries:
        if e["kind"] == "word":
            continue
        for s in e["senses"]:
            if s["ja"]:
                continue
            for syn in s["syn"]:
                ja = by_lemma.get((syn, e["pos"]))
                if ja:
                    s["ja"] = list(ja[:3])
                    s["ja_from"] = syn
                    borrowed += 1
                    break
    print(f"  Japanese borrowed from a synonym for {borrowed:,} phrase senses")


def select_entries(lists, wikt, wn, phrase_counts):
    """Pick what to ship, and decide the order it is taught in."""
    chosen = []
    for entry in wikt:
        lemma, pos, kind = entry["w"], entry["pos"], entry["kind"]
        if " " not in lemma:
            kind = "word"  # Wiktionary files some single words under phrasal categories
        wn_senses = wn.get(f"{lemma}|{pos}", [])
        senses = merge_senses(entry, wn_senses, prefer_wikt=kind != "word")
        if not senses:
            continue
        has_ja = any(s["ja"] for s in senses)
        info = lists.get(lemma)

        if kind == "word":
            if info is None:
                continue
            graded = bool(set(info["lists"]) & {"cefrj", "ngsl", "nawl", "tsl"})
            rank = info["wiki_rank"] or 10 ** 9
            if not has_ja and is_function_word(lemma, pos, info):
                # Kept for the reading analysis only: no deck draws from
                # `function`, so these never turn into cards.
                kind = "function"
            # Ungraded words earn their place by being both frequent in
            # expository English and translatable; an entry with no Japanese
            # cannot be made into a card worth answering.
            if kind != "function":
                if not graded and not (rank <= 30000 and has_ja):
                    continue
                if not has_ja and not graded:
                    continue
            cefr, est = estimate_cefr(info, has_ja)
            sort_key = (CEFR_ORDER[cefr], info["ngsl_rank"] or 10 ** 6, rank)
            freq = info["wiki_count"]
            tags = list(info["lists"])
        else:
            # A WordNet synset that lists this phrase as a member is direct
            # evidence about the phrase, so those senses stay even when no
            # Wiktionary definition matched them. The bad glosses that looked
            # like WordNet's fault turned out to come from borrowing across a
            # polysemous synonym, which `borrow_japanese` now refuses to do.
            has_syn = any(s["syn"] for s in senses)
            if not has_ja and not has_syn:
                continue
            # Corpus frequency is what separates an idiom worth a card from one
            # nobody has written since 1890.
            count = phrase_counts.get(lemma, 0)
            if count < 2 and not (count and has_ja):
                continue
            cefr = "B1" if count >= 60 else "B2" if count >= 12 else "C1"
            est = 1
            sort_key = (CEFR_ORDER[cefr] + 10, -count, lemma)
            freq = count
            tags = []

        chosen.append({
            "lemma": lemma, "pos": pos, "kind": kind, "cefr": cefr, "cefr_est": est,
            "sort": sort_key, "freq": freq, "lists": tags, "ipa": entry["ipa"],
            "forms": entry["forms"], "etym": entry["etym"], "senses": senses,
            "affix": entry.get("affix"),
            "derived": entry["derived"], "related": entry["related"],
            "syn": entry["syn"], "ant": entry["ant"],
        })

    chosen = merge_duplicates(chosen)
    chosen.sort(key=lambda e: e["sort"])
    for i, e in enumerate(chosen, 1):
        e["rank"] = i
        e["id"] = i
    return chosen


def merge_duplicates(chosen, max_senses=6):
    """One entry per lemma and part of speech.

    Wiktionary splits a word into separate entries per etymology, so `term` the
    noun appears twice: once from Latin terminus and once as the mathematical
    sense. A learner does not care which Latin word a meaning descends from, and
    two identical-looking rows in a deck are a bug, so the senses are pooled.
    """
    merged = {}
    for entry in chosen:
        key = (entry["lemma"], entry["pos"])
        first = merged.get(key)
        if first is None:
            merged[key] = entry
            continue
        seen = {s["gloss"] for s in first["senses"]}
        for sense in entry["senses"]:
            if sense["gloss"] not in seen and len(first["senses"]) < max_senses:
                seen.add(sense["gloss"])
                first["senses"].append(sense)
        # Keep whichever etymology and pronunciation was actually found.
        first["etym"] = first["etym"] or entry["etym"]
        first["ipa"] = first["ipa"] or entry["ipa"]
        first["forms"] = first["forms"] or entry["forms"]
        for field in ("derived", "related", "syn", "ant"):
            first[field] = (first[field] + entry[field])[:12]
    for entry in merged.values():
        entry["senses"].sort(key=lambda s: (s["src"] != "wikt" if entry["kind"] != "word"
                                            else False, -s["semcor"], s["ord"]))
        for i, sense in enumerate(entry["senses"]):
            sense["ord"] = i
    print(f"  merged {len(chosen) - len(merged):,} duplicate lemma/pos entries")
    return list(merged.values())


# --------------------------------------------------------------------------
# sentences


def build_form_index(entries):
    """Every surface form a learner will meet, mapped back to its entry."""
    index = collections.defaultdict(list)
    for e in entries:
        if e["kind"] != "word":
            continue
        index[e["lemma"]].append(e["id"])
        for _, form in e["forms"]:
            if form and " " not in form:
                index[form.lower()].append(e["id"])
    return index


def sentence_difficulty(tokens, lists):
    worst = 0
    for t in tokens:
        info = lists.get(t)
        rank = (info or {}).get("wiki_rank") or 60000
        worst = max(worst, rank)
    return worst


def link_sentences(entries, lists, per_entry=6):
    """Attach Tatoeba pairs to entries, best sentence first.

    A good example sentence is short, is built out of words the learner already
    knows, and contains the target word once. Sorting on those three and taking
    the top few per entry keeps the database a tenth of the size it would be if
    every match were kept, with better sentences in it.
    """
    form_index = build_form_index(entries)
    phrases_by_head = collections.defaultdict(list)
    for e in entries:
        if e["kind"] != "word":
            phrases_by_head[e["lemma"].split()[0]].append((e["lemma"], e["id"]))
    candidates = collections.defaultdict(list)
    sentences = {}

    path = os.path.join(CACHE, "sentences.tsv")
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            sid, en, ja = int(parts[0]), parts[1], parts[2]
            tokens = TOKEN_RE.findall(en.lower())
            if not tokens:
                continue
            difficulty = sentence_difficulty(tokens, lists)
            score = (len(tokens), difficulty)
            sentences[sid] = (en, ja)
            hit = False
            counts = collections.Counter(tokens)
            for i, tok in enumerate(tokens):
                if counts[tok] != 1:
                    continue
                for eid in form_index.get(tok, ()):
                    candidates[eid].append((score, sid, tok))
                    hit = True
            lowered = " " + " ".join(tokens) + " "
            for token in set(tokens):
                for phrase, eid in phrases_by_head.get(token, ()):
                    if f" {phrase} " in lowered:
                        candidates[eid].append((score, sid, phrase))
                        hit = True
            if not hit:
                sentences.pop(sid, None)

    links = []
    keep = set()
    for eid, items in candidates.items():
        items.sort()
        for score, sid, surface in items[:per_entry]:
            links.append((sid, eid, surface))
            keep.add(sid)
    kept = {sid: sentences[sid] for sid in keep if sid in sentences}
    print(f"  sentences: {len(kept):,} kept, {len(links):,} links")
    return kept, links


# --------------------------------------------------------------------------
# collocations


def collocations(entries, forms, min_count=4, per_entry=10):
    """Count the word pairs that carry 語法: which verb takes which noun, which
    preposition follows which word.

    Patterns are matched against the parts of speech our own entries declare, so
    a pair is only proposed for words the app actually teaches. Function words
    are excluded on both sides: `spare me` and `abandon it` are grammar, not
    collocation, and a card asking for them teaches nothing.
    """
    pos_of = collections.defaultdict(set)
    id_of = {}
    # `see` and `compare` head Wiktionary's cross-reference boilerplate rather
    # than English prose, and would otherwise outrank every real collocation.
    function_words = set(STOP) | set(DETS) | {
        "see", "cf", "compare", "etc", "ibid", "coordinate", "synonym", "antonym",
        "me", "him", "us", "them", "you", "it", "he", "she", "they", "we", "i",
        "hi", "ok", "yes", "no", "oh", "ah", "mr", "mrs", "ms", "am", "s", "t",
        "thing", "things", "way", "ways", "time", "times", "people", "man",
        "men", "woman", "women", "day", "days", "year", "years", "part", "kind",
    }
    primary_pos = {}
    for e in entries:
        if e["kind"] != "word":
            continue
        pos_of[e["lemma"]].add(e["pos"])
        id_of.setdefault((e["lemma"], e["pos"]), e["id"])
        # Entries arrive in teaching order, so the first one seen for a lemma is
        # the part of speech that lemma usually is. `post office` is then a noun
        # compound rather than a verb taking an object.
        primary_pos.setdefault(e["lemma"], e["pos"])

    def is_content(word, pos):
        return (word not in function_words and len(word) >= 3
                and pos in (pos_of.get(word) or ()))

    unigram = collections.Counter()
    pairs = collections.Counter()
    example_of = {}
    capitalised = collections.Counter()
    total = 0

    def note(kind, head, collocate, phrase, text):
        key = (kind, head, collocate)
        pairs[key] += 1
        if key not in example_of:
            example_of[key] = (phrase, text)

    def feed(text):
        nonlocal total
        # Count lemmas, not surfaces: `made a decision` and `makes a decision`
        # are the same fact about the verb `make`. The surface is kept for
        # display, so the card shows `social media`, not the lemma `medium`.
        raw = RAW_TOKEN_RE.findall(text)
        surface = [t.lower() for t in raw]
        tokens = [forms.get(t, t) for t in surface]
        n = len(tokens)
        total += n
        for i, t in enumerate(tokens):
            unigram[t] += 1
            # Capitalised away from the start of a sentence means a name, and a
            # name is not vocabulary: this is what keeps `harry potter` and
            # `united kingdom` out of the collocation lists.
            if i > 0 and raw[i][:1].isupper():
                capitalised[t] += 1
        for i, a in enumerate(tokens):
            if i + 1 >= n or a in function_words or len(a) < 3:
                continue
            pa = pos_of.get(a)
            if not pa:
                continue
            nxt = tokens[i + 1]
            if nxt in PREPS:
                for pos, kind in (("v", "v+prep"), ("n", "n+prep"), ("adj", "adj+prep")):
                    if pos in pa:
                        note(kind, a, nxt, f"{a} {nxt}", text)
                        break

            gerund = surface[i].endswith("ing") and surface[i] != a
            if primary_pos.get(a) == "adj" and is_content(nxt, "n"):
                note("adj+n", a, nxt, f"{surface[i]} {surface[i + 1]}", text)
            # A gerund in front of a noun is a compound (`swimming pool`), not a
            # verb taking an object, so it is not counted as one.
            if primary_pos.get(a) == "v" and not gerund and is_content(nxt, "n") \
                    and "v" not in (pos_of.get(nxt) or ()):
                note("v+n", a, nxt, f"{surface[i]} {surface[i + 1]}", text)
            if primary_pos.get(a) == "v" and nxt in DETS and i + 2 < n:
                third = tokens[i + 2]
                if is_content(third, "n"):
                    note("v+n", a, third,
                         f"{surface[i]} {surface[i + 1]} {surface[i + 2]}", text)

    for name in ("corpus.txt",):
        path = os.path.join(CACHE, name)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            for line in f:
                feed(line.rstrip("\n"))
    with open(os.path.join(CACHE, "sentences.tsv"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3:
                feed(parts[1])

    print(f"  corpus: {total:,} tokens, {len(pairs):,} candidate pairs")

    def proper_noun(word):
        seen = unigram[word]
        return seen >= 8 and capitalised[word] / seen > 0.55

    best = {}
    for (kind, a, b), count in pairs.items():
        if count < min_count or proper_noun(a) or proper_noun(b):
            continue
        pa, pb = unigram[a], unigram[b]
        if not pa or not pb:
            continue
        pmi = math.log((count / total) / ((pa / total) * (pb / total)))
        if pmi < 1.2:
            continue
        head_pos = "v" if kind.startswith("v") else "adj" if kind.startswith("adj") else "n"
        eid = id_of.get((a, head_pos))
        if eid is None:
            continue
        phrase, example = example_of[(kind, a, b)]
        score = round(pmi * math.log(count), 3)
        # The same pair can be reached by two patterns (a word that is both noun
        # and verb); keep the reading the corpus supports best.
        previous = best.get((eid, b))
        if previous is None or previous[0] < score:
            best[(eid, b)] = (score, kind, a, b, phrase, count, example)

    out = collections.defaultdict(list)
    for (eid, _), item in best.items():
        out[eid].append(item)

    rows = []
    for eid, items in out.items():
        items.sort(reverse=True)
        for score, kind, a, b, phrase, count, example in items[:per_entry]:
            rows.append((eid, kind, a, b, phrase, count, score, example))
    print(f"  collocations: {len(rows):,}")
    return rows


# --------------------------------------------------------------------------
# relations


def surface_rows(entries, forms_by_lemma):
    """Every spelling that should count as "this entry" in a piece of English.

    The reader screen has to decide, for each word of a pasted text, whether the
    learner knows it. That means matching `abandoned`, `abandons` and
    `abandoning` to `abandon`, and `looked up to` to `look up to`, which is what
    this table is for.
    """
    rows = set()
    for entry in entries:
        lemma = entry["lemma"]
        words = lemma.count(" ") + 1
        rows.add((lemma, entry["id"], words))
        if words == 1:
            for _, form in entry["forms"]:
                form = (form or "").lower()
                if form and " " not in form:
                    rows.add((form, entry["id"], 1))
        else:
            head, rest = lemma.split(" ", 1)
            for form in {head} | forms_by_lemma.get(head, set()):
                rows.add((f"{form} {rest}", entry["id"], words))
    print(f"  surfaces: {len(rows):,}")
    return sorted(rows)


CONTENT_POS = ("n", "v", "adj", "adv")


def confusable_pairs(entries, limit_per_entry=4):
    """Words that are one slip apart: concede/precede, adapt/adopt, principal/principle.

    Spelling distance alone is not enough. `there` and `three` are one edit
    apart and nobody mixes them up, because one is a function word you have met
    ten thousand times. A pair is only worth warning about when both halves are
    content words at a level where you are still learning them, and they are
    close enough to misread under time pressure.
    """
    by_key = collections.defaultdict(list)
    for e in entries:
        lemma = e["lemma"]
        if e["kind"] != "word" or len(lemma) < 5 or " " in lemma:
            continue
        if e["pos"] not in CONTENT_POS:
            continue
        # Below B1 a word is met so often that its shape is secure.
        if CEFR_ORDER.get(e["cefr"], 0) < CEFR_ORDER["B1"]:
            continue
        for i in range(len(lemma)):
            by_key[lemma[:i] + lemma[i + 1:]].append(e)

    found = collections.defaultdict(set)
    for bucket in by_key.values():
        if len(bucket) < 2:
            continue
        for i, a in enumerate(bucket):
            for b in bucket[i + 1:]:
                if a["lemma"] == b["lemma"]:
                    continue
                if abs(len(a["lemma"]) - len(b["lemma"])) > 1:
                    continue
                if a["lemma"][:2] != b["lemma"][:2] and a["lemma"][-2:] != b["lemma"][-2:]:
                    continue
                # One of the two words containing the other whole is a suffix
                # pair (`depend`/`depends`), not a confusion.
                if a["lemma"] in b["lemma"] or b["lemma"] in a["lemma"]:
                    continue
                found[a["id"]].add(b["id"])
                found[b["id"]].add(a["id"])

    rows = []
    order = {e["id"]: e["rank"] for e in entries}
    for eid, others in found.items():
        for other in sorted(others, key=lambda x: order.get(x, 10 ** 9))[:limit_per_entry]:
            if eid < other:
                rows.append((eid, other, "confuse"))
    print(f"  confusable pairs: {len(rows):,}")
    return rows


def root_families(entries, min_members=4):
    """Families of words that visibly share a stem.

    Wiktionary's `root` template groups by Proto-Indo-European root, which is the
    only key that puts `perspective` and `conspicuous` together — they share no
    Latin word. But a root like *per- also drags in `fear` and `parent`, and
    being told those are related is trivia, not something you can use.

    So the root does the grouping and the *spelling* does the filtering: for each
    root we find the letter pattern most of its members actually show (duc, spec,
    cept), and keep only the members that contain it. What is left is a family a
    learner can recognise on sight, which is the whole point of teaching roots.
    """
    by_root = collections.defaultdict(list)
    for e in entries:
        etym = e["etym"] or {}
        if etym.get("root"):
            by_root[etym["root"]].append(e)

    rows, assignments = [], {}
    for root, members in sorted(by_root.items()):
        lemmas = {e["lemma"] for e in members if " " not in e["lemma"]}
        if len(lemmas) < min_members:
            continue
        pattern = shared_pattern(lemmas)
        if not pattern:
            continue
        kept = [e for e in members if pattern in e["lemma"]]
        if len({e["lemma"] for e in kept}) < min_members:
            continue
        # The label a learner can read: the Latin or Greek word the family's
        # members actually descend from, with its gloss.
        sources = collections.Counter(
            (e["etym"]["lang"], e["etym"]["form"], e["etym"]["gloss"])
            for e in kept
            if e["etym"].get("form") and e["etym"].get("gloss")
        )
        lang, form, gloss = sources.most_common(1)[0][0] if sources else ("", "", "")
        rid = len(rows) + 1
        rows.append((rid, pattern, root, lang, form, gloss[:80]))
        for e in kept:
            assignments[e["id"]] = rid

    print(f"  root families: {len(rows):,} covering {len(assignments):,} entries")
    return rows, assignments


# Prefixes carry direction, not meaning: every member of the *per- group starts
# with `pro`, and "these 37 words share pro-" teaches nothing. Roots that happen
# to sit word-initially (liber-, econo-) are not on this list and survive.
AFFIXES = frozenset("""
pro pre per con com col cor sub sup super inter intra trans ante anti circum
contra extra infra post retro ultra over under out non semi multi mono poly
auto tele micro macro hyper hypo meta para peri epi endo exo proto pseudo neo
fore counter cross omni pan uni bi tri quad
tion sion ment ness able ible ical ally ance ence ent ant ive ous ize ise
""".split())


def shared_pattern(lemmas, min_share=0.35):
    """The longest run of letters that most of these words have in common."""
    counts = collections.Counter()
    for lemma in lemmas:
        seen = set()
        for size in range(3, 6):
            for i in range(len(lemma) - size + 1):
                seen.add(lemma[i:i + size])
        counts.update(seen)
    threshold = max(3, int(len(lemmas) * min_share))
    best = []
    for pattern, n in counts.items():
        if n < threshold or pattern in AFFIXES:
            continue
        best.append((len(pattern), n, pattern))
    return max(best)[2] if best else ""


def lexical_relations(entries):
    by_lemma = collections.defaultdict(list)
    for e in entries:
        by_lemma[e["lemma"]].append(e["id"])
    rows = set()
    for e in entries:
        for kind, words in (("syn", e["syn"]), ("ant", e["ant"]),
                            ("family", e["derived"][:6] + e["related"][:6])):
            for w in words:
                for other in by_lemma.get(w, ()):
                    if other == e["id"]:
                        continue
                    rows.add((min(e["id"], other), max(e["id"], other), kind))
    print(f"  lexical relations: {len(rows):,}")
    return sorted(rows)


# --------------------------------------------------------------------------
# morphology


# An affix nobody reuses explains nothing: the point of showing `re-` is that it
# does the same job in thirty other words. Below this it is trivia.
MIN_AFFIX_USES = 4


def load_affix_japanese():
    """Japanese for the productive affixes; see tools/affix_ja.json for why."""
    path = os.path.join(HERE, "affix_ja.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return {k: v for k, v in json.load(f).items() if not k.startswith("_")}


def build_morphology(entries, affix_defs, common, affix_ja=None):
    """Cut words into prefix + stem + suffix, and index the affixes by use.

    The decompositions come from Wiktionary's own etymology templates (see
    `step2_wiktionary.pick_affixes`), so nothing here guesses where a word
    breaks. What is decided here is which affixes are worth showing: one that
    appears in three words is a curiosity, not an operator.

    Stems are kept as Wiktionary spells them, and not normalised. `reduce` and
    `conduct` really do descend through different Latin stems (duce, duct); the
    thing that unites them is the Proto-Indo-European root, which `entry.root_id`
    already carries. Flattening the two spellings together here would invent a
    stem that neither word actually shows.
    """
    affix_ja = affix_ja or {}
    uses = collections.Counter()
    cut = {}
    for e in entries:
        parts = e.get("affix")
        if not parts:
            continue
        cut[e["id"]] = parts
        for p in parts:
            if p["kind"] != "stem":
                uses[p["form"]] += 1

    affix_rows, affix_id = [], {}
    for form, count in uses.most_common():
        if count < MIN_AFFIX_USES:
            continue
        found = affix_defs.get(form)
        written = affix_ja.get(form)
        # Something has to be able to say what it does. Wiktionary's own entry
        # is preferred for the English; the Japanese is almost never there (23
        # affixes had any), so it comes from the written table instead.
        if not found and not written:
            continue
        gloss = (found or {}).get("gloss") or []
        ja = [written] if written else order_japanese((found or {}).get("ja") or [], common)
        if not gloss and not ja:
            continue
        affix_id[form] = len(affix_rows) + 1
        affix_rows.append((
            affix_id[form], form, kind_of_affix(form),
            join(gloss[:3]), join(ja[:4]), count,
        ))

    morph_rows = []
    for entry_id, parts in cut.items():
        # A cut whose affixes are all too rare to have a page leaves the word
        # with nothing to point at; it is not worth a row.
        if not any(affix_id.get(p["form"]) for p in parts if p["kind"] != "stem"):
            continue
        for ord_, p in enumerate(parts):
            morph_rows.append((
                entry_id, ord_, p["form"], p["kind"],
                affix_id.get(p["form"], 0), p["gloss"],
            ))
    return affix_rows, morph_rows


def kind_of_affix(form):
    if form.startswith("-") and form.endswith("-"):
        return "interfix"
    return "suffix" if form.startswith("-") else "prefix"


# --------------------------------------------------------------------------
# writing


SCHEMA = """
PRAGMA page_size = 4096;
CREATE TABLE entry (
  id INTEGER PRIMARY KEY,
  lemma TEXT NOT NULL,
  pos TEXT NOT NULL,
  kind TEXT NOT NULL,
  cefr TEXT NOT NULL,
  cefr_est INTEGER NOT NULL,
  rank INTEGER NOT NULL,
  freq INTEGER NOT NULL,
  lists TEXT NOT NULL,
  ipa TEXT NOT NULL,
  forms TEXT NOT NULL,
  root TEXT NOT NULL,
  root_lang TEXT NOT NULL,
  root_gloss TEXT NOT NULL,
  root_id INTEGER NOT NULL,
  ja TEXT NOT NULL
);
CREATE INDEX entry_root ON entry(root_id, rank);
CREATE INDEX entry_lemma ON entry(lemma);
CREATE INDEX entry_rank ON entry(rank);
CREATE INDEX entry_kind ON entry(kind, rank);

CREATE TABLE sense (
  id INTEGER PRIMARY KEY,
  entry_id INTEGER NOT NULL,
  ord INTEGER NOT NULL,
  ja TEXT NOT NULL,
  ja_def TEXT NOT NULL,
  def_en TEXT NOT NULL,
  parent TEXT NOT NULL,
  tags TEXT NOT NULL,
  topics TEXT NOT NULL,
  syn TEXT NOT NULL,
  ant TEXT NOT NULL,
  semcor INTEGER NOT NULL,
  synset TEXT NOT NULL
);
CREATE INDEX sense_entry ON sense(entry_id, ord);

CREATE TABLE sense_example (
  sense_id INTEGER NOT NULL,
  en TEXT NOT NULL,
  ja TEXT NOT NULL
);
CREATE INDEX sense_example_sense ON sense_example(sense_id);

CREATE TABLE sentence (
  id INTEGER PRIMARY KEY,
  en TEXT NOT NULL,
  ja TEXT NOT NULL
);
CREATE TABLE sentence_entry (
  sentence_id INTEGER NOT NULL,
  entry_id INTEGER NOT NULL,
  surface TEXT NOT NULL
);
CREATE INDEX sentence_entry_entry ON sentence_entry(entry_id);

CREATE TABLE collocation (
  id INTEGER PRIMARY KEY,
  entry_id INTEGER NOT NULL,
  pattern TEXT NOT NULL,
  head TEXT NOT NULL,
  collocate TEXT NOT NULL,
  phrase TEXT NOT NULL,
  count INTEGER NOT NULL,
  score REAL NOT NULL,
  example TEXT NOT NULL
);
CREATE INDEX collocation_entry ON collocation(entry_id);

CREATE TABLE relation (
  a INTEGER NOT NULL,
  b INTEGER NOT NULL,
  kind TEXT NOT NULL
);
CREATE INDEX relation_a ON relation(a);
CREATE INDEX relation_b ON relation(b);

CREATE TABLE root (
  id INTEGER PRIMARY KEY,
  pattern TEXT NOT NULL,
  pie TEXT NOT NULL,
  lang TEXT NOT NULL,
  form TEXT NOT NULL,
  gloss TEXT NOT NULL
);

CREATE TABLE surface (
  form TEXT NOT NULL,
  entry_id INTEGER NOT NULL,
  words INTEGER NOT NULL
);
CREATE INDEX surface_form ON surface(form);

-- The pieces words are built from. `affix` is the operator (re-, -tion) with
-- its own meaning; `morph` is one word cut into pieces, in reading order. Two
-- tables rather than one because the app reads them from both ends: down a
-- word to show how it breaks up, and across an affix to show every word it
-- builds. That second direction is the one paper cannot do.
CREATE TABLE affix (
  id INTEGER PRIMARY KEY,
  form TEXT NOT NULL,
  kind TEXT NOT NULL,
  gloss TEXT NOT NULL,
  ja TEXT NOT NULL,
  uses INTEGER NOT NULL
);
CREATE UNIQUE INDEX affix_form ON affix(form);
CREATE INDEX affix_uses ON affix(kind, uses DESC);

CREATE TABLE morph (
  entry_id INTEGER NOT NULL,
  ord INTEGER NOT NULL,
  form TEXT NOT NULL,
  kind TEXT NOT NULL,
  affix_id INTEGER NOT NULL,
  gloss TEXT NOT NULL
);
CREATE INDEX morph_entry ON morph(entry_id, ord);
CREATE INDEX morph_affix ON morph(affix_id);
CREATE INDEX morph_stem ON morph(form, kind);

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


def join(items):
    return "|".join(str(i).replace("|", "/") for i in items)


def write_db(path, entries, sentences, links, colls, relations, roots, root_ids,
             surfaces, common=(), affix_rows=(), morph_rows=()):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.executescript(SCHEMA)

    sense_id = 0
    sense_rows, example_rows = [], []
    entry_rows = []
    for e in entries:
        etym = e["etym"] or {}
        headline = []
        for s in e["senses"]:
            for ja in order_japanese(s["ja"], common):
                if ja not in headline:
                    headline.append(ja)
            if len(headline) >= 3:
                break
        entry_rows.append((
            e["id"], e["lemma"], e["pos"], e["kind"], e["cefr"], e["cefr_est"],
            e["rank"], e["freq"], join(e["lists"]), e["ipa"],
            join(f"{label}:{form}" for label, form in e["forms"]),
            etym.get("form", ""), etym.get("lang", ""), etym.get("gloss", ""),
            root_ids.get(e["id"], 0), join(headline[:3]),
        ))
        for s in e["senses"]:
            sense_id += 1
            sense_rows.append((
                sense_id, e["id"], s["ord"], join(order_japanese(s["ja"], common)),
                s["ja_def"], s["gloss"],
                s["parent"], join(s["tags"]), join(s["topics"]), join(s["syn"]),
                join(s["ant"]), s["semcor"], s["synset"],
            ))
            for en, ja in s["ex"]:
                example_rows.append((sense_id, en, ja))

    db.executemany("INSERT INTO entry VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", entry_rows)
    db.executemany("INSERT INTO sense VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", sense_rows)
    db.executemany("INSERT INTO sense_example VALUES (?,?,?)", example_rows)
    db.executemany("INSERT INTO sentence VALUES (?,?,?)",
                   [(sid, en, ja) for sid, (en, ja) in sorted(sentences.items())])
    db.executemany("INSERT INTO sentence_entry VALUES (?,?,?)", links)
    db.executemany("INSERT INTO collocation (entry_id,pattern,head,collocate,phrase,"
                   "count,score,example) VALUES (?,?,?,?,?,?,?,?)", colls)
    db.executemany("INSERT INTO relation VALUES (?,?,?)", relations)
    db.executemany("INSERT INTO root VALUES (?,?,?,?,?,?)", roots)
    db.executemany("INSERT INTO surface VALUES (?,?,?)", surfaces)
    db.executemany("INSERT INTO affix VALUES (?,?,?,?,?,?)", affix_rows)
    db.executemany("INSERT INTO morph VALUES (?,?,?,?,?,?)", morph_rows)
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("entries", str(len(entry_rows))),
        ("senses", str(len(sense_rows))),
        ("sentences", str(len(sentences))),
        ("collocations", str(len(colls))),
        ("surfaces", str(len(surfaces))),
        ("roots", str(len(roots))),
        ("affixes", str(len(affix_rows))),
        ("morphs", str(len({m[0] for m in morph_rows}))),
        ("schema", "1"),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()
    return len(entry_rows), len(sense_rows), len(example_rows)


def main():
    print("loading cache")
    lists, wikt, wn, affix_defs = load_cache()
    common = load_common_japanese()
    print(f"  {len(common):,} everyday Japanese words for ordering glosses")
    print(f"  {len(lists):,} candidates, {len(wikt):,} Wiktionary entries, "
          f"{len(wn):,} WordNet keys, {len(affix_defs):,} affixes")

    print("counting phrases in the corpus")
    forms = inflection_map(wikt)
    phrase_counts = count_phrases(
        [e["w"] for e in wikt if " " in e["w"]], forms)
    print(f"  {sum(1 for v in phrase_counts.values() if v >= 2):,} phrases seen twice or more")

    print("selecting entries")
    entries = select_entries(lists, wikt, wn, phrase_counts)
    borrow_japanese(entries)
    before = len(entries)
    entries = [e for e in entries if teachable(e)]
    for i, e in enumerate(entries, 1):
        e["rank"] = i
        e["id"] = i
    if before != len(entries):
        print(f"  dropped {before - len(entries):,} entries with no usable meaning")
    by_kind = collections.Counter(e["kind"] for e in entries)
    by_cefr = collections.Counter(e["cefr"] for e in entries)
    print(f"  {len(entries):,} entries {dict(by_kind)}")
    print(f"  levels {dict(sorted(by_cefr.items()))}")

    print("linking sentences")
    sentences, links = link_sentences(entries, lists)
    print("counting collocations")
    colls = collocations(entries, forms)
    print("building relations")
    root_rows, root_ids = root_families(entries)
    relations = confusable_pairs(entries) + lexical_relations(entries)
    print("cutting words into morphemes")
    affix_rows, morph_rows = build_morphology(entries, affix_defs, common,
                                              load_affix_japanese())
    print(f"  {len(affix_rows):,} affixes worth a page, "
          f"{len({m[0] for m in morph_rows}):,} words cut")

    os.makedirs(ASSETS, exist_ok=True)
    tmp = os.path.join(CACHE, "content.db")
    surfaces = surface_rows(entries, forms_of_lemma(wikt))
    n_entry, n_sense, n_ex = write_db(tmp, entries, sentences, links, colls,
                                      relations, root_rows, root_ids, surfaces, common,
                                      affix_rows, morph_rows)
    # Not `.gz`: the Android asset merger expands assets with that extension.
    out = os.path.join(ASSETS, "content.dbz")
    with open(tmp, "rb") as src, gzip.open(out, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst, 1 << 20)
    raw_mb = os.path.getsize(tmp) / 1e6
    gz_mb = os.path.getsize(out) / 1e6
    print(f"\n{n_entry:,} entries / {n_sense:,} senses / {n_ex:,} sense examples")
    print(f"{raw_mb:.1f} MB -> {gz_mb:.1f} MB gzipped at {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
