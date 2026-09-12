#!/usr/bin/env bash
# Downloads every source corpus the content database is built from.
#
# Everything here is redistributable under an open licence; see SOURCES.md for
# the terms and the attribution that ships inside the app. Output lands in
# tools/raw/, which is not tracked by git (the sources total ~3.5 GB).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw
cd raw

get() { # get <file> <url>
  if [ -s "$1" ]; then echo "have $1"; return; fi
  echo "fetching $1"
  curl -sSfL --retry 3 --retry-delay 2 -o "$1.part" "$2"
  mv "$1.part" "$1"
}

# --- English Wiktionary, machine readable (senses, examples, Japanese
#     translations, IPA, etymology, inflections, idioms, phrasal verbs) ---
get kaikki-en.jsonl https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl

# --- Japanese WordNet: synset-aligned Japanese lemmas, definitions, examples ---
get wnjpn.db.gz https://github.com/bond-lab/wnja/releases/download/v1.1/wnjpn.db.gz
[ -s wnjpn.db ] || gunzip -k wnjpn.db.gz

# --- Princeton WordNet 3.0: sense order and SemCor tag counts (index.sense) ---
get WNdb-3.0.tar.gz https://wordnetcode.princeton.edu/3.0/WNdb-3.0.tar.gz
[ -s dict/index.sense ] || tar xzf WNdb-3.0.tar.gz

# --- Tatoeba: English/Japanese sentence pairs ---
get eng_sentences.tsv.bz2   https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
get jpn_sentences.tsv.bz2   https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2
get jpn-eng_links.tsv.bz2   https://downloads.tatoeba.org/exports/per_language/jpn/jpn-eng_links.tsv.bz2

# --- Frequency / level lists ---
get NGSL_12_stats.csv                 https://www.newgeneralservicelist.com/s/NGSL_12_stats.csv
get NGSL_12_lemmatized_for_teaching.csv https://www.newgeneralservicelist.com/s/NGSL_12_lemmatized_for_teaching.csv
get NAWL_12_with_en_definitions.csv   https://www.newgeneralservicelist.com/s/NAWL_12_with_en_definitions.csv
get NAWL_12_lemmatized_for_teaching.csv https://www.newgeneralservicelist.com/s/NAWL_12_lemmatized_for_teaching.csv
get TSL_12_lemmatized_for_teaching.csv  https://www.newgeneralservicelist.com/s/TSL_12_lemmatized_for_teaching.csv
get cefrj.zip                         https://www.cefr-j.org/data/CEFRJ_wordlist_ver1.6.zip
[ -s "CEFR-J Wordlist Ver1.6.xlsx" ] || unzip -o -q cefrj.zip
get enwiki-word-frequency.txt https://raw.githubusercontent.com/IlyaSemenov/wikipedia-word-frequency/master/results/enwiki-2023-04-13.txt

# --- JMdict (Japanese-English dictionary), used as a Japanese gloss fallback ---
get JMdict_e.gz http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz

echo "all sources present in $(pwd)"
