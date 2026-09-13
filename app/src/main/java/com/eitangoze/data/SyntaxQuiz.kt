package com.eitangoze.data

/**
 * Questions about a sentence's structure, generated from its tree.
 *
 * 京大 marks 下線部和訳 on whether the structure was read right, and most of the
 * marks lost there are lost before any Japanese is written: the wrong noun taken
 * as the subject, a `that` read as a relative pronoun when it was appositive, a
 * participle attached to the wrong head. The translation itself is then a
 * faithful rendering of a sentence that was never there.
 *
 * Nothing here asks for a translation. There is no model Japanese to mark it
 * against — several English sentences translate the same Japanese and the
 * reverse is just as true — so the app does not pretend otherwise (see
 * docs/DESIGN.md, 作らないと決めたもの). What it can do is close off the way the
 * translation goes wrong, and every question below has exactly one answer that
 * comes from the tree rather than from an opinion.
 *
 * Only sentences both parsers confirmed produce questions. A tree that is wrong
 * about what attaches where would teach precisely the mistake it is meant to
 * prevent.
 */
data class SyntaxQuestion(
    val kind: Kind,
    val prompt: String,
    /** Token indices of the choices, in the order they are shown. */
    val choices: List<Int>,
    val correct: Int,
    /** Why it matters, shown after answering. */
    val note: String,
) {
    enum class Kind(val ja: String) {
        MAIN_VERB("主動詞"),
        SUBJECT("主語"),
        MODIFIED("係り先"),
    }

    val correctChoice: Int get() = choices[correct]
}

/** Builds the questions a sentence can honestly be asked. */
object SyntaxQuiz {

    private const val MAX_CHOICES = 4

    fun of(sentence: ParsedSentence, passage: String, seed: Int = 0): List<SyntaxQuestion> {
        if (!sentence.confirmed) return emptyList()
        val out = ArrayList<SyntaxQuestion>()
        mainVerb(sentence, passage, seed)?.let(out::add)
        subject(sentence, passage, seed)?.let(out::add)
        modified(sentence, passage, seed)?.let(out::add)
        return out
    }

    private fun word(sentence: ParsedSentence, passage: String, index: Int): String {
        val range = sentence.tokens.getOrNull(index) ?: return ""
        return passage.substring(range.first, range.last + 1)
    }

    /**
     * Which verb is the sentence's own.
     *
     * Only asked when there is something to get wrong: a sentence with one verb
     * in it is not a question, it is a reading of the sentence back.
     */
    private fun mainVerb(
        sentence: ParsedSentence,
        passage: String,
        seed: Int,
    ): SyntaxQuestion? {
        val root = sentence.roles.entries.firstOrNull { it.value == "V" }?.key ?: return null
        val rivals = sentence.pos.indices.filter {
            it != root && (sentence.pos[it] == "VERB" || sentence.pos[it] == "AUX")
        }
        if (rivals.isEmpty()) return null
        val choices = pick(listOf(root) + rivals, root, seed)
        return SyntaxQuestion(
            kind = SyntaxQuestion.Kind.MAIN_VERB,
            prompt = "この文の主動詞はどれ？",
            choices = choices,
            correct = choices.indexOf(root),
            note = "従属節の動詞を主動詞と取ると、文全体が別の意味になります。",
        )
    }

    /** Which noun the main verb belongs to. */
    private fun subject(
        sentence: ParsedSentence,
        passage: String,
        seed: Int,
    ): SyntaxQuestion? {
        val root = sentence.roles.entries.firstOrNull { it.value == "V" }?.key ?: return null
        val subject = sentence.roles.entries.firstOrNull { it.value == "S" }?.key ?: return null
        val rivals = sentence.pos.indices.filter {
            it != subject && sentence.pos[it] in NOUNISH
        }
        if (rivals.isEmpty()) return null
        val choices = pick(listOf(subject) + rivals, subject, seed + 1)
        return SyntaxQuestion(
            kind = SyntaxQuestion.Kind.SUBJECT,
            prompt = "${word(sentence, passage, root)} の主語はどれ？",
            choices = choices,
            correct = choices.indexOf(subject),
            note = "直前の名詞が主語とはかぎりません。修飾語のなかの名詞に引かれると、" +
                "訳は必ず崩れます。",
        )
    }

    /**
     * What a relative or participial clause is attached to.
     *
     * This is the one that decides 下線部和訳 more often than any other: the
     * clause is understood, and hung on the wrong noun.
     */
    private fun modified(
        sentence: ParsedSentence,
        passage: String,
        seed: Int,
    ): SyntaxQuestion? {
        val clause = sentence.folds.firstOrNull {
            it.label in setOf("relcl", "acl:relcl", "acl") && it.length >= 3
        } ?: return null
        val head = sentence.heads.getOrNull(clause.start)?.takeIf { it in sentence.tokens.indices }
            ?: return null
        // The head of a relative clause is what it modifies; but the fold's own
        // first token is the clause, so walk up from its head token.
        val target = sentence.heads.getOrNull(head) ?: head
        val anchor = if (sentence.pos.getOrNull(head) in NOUNISH) head else target
        if (sentence.pos.getOrNull(anchor) !in NOUNISH) return null
        val rivals = sentence.pos.indices.filter {
            it != anchor && sentence.pos[it] in NOUNISH && it < clause.start
        }
        if (rivals.isEmpty()) return null
        val phrase = (clause.start..clause.end).joinToString(" ") { word(sentence, passage, it) }
        val choices = pick(listOf(anchor) + rivals, anchor, seed + 2)
        return SyntaxQuestion(
            kind = SyntaxQuestion.Kind.MODIFIED,
            prompt = "「${phrase.take(60)}」はどの語にかかる？",
            choices = choices,
            correct = choices.indexOf(anchor),
            note = "節の意味が取れていても、かける先を間違えれば訳は別の文になります。",
        )
    }

    private val NOUNISH = setOf("NOUN", "PROPN", "PRON")

    /**
     * Up to four choices including the right one, ordered without a tell.
     *
     * Shuffled from a seed derived from the sentence rather than from a clock,
     * so the same question looks the same on every redraw — a choice that jumps
     * between recompositions is unanswerable.
     */
    private fun pick(candidates: List<Int>, correct: Int, seed: Int): List<Int> {
        val distinct = candidates.distinct()
        val others = distinct.filter { it != correct }.sortedBy {
            // Nearest rivals first: a distractor on the other side of the
            // sentence is not a mistake anyone would actually make.
            kotlin.math.abs(it - correct)
        }.take(MAX_CHOICES - 1)
        return (others + correct).sortedBy { (it * 31 + seed * 17) % 101 }
    }
}
