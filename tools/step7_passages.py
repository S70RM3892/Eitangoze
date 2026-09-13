#!/usr/bin/env python3
"""Collect the English passages the app ships, by genre and by level.

Output: cache/passages.jsonl   (one object per passage)

The reader is not asked to paste anything. Pasting is where almost everybody
stops, so the passages are in the app from the first launch, and the app picks
which one to hand you from your own coverage (see docs/DESIGN.md ⑧). That only
works if the library is wide enough that every taste is already in it, so the
genres below are chosen to span what a Japanese university exam actually sets —
京大 has run 科学史, 大気生物学, 文明論 — plus the narrative and essay prose that
科学 alone would miss.

Everything here is redistributable. Three sources carry all thirteen genres:

  MediaWiki    en/simple Wikipedia, Wikinews, Wikisource — CC BY-SA 4.0
               (Wikinews CC BY 2.5), plain text straight from the API
  Gutenberg    public domain literature and essays, via the Gutendex index
  PMC          the open-access subset of PubMed Central, filtered to the
               articles whose own <license> element says CC BY or CC0

Deliberately not wired, and why — each would need scraping rather than an API,
and a passage nobody can check the provenance of is worse than one fewer genre:

  VOA Learning English  public domain, but the RSS endpoints are opaque ids
                        with no documented index
  NASA / NOAA           the WordPress REST output is mostly site chrome, and
                        the article body has to be dug out of it
  World Bank OKR        CC BY, but the corpus is PDF-first

Written one passage at a time, and safe to interrupt. The first full run took
over an hour and wrote nothing when it was stopped, because it held everything
in memory until the end — an hour of public API calls thrown away, and worse,
no way to tell how far it had got. Every passage is now appended and flushed as
it arrives, and re-running skips what is already on disk.

Usage:  python3 tools/step7_passages.py [--target N] [--genre NAME] [--restart]
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")

USER_AGENT = "Eitangoze/1.0 (open-data vocabulary app; +https://github.com/S70RM3892/Eitangoze)"

# A passage is one exam reading in length. 京大 2026 set 730 and 580 words, so
# anything shorter is not the exercise and anything much longer is two of them.
MIN_WORDS = 420
MAX_WORDS = 1100
# Simple English articles are written short on purpose; holding them to the
# length of an exam passage leaves one article in the whole encyclopaedia.
PLAIN_MIN_WORDS = 220

# Wikipedia is an encyclopaedia, so its articles arrive with apparatus that is
# not prose and must not be counted as reading.
SECTION_STOP = {
    "see also", "references", "further reading", "external links", "notes",
    "bibliography", "sources", "citations", "footnotes", "gallery",
}


# Everything goes through one throttle. These are public APIs run as a service
# to everyone, and the first bulk run answered 429 to every Wikimedia request
# for a while afterwards — which does not look like rate limiting in the logs,
# it looks like the source being empty. One request at a time, paced.
MIN_INTERVAL = 1.1
_last_request = [0.0]


def get(url, tries=4):
    for attempt in range(tries):
        wait = MIN_INTERVAL - (time.monotonic() - _last_request[0])
        if wait > 0:
            time.sleep(wait)
        _last_request[0] = time.monotonic()
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 429 and attempt < tries - 1:
                # The server said how long to wait; believe it.
                retry_after = error.headers.get("Retry-After")
                delay = int(retry_after) if (retry_after or "").isdigit() else 0
                time.sleep(min(max(delay, 10 * (attempt + 1)), 120))
                continue
            if attempt == tries - 1:
                print(f"    ! HTTP {error.code} {url[:86]}", file=sys.stderr)
                return None
            time.sleep(3 * (attempt + 1))
        except Exception as error:  # noqa: BLE001 - any network failure retries
            if attempt == tries - 1:
                print(f"    ! {type(error).__name__} {url[:86]}", file=sys.stderr)
                return None
            time.sleep(3 * (attempt + 1))
    return None


def get_json(url, tries=3):
    raw = get(url, tries)
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        return None


def words(text):
    return len(text.split())


# --------------------------------------------------------------------------
# MediaWiki


def wiki_api(site, **params):
    params.setdefault("action", "query")
    params.setdefault("format", "json")
    return get_json(f"https://{site}/w/api.php?" + urllib.parse.urlencode(params))


def category_members(site, category, namespace, limit, pages=4):
    """Members of a category in one namespace, following continuations.

    `cmlimit` caps how many members the server *scans*, not how many come back
    after `cmnamespace` filters them. Asking for 30 subcategories of a category
    whose first 30 members are all articles returns an empty list and a
    continuation token — which reads exactly like "this category has no
    subcategories" and quietly halves the library.
    """
    out = []
    params = dict(
        list="categorymembers", cmtitle=f"Category:{category}",
        cmlimit="500", cmnamespace=namespace,
    )
    for _ in range(pages):
        data = wiki_api(site, **params)
        if not data:
            break
        out += [m["title"] for m in
                (data.get("query", {}).get("categorymembers") or [])]
        token = (data.get("continue") or {}).get("cmcontinue")
        if not token or len(out) >= limit:
            break
        params["cmcontinue"] = token
    return out[:limit]


def category_titles(site, category, limit=400, depth=1):
    """Article titles in a category and, one level down, in its subcategories.

    A single category is nowhere near enough. `Atmospheric sciences` lists 39
    articles directly, and most of those are stubs or list pages that the length
    filter then throws out — the first full run came back with 41 science
    passages against a budget of 214. Its ten subcategories are where the actual
    articles are.
    """
    seen, out = set(), []
    frontier = [(category, 0)]
    while frontier and len(out) < limit:
        name, level = frontier.pop(0)
        if name in seen:
            continue
        seen.add(name)
        for title in category_members(site, name, "0", 100):
            if title not in seen:
                seen.add(title)
                out.append(title)
        if level < depth:
            for sub in category_members(site, name, "14", 30):
                frontier.append((sub.split(":", 1)[-1], level + 1))
    return out[:limit]


def clean_wiki(text):
    """Prose only: drop the apparatus sections and the heading lines."""
    kept = []
    for block in text.split("\n"):
        heading = re.fullmatch(r"=+\s*(.+?)\s*=+", block.strip())
        if heading:
            if heading.group(1).strip().lower() in SECTION_STOP:
                break
            continue
        block = block.strip()
        # A line with no sentence-ending punctuation is a caption or a list row.
        if len(block.split()) >= 8 and re.search(r"[.!?]\s*$", block):
            kept.append(block)
    return "\n\n".join(kept)


def wiki_passages(site, titles, genre, licence, batch=12, minimum=MIN_WORDS):
    out = []
    for start in range(0, len(titles), batch):
        chunk = titles[start:start + batch]
        data = wiki_api(
            site, prop="extracts", explaintext="1", exlimit=str(len(chunk)),
            titles="|".join(chunk), redirects="1",
        )
        for page in ((data or {}).get("query", {}).get("pages") or {}).values():
            extract = page.get("extract") or ""
            text = clean_wiki(extract)
            if not minimum <= words(text) <= MAX_WORDS:
                # Long articles are still usable: take the opening, which is the
                # part written as continuous prose rather than as a table.
                if words(text) > MAX_WORDS:
                    text = trim_to(text, MAX_WORDS)
                if not minimum <= words(text) <= MAX_WORDS:
                    continue
            out.append({
                "title": page.get("title", ""),
                "text": text,
                "genre": genre,
                "source": site,
                "url": f"https://{site}/wiki/" + urllib.parse.quote(
                    page.get("title", "").replace(" ", "_")),
                "license": licence,
            })
    return out


def trim_to(text, limit):
    """Whole paragraphs up to a word budget."""
    kept, total = [], 0
    for paragraph in text.split("\n\n"):
        n = words(paragraph)
        if total + n > limit:
            break
        kept.append(paragraph)
        total += n
    return "\n\n".join(kept)


# --------------------------------------------------------------------------
# Project Gutenberg


GUTENBERG_SKIP = re.compile(
    r"\*\*\*\s*(START|END) OF (THE|THIS) PROJECT GUTENBERG", re.IGNORECASE)


def gutenberg_ids(topic, limit=40):
    out = []
    url = ("https://gutendex.com/books?languages=en&sort=popular&topic="
           + urllib.parse.quote(topic))
    while url and len(out) < limit:
        data = get_json(url)
        if not data:
            break
        for book in data.get("results", []):
            out.append((book["id"], book.get("title", "")))
            if len(out) >= limit:
                break
        url = data.get("next")
        time.sleep(0.3)
    return out


def gutenberg_passages(topic, genre, per_book=3, limit=40):
    out = []
    for book_id, title in gutenberg_ids(topic, limit):
        raw = get(f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt")
        if raw is None:
            continue
        text = raw.decode("utf-8", "replace")
        # Between the Gutenberg banners is the work itself.
        parts = GUTENBERG_SKIP.split(text)
        body = max(text.split("***"), key=len) if len(parts) < 2 else text
        marks = [m.end() for m in GUTENBERG_SKIP.finditer(text)]
        if len(marks) >= 1:
            end = GUTENBERG_SKIP.search(text, marks[0])
            body = text[marks[0]:end.start() if end else len(text)]
        paragraphs = [" ".join(p.split()) for p in re.split(r"\n\s*\n", body)]
        paragraphs = [p for p in paragraphs if words(p) >= 25 and p[:1].isupper()]
        # Consecutive paragraphs, so a passage is a continuous piece of the
        # book rather than a collage of unrelated pages.
        taken, chunk, total = 0, [], 0
        for paragraph in paragraphs:
            chunk.append(paragraph)
            total += words(paragraph)
            if total >= MIN_WORDS:
                out.append({
                    "title": f"{title} ({taken + 1})",
                    "text": "\n\n".join(chunk),
                    "genre": genre,
                    "source": "gutenberg.org",
                    "url": f"https://www.gutenberg.org/ebooks/{book_id}",
                    "license": "public domain",
                })
                taken, chunk, total = taken + 1, [], 0
                if taken >= per_book:
                    break
        time.sleep(0.5)
    return out


# --------------------------------------------------------------------------
# PubMed Central, open-access subset


EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"
# Only these two let the passage ship with the app. Every other CC variant in
# the subset carries NC or ND, which redistribution inside an APK would break.
PMC_OK_LICENCE = re.compile(r"creativecommons\.org/(licenses/by/|publicdomain/zero)")
XLINK_HREF = "{http://www.w3.org/1999/xlink}href"


def licence_url(article):
    """The licence an article states, wherever it happened to state it.

    PMC writes this three ways. Most articles put the URL in the *text* of an
    `<ali:license_ref>` element; some hang it on `xlink:href` of `<license>`;
    some only have it on an `<ext-link>` inside the licence paragraph. Reading
    only the second of those found a valid licence on nothing at all, which is
    how a source that works ends up looking like a source that is empty.
    """
    licence = article.find(".//license")
    if licence is None:
        return ""
    for node in licence.iter():
        href = node.get(XLINK_HREF) or ""
        if "creativecommons.org" in href:
            return href
        text = (node.text or "").strip()
        if text.startswith("http") and "creativecommons.org" in text:
            return text
    return ""


def pmc_ids(query, limit=60):
    url = (f"{EUTILS}/esearch.fcgi?db=pmc&retmode=json&retmax={limit}&term="
           + urllib.parse.quote(f'"open access"[filter] AND ({query})'))
    data = get_json(url)
    return ((data or {}).get("esearchresult", {}).get("idlist")) or []


def pmc_passages(query, genre, limit=60):
    import xml.etree.ElementTree as ET

    ids = pmc_ids(query, limit)
    out = []
    for start in range(0, len(ids), 8):
        batch = ",".join(ids[start:start + 8])
        raw = get(f"{EUTILS}/efetch.fcgi?db=pmc&retmode=xml&id={batch}")
        if raw is None:
            continue
        try:
            root = ET.fromstring(raw)
        except ET.ParseError:
            continue
        for article in root.iter("article"):
            href = licence_url(article)
            if not PMC_OK_LICENCE.search(href):
                continue
            title_el = article.find(".//article-title")
            title = " ".join("".join(title_el.itertext()).split()) if title_el is not None else ""
            pmcid = ""
            for ident in article.iter("article-id"):
                if ident.get("pub-id-type") == "pmc":
                    pmcid = ident.text or ""
            body = article.find(".//body")
            if body is None:
                continue
            # Paragraphs of the article's own prose. Figure captions, tables and
            # reference lists are different kinds of writing and are not read
            # end to end, so they are not part of a reading passage.
            # Every paragraph of the body, minus the ones inside a figure or a
            # table. Looking only under <sec> found nothing at all: plenty of
            # articles put their prose straight in <body>, and nested sections
            # would have been counted twice.
            skip = set()
            for wrapper in body.iter():
                if wrapper.tag in ("fig", "table-wrap", "caption", "boxed-text"):
                    for para in wrapper.iter("p"):
                        skip.add(id(para))
            paragraphs = []
            for para in body.iter("p"):
                if id(para) in skip:
                    continue
                line = " ".join("".join(para.itertext()).split())
                if words(line) >= 25:
                    paragraphs.append(line)
            text = trim_to("\n\n".join(paragraphs), MAX_WORDS)
            if words(text) < MIN_WORDS:
                continue
            out.append({
                "title": title,
                "text": text,
                "genre": genre,
                "source": "PubMed Central",
                "url": f"https://www.ncbi.nlm.nih.gov/pmc/articles/PMC{pmcid}/",
                "license": "CC BY 4.0" if "licenses/by/" in href else "CC0 1.0",
            })
    return out


# --------------------------------------------------------------------------
# the genres


WIKI = "en.wikipedia.org"
SIMPLE = "simple.wikipedia.org"
CC_BY_SA = "CC BY-SA 4.0"

GENRES = [
    ("science", "自然科学", "wiki", [
        "Atmospheric sciences", "Meteorology", "Ecology", "Evolutionary biology",
        "Geology", "Oceanography", "Astronomy", "Physical geography",
        "Climatology", "Paleontology",
    ]),
    ("medicine", "医学・脳科学・心理", "pmc", [
        "memory[title] OR sleep[title]", "attention[title] OR perception[title]",
        "immunity[title] OR vaccine[title]", "neuroscience[title] OR cognition[title]",
    ]),
    ("technology", "技術・AI・情報", "wiki", [
        "Artificial intelligence", "Computer science", "Cryptography",
        "History of computing", "Machine learning", "Internet",
    ]),
    ("environment", "環境・気候", "wiki", [
        "Climate change", "Environmental science", "Conservation biology",
        "Renewable energy", "Pollution", "Sustainability",
    ]),
    ("economy", "経済・開発", "wiki", [
        "Economics", "International development", "Macroeconomics",
        "Economic history", "Globalization", "Labour economics",
    ]),
    ("history", "歴史・文明論", "wiki", [
        "Ancient history", "History of science", "Archaeology",
        "Civilizations", "Historiography", "History of ideas",
    ]),
    ("philosophy", "哲学・思想", "wiki", [
        "Epistemology", "Ethics", "Philosophy of mind", "Metaphysics",
        "Philosophy of science", "Political philosophy",
    ]),
    ("language", "言語・教育", "wiki", [
        "Linguistics", "Language acquisition", "Sociolinguistics",
        "Educational psychology", "Writing systems", "Semantics",
    ]),
    ("arts", "芸術・文化", "wiki", [
        "Art history", "Music theory", "Architecture", "Cultural anthropology",
        "Film theory", "Aesthetics",
    ]),
    ("society", "社会・メディア・政治", "wiki", [
        "Sociology", "Mass media", "Political science", "Human rights",
        "Journalism", "Urban studies",
    ]),
    ("news", "時事", "wikinews", ["Politics and conflicts", "Science and technology"]),
    ("literature", "物語・文学", "gutenberg", ["Fiction", "Adventure", "Science Fiction"]),
    ("essay", "随筆・演説", "gutenberg", ["Essays", "Biography", "Philosophy"]),
    ("plain", "やさしい英語", "simple", [
        "Science", "History", "Technology", "Nature", "Culture",
    ]),
]


def collect(genre_id, label, kind, seeds, budget, seen=()):
    print(f"  {genre_id} ({label}) — want {budget} more", flush=True)
    got = []
    for seed in seeds:
        if len(got) >= budget:
            break
        want = budget - len(got)
        if kind == "wiki":
            got += wiki_passages(WIKI, category_titles(WIKI, seed)[:want * 3],
                                 genre_id, CC_BY_SA)[:want]
        elif kind == "simple":
            got += wiki_passages(SIMPLE, category_titles(SIMPLE, seed)[:want * 6],
                                 genre_id, CC_BY_SA, minimum=PLAIN_MIN_WORDS)[:want]
        elif kind == "wikinews":
            site = "en.wikinews.org"
            got += wiki_passages(site, category_titles(site, seed)[:want * 4],
                                 genre_id, "CC BY 2.5")[:want]
        elif kind == "gutenberg":
            got += gutenberg_passages(seed, genre_id, limit=max(8, want // 3))[:want]
        elif kind == "pmc":
            got += pmc_passages(seed, genre_id, limit=max(10, want))[:want]
        got = [p for p in got if p["url"] + p["title"] not in seen]
        print(f"    {seed[:44]:46s} → {len(got)}", flush=True)
    return got[:budget]


def load_existing(path):
    """What is already collected, so a second run adds rather than repeats."""
    seen, by_genre = set(), {}
    if not os.path.exists(path):
        return seen, by_genre
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                passage = json.loads(line)
            except ValueError:
                continue
            seen.add(passage["url"] + passage["title"])
            by_genre[passage["genre"]] = by_genre.get(passage["genre"], 0) + 1
    return seen, by_genre


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=int, default=8000,
                        help="how many passages to collect in total")
    parser.add_argument("--genre", default=None, help="collect only this genre id")
    parser.add_argument("--restart", action="store_true",
                        help="throw away what is already collected and start over")
    args = parser.parse_args()

    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, "passages.jsonl")
    if args.restart and os.path.exists(path):
        os.remove(path)

    genres = [g for g in GENRES if args.genre in (None, g[0])]
    budget = max(1, args.target // max(1, len(genres)))
    seen, have = load_existing(path)
    if seen:
        print(f"already collected {len(seen):,}: "
              + "  ".join(f"{k} {v}" for k, v in sorted(have.items())))

    added = 0
    # Appended and flushed one passage at a time. An interrupted run keeps
    # everything it had already fetched, and re-running continues from there.
    with open(path, "a", encoding="utf-8") as dst:
        for genre_id, label, kind, seeds in genres:
            done = have.get(genre_id, 0)
            if done >= budget:
                print(f"  {genre_id} ({label}) — already {done}, skipping")
                continue
            for passage in collect(genre_id, label, kind, seeds, budget - done, seen):
                key = passage["url"] + passage["title"]
                if key in seen:
                    continue
                seen.add(key)
                dst.write(json.dumps(passage, ensure_ascii=False) + "\n")
                dst.flush()
                added += 1

    total, by_genre = load_existing(path)
    print(f"\nadded {added:,}; library now {len(total):,} passages")
    for genre_id, count in sorted(by_genre.items(), key=lambda kv: -kv[1]):
        print(f"  {genre_id:12s} {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
