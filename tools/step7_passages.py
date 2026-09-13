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

Usage:  python3 tools/step7_passages.py [--target N] [--genre NAME]
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")

USER_AGENT = "Eitangoze/1.0 (open-data vocabulary app; +https://github.com/S70RM3892/Eitangoze)"

# A passage is one exam reading in length. 京大 2026 set 730 and 580 words, so
# anything shorter is not the exercise and anything much longer is two of them.
MIN_WORDS = 420
MAX_WORDS = 1100

# Wikipedia is an encyclopaedia, so its articles arrive with apparatus that is
# not prose and must not be counted as reading.
SECTION_STOP = {
    "see also", "references", "further reading", "external links", "notes",
    "bibliography", "sources", "citations", "footnotes", "gallery",
}


def get(url, tries=3):
    for attempt in range(tries):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 - any network failure retries
            if attempt == tries - 1:
                print(f"    ! {type(error).__name__} {url[:90]}", file=sys.stderr)
                return None
            time.sleep(2 ** attempt)
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


def category_titles(site, category, limit=120):
    """Article titles in a category, one level deep."""
    out = []
    data = wiki_api(
        site, list="categorymembers", cmtitle=f"Category:{category}",
        cmlimit=str(limit), cmnamespace="0",
    )
    for member in ((data or {}).get("query", {}).get("categorymembers") or []):
        out.append(member["title"])
    return out


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


def wiki_passages(site, titles, genre, licence, batch=12):
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
            if not MIN_WORDS <= words(text) <= MAX_WORDS:
                # Long articles are still usable: take the opening, which is the
                # part written as continuous prose rather than as a table.
                if words(text) > MAX_WORDS:
                    text = trim_to(text, MAX_WORDS)
                if not MIN_WORDS <= words(text) <= MAX_WORDS:
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
        time.sleep(0.3)
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
            licence = article.find(".//license")
            href = (licence.get("{http://www.w3.org/1999/xlink}href")
                    if licence is not None else None) or ""
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
            paragraphs = []
            for parent in body.iter("sec"):
                for para in parent.findall("p"):
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
        time.sleep(0.4)
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


def collect(genre_id, label, kind, seeds, budget):
    print(f"  {genre_id} ({label})", flush=True)
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
                                 genre_id, CC_BY_SA)[:want]
        elif kind == "wikinews":
            site = "en.wikinews.org"
            got += wiki_passages(site, category_titles(site, seed)[:want * 4],
                                 genre_id, "CC BY 2.5")[:want]
        elif kind == "gutenberg":
            got += gutenberg_passages(seed, genre_id, limit=max(8, want // 3))[:want]
        elif kind == "pmc":
            got += pmc_passages(seed, genre_id, limit=max(10, want))[:want]
        print(f"    {seed[:44]:46s} → {len(got)}", flush=True)
    return got[:budget]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=int, default=3000,
                        help="how many passages to collect in total")
    parser.add_argument("--genre", default=None, help="collect only this genre id")
    args = parser.parse_args()

    os.makedirs(CACHE, exist_ok=True)
    genres = [g for g in GENRES if args.genre in (None, g[0])]
    budget = max(1, args.target // max(1, len(genres)))

    out, seen = [], set()
    for genre_id, label, kind, seeds in genres:
        for passage in collect(genre_id, label, kind, seeds, budget):
            key = passage["url"] + passage["title"]
            if key in seen:
                continue
            seen.add(key)
            out.append(passage)

    path = os.path.join(CACHE, "passages.jsonl")
    with open(path, "w", encoding="utf-8") as dst:
        for passage in out:
            dst.write(json.dumps(passage, ensure_ascii=False) + "\n")

    by_genre = {}
    for passage in out:
        by_genre[passage["genre"]] = by_genre.get(passage["genre"], 0) + 1
    print(f"\n{len(out):,} passages, "
          f"{sum(words(p['text']) for p in out):,} running words")
    for genre_id, count in sorted(by_genre.items(), key=lambda kv: -kv[1]):
        print(f"  {genre_id:12s} {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
