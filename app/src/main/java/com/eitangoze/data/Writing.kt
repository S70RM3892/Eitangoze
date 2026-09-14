package com.eitangoze.data

/**
 * Writing English — the half of the exam that reading practice never reaches.
 *
 * 京大 sets four questions: two reading passages, a Japanese sentence to put
 * into English, and a free composition of 80–100 words. Everything else in this
 * app serves the first two. This file serves the other two, and it has to do it
 * without ever claiming to mark English prose, because marking prose needs a
 * model answer this app does not have and could not honestly invent.
 *
 * What it does have is two things nothing else in a learner's hands has:
 *
 * 1. **40,918 Japanese–English pairs** that came with the dictionary. The
 *    English side is a reference translation written by a person, not by us, so
 *    a drill can show it without anyone authoring an answer key.
 * 2. **A memory state per word.** Reading a word and producing it are separate
 *    cards here (see [CardKind]), so the app can pick a Japanese sentence whose
 *    English uses only words the learner is predicted to *read* on sight — and
 *    then every word they fail to produce is a real finding rather than the
 *    obvious consequence of not knowing the word at all.
 *
 * That is the whole idea: **読める語と書ける語のずれだけを取り出す。** A sentence
 * you could not read teaches nothing when you fail to write it.
 *
 * What is deliberately *not* computed: whether the English is correct. Tense,
 * article, agreement and idiom are all beyond a lexical comparison, and a
 * confident wrong verdict is worse than silence — the same line this app draws
 * at 誤読リスク and at Lesk. The comparison reports overlap of content words
 * against a corpus translation, says so in those words, and leaves the rest to
 * the learner's own checklist.
 */

private val WORD = Regex("[A-Za-z][A-Za-z'’-]*")
private val SENTENCE_END = Regex("[.!?]+")
private val CONTRACTED = Regex("[A-Za-z]+['’][A-Za-z]+")

/**
 * Where vocabulary stops and grammar starts, by corpus frequency.
 *
 * [EntryKind.FUNCTION] would be the right test, but the shipped dictionary
 * takes that label from Wiktionary's part of speech rather than from frequency,
 * so `the` and `of` carry it while `be`, `to`, `have`, `do` and `a` do not — and
 * those resolve to the ordinary headword because it has the lower rank. For
 * reading that hardly matters. For writing it matters a great deal: nobody
 * fails to produce `to`, and reporting it as a word the learner could read but
 * not write would discredit every other line on the screen.
 *
 * The cut is the corpus's own: ranks 1–71 are the, be, and, of, to, a, in, have,
 * it, you, he, for, they, not, that, we, on, with, this, i, do, as, at, she,
 * but, from, by, will, or — every one of them structure. The first content word
 * in the list is `say`, at 72.
 */
private const val GRAMMAR_RANK = 71

private val Entry.isGrammar: Boolean
    get() = kind == EntryKind.FUNCTION || (rank in 1..GRAMMAR_RANK)

/** One English the corpus offers for a Japanese sentence, with its content words. */
data class Reference(
    val id: Long,
    val en: String,
    /** Everything but grammar words, in the order they appear. */
    val content: List<Entry>,
)

/**
 * A Japanese sentence to write in English.
 *
 * [readiness] is the share of content words the learner is predicted to know by
 * sight, in whichever of the [references] they know best — one translation they
 * could have written is all the drill needs. It is shown rather than hidden: at
 * 1.0 the drill is a clean test of production, and below it the learner is
 * entitled to know that part of what is being asked of them is simply new
 * vocabulary.
 */
data class WritingTask(
    val ja: String,
    val references: List<Reference>,
    val readiness: Double,
) {
    /** The shortest reference: the least the learner has to have produced. */
    val shortest: Reference get() = references.minByOrNull { it.en.length } ?: references.first()

    val clean: Boolean get() = readiness >= 1.0
}

/** A word of the reference the learner did not write, and the one they wrote instead. */
data class Substitution(val expected: Entry, val used: Entry, val kind: RelationKind)

/**
 * What one attempt at 和文英訳 has in common with the corpus translation.
 *
 * Every field is an overlap of words, never a judgement of English. The naming
 * is deliberate — [matched] and [missed], not correct and wrong — because a
 * sentence that shares four content words out of six with one reference may be
 * perfectly good English, and a sentence that shares all six may not be.
 */
data class WritingReview(
    val task: WritingTask,
    val written: String,
    /** The reference this attempt came closest to; all of them are right answers. */
    val closest: Reference,
    val matched: List<Entry>,
    /** Content words of [closest] that appear nowhere in the attempt. */
    val missed: List<Entry>,
    /** Missed words the learner covered with a related word of their own. */
    val substituted: List<Substitution>,
    /** Words used that no reference has. Not an error: another way to say it. */
    val extra: List<Entry>,
    /** Spellings no entry in the dictionary matched. */
    val unlisted: List<String>,
) {
    val expected: Int get() = closest.content.size

    /** Share of the closest reference's content words the attempt produced. */
    val overlap: Double
        get() = if (expected == 0) 0.0 else matched.size.toDouble() / expected

    val wrote: Boolean get() = written.isNotBlank()

    /**
     * The one line worth saying, in the order the findings block each other.
     *
     * A missed content word is the finding this app exists to produce: the
     * learner could read it and could not write it. Everything else is reported
     * as information, not as a fault.
     */
    val verdict: String
        get() = when {
            !wrote -> "まだ何も書かれていません。"
            missed.isNotEmpty() ->
                "読めるのに書けなかった語が ${missed.size} 語あります。これが和文英訳で落ちる語です。"
            substituted.isNotEmpty() ->
                "参照訳とは違う語で言い換えています。意味が通っていれば、それは正解のひとつです。"
            else ->
                "参照訳の内容語はすべて出せています。残るのは時制・冠詞・語順で、そこは機械には判定できません。"
        }
}

/**
 * Picking a sentence to write, and comparing what came back.
 *
 * Both entry points take the analyzer as a function so that the study database
 * stays on the other side of [Repository]: this class knows about English and
 * about the corpus, and nothing about who is using it.
 */
class WritingDrill(private val content: ContentDb) {

    /**
     * The best sentence to ask for, out of [candidates].
     *
     * "Best" is the one whose English the learner can already read, longest
     * first among those — a longer sentence has more structure to get wrong and
     * says more when it comes back. Only [inspect] of them are measured, because
     * each measurement is a handful of queries and the difference between the
     * best of twenty-four and the best of sixty is not worth the wait.
     *
     * Below [floor] nothing is returned at all. A sentence whose words the
     * learner has not met does not measure their writing, it measures their
     * vocabulary — which every other screen in the app already does, better.
     * Saying "not yet" is the honest answer, and the screen says why.
     */
    fun pick(
        candidates: List<Example>,
        inspect: Int = 24,
        floor: Double = 0.6,
        analyze: (String) -> TextReport,
    ): WritingTask? {
        var best: Example? = null
        var bestScore = -1.0
        var bestWords = 0
        val seen = HashSet<String>()
        for (candidate in candidates) {
            if (!seen.add(candidate.ja)) continue
            if (seen.size > inspect) break
            val report = analyze(candidate.en)
            val score = readiness(report)
            val words = WORD.findAll(candidate.en).count()
            if (score > bestScore || (score == bestScore && words > bestWords)) {
                best = candidate
                bestScore = score
                bestWords = words
            }
            // A sentence made entirely of words they can read is what the drill
            // is for; once one long enough to be worth writing turns up, stop.
            if (bestScore >= 1.0 && bestWords >= 8) break
        }
        val chosen = best ?: return null
        if (bestScore < floor) return null
        return task(chosen.ja, analyze)
    }

    /**
     * Build the task for one Japanese sentence, gathering every English for it.
     *
     * Readiness is measured again here, over each translation in turn, and the
     * best one wins: the learner only has to have written one of them, so a
     * second translation full of words they have never met says nothing about
     * whether this sentence is fair to ask for.
     */
    fun task(ja: String, analyze: (String) -> TextReport): WritingTask? {
        val parallels = content.parallels(ja)
        if (parallels.isEmpty()) return null
        var readiness = 0.0
        val references = parallels.entries
            .distinctBy { it.value.trim() }
            .map { (id, en) ->
                val report = analyze(en)
                readiness = maxOf(readiness, readiness(report))
                Reference(id, en, contentWords(report))
            }
            .filter { it.content.isNotEmpty() }
        if (references.isEmpty()) return null
        return WritingTask(ja = ja, references = references, readiness = readiness)
    }

    /**
     * Compare an attempt against every reference the corpus has.
     *
     * The reference it comes closest to is the one reported against. Any of them
     * is a right answer, so scoring against a fixed one — the first, the
     * shortest — would fail a learner for choosing the other translator's words.
     */
    fun review(task: WritingTask, written: String, analyze: (String) -> TextReport): WritingReview {
        val report = if (written.isBlank()) TextReport.EMPTY else analyze(written)
        val mine = contentWords(report)
        val mineIds = mine.map { it.id }.toSet()

        val closest = task.references.maxWithOrNull(
            compareBy<Reference> { reference ->
                if (reference.content.isEmpty()) 0.0
                else reference.content.count { it.id in mineIds }.toDouble() / reference.content.size
            }.thenBy { it.content.count { word -> word.id in mineIds } }
        ) ?: task.shortest

        val matched = closest.content.filter { it.id in mineIds }
        val substituted = ArrayList<Substitution>()
        val missed = ArrayList<Entry>()
        for (word in closest.content.filter { it.id !in mineIds }) {
            val related = relatedUse(word, mineIds)
            if (related != null) substituted.add(related) else missed.add(word)
        }
        val anywhere = task.references.flatMap { it.content }.map { it.id }.toSet()
        val covered = substituted.map { it.used.id }.toSet()
        return WritingReview(
            task = task,
            written = written.trim(),
            closest = closest,
            matched = matched,
            missed = missed,
            substituted = substituted,
            extra = mine.filter { it.id !in anywhere && it.id !in covered },
            unlisted = report.words
                .filter { it.knowledge == Knowledge.UNLISTED }
                .map { it.surface }
                .filterNot { CONTRACTED.matches(it) },
        )
    }

    /**
     * A word of the learner's own that stands in for one they did not use.
     *
     * `uncommon` for `rare`, `answer` for `reply`: the dictionary already knows
     * these are related, so calling them a miss would be wrong. It is reported
     * separately rather than silently counted as a match, because the two words
     * are not interchangeable everywhere and only the learner can see whether
     * this sentence was one of the places.
     */
    private fun relatedUse(expected: Entry, used: Set<Long>): Substitution? {
        if (used.isEmpty()) return null
        val relation = content.relations(expected.id, limit = 24).firstOrNull {
            it.other.id in used &&
                (it.kind == RelationKind.SYNONYM || it.kind == RelationKind.FAMILY ||
                    it.kind == RelationKind.ROOT)
        } ?: return null
        return Substitution(expected = expected, used = relation.other, kind = relation.kind)
    }

    /** Everything in a text but the grammar words, deduplicated, in order. */
    private fun contentWords(report: TextReport): List<Entry> =
        report.words.mapNotNull { it.entry }.filterNot { it.isGrammar }

    /**
     * Share of a sentence's content words the learner can read on sight.
     *
     * Spellings the dictionary does not have — usually a name — count in the
     * total. `Disneyland` is a word the writer has to produce like any other, and
     * pretending it is free would make the sentence look easier than it is.
     */
    private fun readiness(report: TextReport): Double {
        var total = 0
        var known = 0
        for (word in report.words) {
            if (word.entry?.isGrammar == true) continue
            total++
            if (word.knowledge == Knowledge.KNOWN) known++
        }
        return if (total == 0) 0.0 else known.toDouble() / total
    }
}

/**
 * What can be said about a free composition without pretending to mark it.
 *
 * 京大's fourth question is 80–100 words arguing a position. Nothing offline can
 * tell whether the argument is any good, and this app does not try. What it can
 * do is count, and counting is exactly where the marks are lost: a composition
 * that runs to 140 words, repeats one noun six times, or leans entirely on A1
 * vocabulary loses points for reasons a person can fix in a minute — once
 * somebody tells them.
 *
 * [attested] is the one positive claim: pairs of words the learner used that a
 * corpus actually puts together. The table holds 15,088 pairings, nowhere near
 * all of English, so nothing is ever said about a pairing that is *missing* from
 * it. Absence of evidence is not evidence here, and a red underline under good
 * English would teach the learner to distrust the app.
 */
data class EssayCheck(
    val text: String,
    val words: Int,
    val sentences: Int,
    /** CEFR band to running words, for "is this all A1?". */
    val levelProfile: Map<String, Int>,
    /** Content words used three times or more. */
    val repeated: List<TextWord>,
    /** Spellings no entry matched: names, or typos. Reported as neither. */
    val unlisted: List<TextWord>,
    /** Pairings the learner used that a corpus attests. */
    val attested: List<Collocation>,
) {
    val withinLength: Boolean get() = words in MIN_WORDS..MAX_WORDS

    /** Share of running words at B1 or above: the level the marker sees. */
    val aboveB1: Int
        get() = levelProfile.filterKeys { it in ABOVE }.values.sum()

    val averageSentence: Int
        get() = if (sentences == 0) 0 else words / sentences

    val lengthNote: String
        get() = when {
            words == 0 -> "まだ書かれていません"
            words < MIN_WORDS -> "$MIN_WORDS 語まであと ${MIN_WORDS - words} 語"
            words > MAX_WORDS -> "$MAX_WORDS 語を ${words - MAX_WORDS} 語超えています"
            else -> "指定の $MIN_WORDS〜$MAX_WORDS 語に収まっています"
        }

    companion object {
        /** 京大 has set 80–100 words; the shape of the answer follows the count. */
        const val MIN_WORDS = 80
        const val MAX_WORDS = 100

        private val ABOVE = setOf("B1", "B2", "C1", "C2")
    }
}

/** The four moves of an argument, in the order the answer has to make them. */
enum class EssayPart(val ja: String, val hint: String) {
    CLAIM("主張", "どちらの立場かを1文で。理由はまだ書かない"),
    REASON_ONE("理由1", "主張を支える理由を1つ。具体例まで"),
    REASON_TWO("理由2", "別の角度からもう1つ。1つ目の言い換えにしない"),
    CLOSE("総括", "主張を別の言葉で置き直す。新しい話題を出さない"),
}

/** Measures a free composition. Counts only; see [EssayCheck] for what is refused. */
class EssayReader(private val content: ContentDb) {

    fun check(text: String, analyze: (String) -> TextReport): EssayCheck {
        val words = WORD.findAll(text).count()
        if (words == 0) {
            return EssayCheck(text, 0, 0, emptyMap(), emptyList(), emptyList(), emptyList())
        }
        val report = analyze(text)
        val used = report.words.mapNotNull { it.entry }.filterNot { it.isGrammar }
        return EssayCheck(
            text = text,
            words = words,
            sentences = SENTENCE_END.findAll(text).count().coerceAtLeast(1),
            levelProfile = report.levelProfile,
            repeated = report.words
                .filter { it.occurrences >= 3 && it.entry?.isGrammar == false }
                .sortedByDescending { it.occurrences },
            // `don't` and `we're` are in nobody's dictionary here; calling them
            // unknown spellings would be reporting this app's own gap as the
            // writer's mistake.
            unlisted = report.words.filter {
                it.knowledge == Knowledge.UNLISTED && !CONTRACTED.matches(it.surface)
            },
            attested = content.collocationsAmong(used.map { it.lemma })
                .distinctBy { it.head to it.collocate },
        )
    }
}
