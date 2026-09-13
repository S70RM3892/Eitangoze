#!/usr/bin/env python3
"""Parse every passage once, here, so the phone never has to.

Input : cache/passages.jsonl
Output: cache/syntax.jsonl   (one object per passage, with its sentences)

Folding a long sentence down to its skeleton — the first step of 京大's
下線部和訳 — needs a dependency tree. Shipping a parser would mean shipping a
model; parsing on the phone would mean waiting for it. Neither is necessary,
because the passages are fixed at build time: they are parsed once and the
trees ride along in the database.

Two parsers, and only sentences they agree on are offered as syntax practice.
That is not belt and braces. Parser accuracy is quoted on news text, and it
falls exactly where this app needs it most — the long subordinated academic
sentence. On the sentence used as the worked example in docs/DESIGN.md:

    Although the evidence that had been gathered over the preceding decades
    suggested otherwise, most researchers continued to assume that ...

  en_core_web_sm   evidence → nsubj of "continued"   suggested → acl of "decades"   WRONG
  en_core_web_trf  evidence → nsubj of "suggested"   suggested → advcl of "continued"  right

A tree that wrong would teach the opposite of the lesson. So the transformer
parses, the small model checks, and a sentence they disagree about is still
read — it is simply never folded.
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")

# What may be folded away. Every one of these heads a subtree that modifies
# something else, so removing it leaves a shorter sentence that is still a
# sentence — which is exactly the move a reader makes to find the main clause.
FOLDABLE = {
    "acl", "relcl", "acl:relcl",   # 関係節・分詞句
    "advcl",                        # 副詞節
    "prep", "obl", "nmod",          # 前置詞句
    "appos",                        # 同格
    "ccomp", "xcomp",               # 補文
    "advmod",                       # 副詞（単独でも邪魔になる）
    "conj",                         # 等位の後ろ側
}
# Folding one word gains nothing and just makes the sentence look punctured.
MIN_FOLD = 3

# The pieces of the main clause, labelled for the skeleton view.
ROLES = {
    "nsubj": "S", "nsubjpass": "S", "csubj": "S",
    "ROOT": "V",
    "dobj": "O", "obj": "O", "iobj": "O", "ccomp": "O", "xcomp": "O",
    "attr": "C", "acomp": "C", "oprd": "C",
}


def subtree_span(token, base=0):
    """The span a subtree covers, in indices relative to [base].

    Everything stored is sentence-relative — the app receives one sentence at a
    time and has no document to count from — so the base has to be subtracted
    here rather than anywhere downstream.
    """
    indices = [t.i - base for t in token.subtree]
    return min(indices), max(indices)


def folds_of(doc_sentence):
    """Foldable subtrees, widest first so the app can collapse by depth."""
    out = []
    base = doc_sentence.start
    root = doc_sentence.root
    for token in doc_sentence:
        if token is root or token.dep_ not in FOLDABLE:
            continue
        start, end = subtree_span(token, base)
        if end - start + 1 < MIN_FOLD:
            continue
        # Never fold something containing the main verb: the skeleton has to
        # survive every fold, or the exercise destroys its own answer.
        if start <= root.i - base <= end:
            continue
        out.append({"start": start, "end": end, "label": token.dep_})
    out.sort(key=lambda f: (f["start"] - f["end"], f["start"]))
    return out


def roles_of(doc_sentence):
    """S / V / O / C on the main clause only."""
    base = doc_sentence.start
    root = doc_sentence.root
    out = {root.i - base: "V"}
    for child in root.children:
        role = ROLES.get(child.dep_)
        if role and role != "V":
            out.setdefault(child.i - base, role)
    return out


def spans_of(doc_sentence):
    """Every subtree in the sentence, as a set of (start, end) token spans."""
    base = doc_sentence.start
    return {subtree_span(token, base) for token in doc_sentence}


def offsets_of(doc_sentence):
    return tuple((token.idx, token.idx + len(token.text)) for token in doc_sentence)


def confirm(sentence, folds, roles, other):
    """Keep only what the second parser also sees.

    The check is per *fold*, not per sentence, because whole-tree comparison
    rejects almost everything for reasons that have nothing to do with folding:
    measured over 207 sentences the two parsers matched exactly 35 times, and
    the disagreements were led by `prep` (111), `punct` (103), `amod` (75) and
    `det` (46) — where a preposition phrase hangs, and what the full stop
    attaches to. None of that moves the boundary of a clause.

    What has to agree is what the app is about to draw: where the main verb is,
    and where each collapsible subtree starts and ends. A fold both parsers
    bracket the same way is safe to offer even if they label it differently;
    one only the transformer sees is dropped, and the sentence keeps the rest.
    """
    if other is None or offsets_of(sentence) != offsets_of(other):
        # Different tokenisation: nothing can be lined up, so nothing is
        # confirmed. The sentence is still perfectly readable.
        return [], {}, False
    base_other = other.start
    if sentence.root.i - sentence.start != other.root.i - base_other:
        # They disagree about the main verb, which is the one thing the whole
        # exercise rests on.
        return [], {}, False
    spans = spans_of(other)
    kept = [f for f in folds if (f["start"], f["end"]) in spans]
    return kept, roles, True


def parse(passages, batch=16):
    """Yields each parsed passage as it finishes, so callers can write as they go."""
    import spacy

    print("loading parsers", flush=True)
    main = spacy.load("en_core_web_trf")
    check = spacy.load("en_core_web_sm")

    texts = [p["text"] for p in passages]
    done = 0
    agreed_total = sentence_total = 0
    for passage, doc, doc2 in zip(
        passages,
        main.pipe(texts, batch_size=batch),
        check.pipe(texts, batch_size=batch),
    ):
        by_text = {" ".join(s.text.split()): s for s in doc2.sents}

        sentences = []
        for sentence in doc.sents:
            if len(sentence) < 5:
                continue
            base = sentence.start
            other = by_text.get(" ".join(sentence.text.split()))
            folds, roles, agreed = confirm(
                sentence, folds_of(sentence), roles_of(sentence), other)
            sentences.append({
                "start": sentence.start_char,
                "end": sentence.end_char,
                "agreed": agreed,
                "heads": [token.head.i - base for token in sentence],
                "deps": [token.dep_ for token in sentence],
                "pos": [token.pos_ for token in sentence],
                "offsets": [[token.idx, token.idx + len(token.text)]
                            for token in sentence],
                "folds": folds,
                "roles": roles,
            })
            sentence_total += 1
            agreed_total += agreed
        yield dict(passage, sentences=sentences)

        done += 1
        if done % 25 == 0:
            print(f"  {done:,}/{len(passages):,} passages, "
                  f"{agreed_total:,}/{sentence_total:,} sentences agreed", flush=True)

    print(f"\n{sentence_total:,} sentences, {agreed_total:,} agreed "
          f"({agreed_total * 100 // max(1, sentence_total)}%)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0,
                        help="stop after this many newly parsed passages")
    parser.add_argument("--restart", action="store_true")
    args = parser.parse_args()

    src = os.path.join(CACHE, "passages.jsonl")
    if not os.path.exists(src):
        print("run tools/step7_passages.py first", file=sys.stderr)
        return 1
    with open(src, encoding="utf-8") as f:
        passages = [json.loads(line) for line in f]

    out_path = os.path.join(CACHE, "syntax.jsonl")
    if args.restart and os.path.exists(out_path):
        os.remove(out_path)
    done = set()
    if os.path.exists(out_path):
        with open(out_path, encoding="utf-8") as f:
            for line in f:
                try:
                    done.add(json.loads(line)["url"] + json.loads(line)["title"])
                except Exception:  # noqa: BLE001 - a half-written last line
                    continue
    todo = [p for p in passages if p["url"] + p["title"] not in done]
    if args.limit:
        todo = todo[:args.limit]
    print(f"{len(passages):,} passages, {len(done):,} already parsed, "
          f"{len(todo):,} to do")
    if not todo:
        return 0

    # Parsing is the slow half — a transformer manages about 700 words a second
    # on this machine, so a full library is hours. Appended and flushed as each
    # passage finishes, so stopping it costs only the passage in flight.
    with open(out_path, "a", encoding="utf-8") as dst:
        for passage in parse(todo):
            dst.write(json.dumps(passage, ensure_ascii=False) + "\n")
            dst.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
