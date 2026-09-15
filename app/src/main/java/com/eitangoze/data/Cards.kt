package com.eitangoze.data

import kotlin.random.Random

/** How the learner answers. */
enum class AnswerMode {
    /** Pick one of four. Used where the wrong answers themselves teach something. */
    CHOICE,

    /** Type it. The only mode that proves you can produce the word, not just recognise it. */
    TYPE,

    /** Write freely, then check your own work against the model answer. */
    SELF_CHECK,
}

/** One question, fully built and ready to draw. */
data class StudyCard(
    val key: CardKey,
    val kind: CardKind,
    val deck: String,
    val entry: Entry,
    val sense: Sense?,
    val instruction: String,
    val prompt: String,
    /** Japanese shown with the question (a sentence translation, a meaning). */
    val promptJa: String = "",
    val promptNotes: List<Pair<String, String>> = emptyList(),
    val mode: AnswerMode,
    val choices: List<String> = emptyList(),
    val correctIndex: Int = -1,
    val accepted: List<String> = emptyList(),
    val alsoAccepted: List<String> = emptyList(),
    val answerTitle: String = "",
    val answerNotes: List<Pair<String, String>> = emptyList(),
    val examples: List<Example> = emptyList(),
    val checklist: List<String> = emptyList(),
    /**
     * Every meaning of this word with a corpus count, for the bar that opens
     * when the answer is revealed. Empty when the word has only one.
     */
    val senseShare: List<Sense> = emptyList(),
) {
    val correctChoice: String get() = choices.getOrElse(correctIndex) { "" }
}

/** What a card would be, before it exists in the study database. */
data class CardSpec(
    val key: CardKey,
    val kind: CardKind,
    val entryId: Long,
    val senseId: Long,
    val extra: String,
)

/**
 * Turns database rows into questions.
 *
 * The card kinds are deliberately different *kinds of knowing*, not the same
 * fact in four costumes: recognising a meaning, telling two meanings apart in
 * context, producing the word, spelling it inside a sentence, and knowing what
 * it combines with. A learner who can do the first but not the third has a real
 * gap, and scheduling them together would hide it.
 */
class CardFactory(private val content: ContentDb, private val random: Random = Random.Default) {

    /** Every question that could be asked about [entry], given the enabled kinds. */
    fun specs(entry: Entry, deck: String, kinds: Set<CardKind>): List<CardSpec> {
        val senses = content.senses(entry.id).filter { it.ja.isNotEmpty() }
        if (senses.isEmpty()) return emptyList()
        val out = ArrayList<CardSpec>()

        fun add(kind: CardKind, senseId: Long = 0, extra: String = "") {
            if (kind in kinds) {
                out.add(CardSpec(CardKey.of(kind, entry.id, extra), kind, entry.id, senseId, extra))
            }
        }

        add(CardKind.MEANING, senses.first().id)
        add(CardKind.PRODUCE, senses.first().id)

        // Telling meanings apart only makes sense when there are meanings to
        // confuse, and only when an example pins the one being asked about —
        // an example the word is actually *in*. See [contextExample].
        //
        // At most [MAX_CONTEXT] of them, most-used first. `say` has six senses;
        // six context cards for one word is not learning the word, it is a
        // quiz about WordNet, and it lets a single word fill an entire session.
        // Which six matter is already known — the corpus counted them.
        if (senses.size >= 2) {
            senses.sortedByDescending { it.semcor }
                .filter { contextExample(entry, it) != null }
                .take(MAX_CONTEXT)
                .forEach { add(CardKind.CONTEXT, it.id, it.id.toString()) }
        }

        content.sentences(entry.id, limit = 2).forEach {
            if (blanksCleanly(entry, it)) add(CardKind.CLOZE, senses.first().id, it.id.toString())
            add(CardKind.COMPOSITION, senses.first().id, it.id.toString())
        }

        content.collocations(entry.id, limit = 3).forEach {
            if (!leaksAnswer(it)) add(CardKind.COLLOCATION, senses.first().id, it.collocate)
        }

        if (entry.kind == EntryKind.PHRASAL_VERB && entry.lemma.contains(' ')) {
            add(CardKind.PARTICLE, senses.first().id)
        }
        if (entry.root.isNotBlank()) {
            add(CardKind.ROOT_WORD, senses.first().id)
        }
        return out
    }

    /** Build the question named by a stored card, or null if its content is gone. */
    fun build(card: UserDb.DueCard): StudyCard? {
        val entry = content.entry(card.entryId) ?: return null
        val senses = content.senses(entry.id)
        val withJa = senses.filter { it.ja.isNotEmpty() }
        val sense = senses.firstOrNull { card.senseId in it.sourceIds } ?: withJa.firstOrNull()
        // Attached to every kind in one place: the proportions belong to the
        // word, not to the question that happened to be asked about it.
        val share = withJa.filter { it.semcor > 0 }.takeIf { it.size >= 2 }.orEmpty()
        return when (card.kind) {
            CardKind.MEANING -> meaning(card, entry, withJa)
            CardKind.CONTEXT -> context(card, entry, withJa)
            CardKind.PRODUCE -> produce(card, entry, sense ?: return null)
            CardKind.CLOZE -> cloze(card, entry)
            CardKind.COLLOCATION -> collocation(card, entry)
            CardKind.PARTICLE -> particle(card, entry, sense ?: return null)
            CardKind.ROOT_WORD -> root(card, entry, sense ?: return null)
            CardKind.COMPOSITION -> composition(card, entry)
        }?.copy(senseShare = share)
    }

    // ---- individual kinds ---------------------------------------------------

    private fun heading(entry: Entry): List<Pair<String, String>> = buildList {
        add("品詞" to entry.pos.ja)
        if (entry.ipa.isNotBlank()) add("発音" to entry.ipa)
        add("レベル" to entry.cefr + if (entry.cefrEstimated) "（推定）" else "")
    }

    private fun senseNotes(sense: Sense): List<Pair<String, String>> = buildList {
        if (sense.definition.isNotBlank()) add("英英" to sense.definition)
        if (sense.jaDefinition.isNotBlank()) add("語義" to sense.jaDefinition)
        if (sense.tags.isNotEmpty()) add("語法" to sense.tags.joinToString(" / ") { tagJa(it) })
        if (sense.synonyms.isNotEmpty()) add("類義" to sense.synonyms.joinToString(", "))
        if (sense.antonyms.isNotEmpty()) add("対義" to sense.antonyms.joinToString(", "))
    }

    /**
     * One meaning as it goes on a button.
     *
     * Capped, because a sense can carry eight glosses and four of those side by
     * side is not a question anyone reads — and because every option has to be
     * the same shape. See [ContentDb.distractorMeanings].
     */
    private fun choiceLine(sense: Sense): String =
        sense.ja.take(MAX_CHOICE_GLOSSES).joinToString("、")

    private fun meaning(card: UserDb.DueCard, entry: Entry, senses: List<Sense>): StudyCard? {
        val sense = senses.firstOrNull() ?: return null
        val correct = choiceLine(sense)
        val distractors = content.distractorMeanings(
            entry, setOf(correct) + sense.ja, 3,
            glosses = sense.ja.take(MAX_CHOICE_GLOSSES).size,
        )
        val choices = (listOf(correct) + distractors).shuffled(random)
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "意味はどれ？",
            prompt = entry.lemma,
            promptNotes = heading(entry),
            mode = if (distractors.size == 3) AnswerMode.CHOICE else AnswerMode.SELF_CHECK,
            choices = choices,
            correctIndex = choices.indexOf(correct),
            accepted = sense.ja,
            answerTitle = correct,
            answerNotes = buildList {
                // The button holds three; the rest belong to the meaning too.
                if (sense.ja.size > MAX_CHOICE_GLOSSES) add("訳語" to sense.jaLine)
                addAll(senseNotes(sense))
                senses.drop(1).take(3).forEach { add("他の意味 ${it.ord + 1}" to it.jaLine) }
            },
            examples = content.senseExamples(sense).take(2),
        )
    }

    /**
     * A sentence that can be asked about this sense of this word, or null.
     *
     * WordNet files an example against a *synset*, and a synset is a set of
     * words, so the sentence it stores is written with whichever member the
     * lexicographer reached for. Under `measure` 「法案」 that is "they held a
     * public hearing on the bill"; under 「評価する」, "Can you quantify your
     * results?". Both are correct English for the meaning and neither can be
     * used to ask which meaning of `measure` is in play, because `measure` is
     * not in them. **2,419 of the 9,842 context cards in the shipped database
     * (24.6%) were built on a sentence without the word in it.**
     *
     * So the sentence has to contain the word. Requiring it costs 1,480 cards
     * and leaves 348 words of 5,061 with none — those are the words whose every
     * example was about a synonym, and there was never a question to ask about
     * them.
     */
    private fun contextExample(entry: Entry, sense: Sense): Example? =
        content.senseExamples(sense)
            .firstOrNull { it.en.isNotBlank() && entry.appearsIn(it.en) }

    /**
     * The card this app exists for: the same word in a sentence, and the question
     * is which of *its own* meanings is in play. The wrong answers are the other
     * senses of the very same word, so guessing from the shape of the options
     * gains you nothing.
     */
    private fun context(card: UserDb.DueCard, entry: Entry, senses: List<Sense>): StudyCard? {
        val sense = senses.firstOrNull { card.senseId in it.sourceIds } ?: return null
        if (senses.size < 2) return null
        val example = contextExample(entry, sense) ?: return null
        // The wrong answers are the word's own other meanings and nothing else,
        // so a two-sense word gives a two-way question. That is the real task —
        // padding it out with another word's meaning would make it guessable.
        //
        // A wrong answer that shares a gloss with the right one is not a wrong
        // answer, it is the same answer written twice; ContentDb.senses folds
        // those together, and this refuses to build the question if any slip
        // through. Better no card than a card with two correct options.
        val wrong = senses.filter { it.id != sense.id && !it.sharesGloss(sense) }.take(3)
        if (wrong.isEmpty()) return null
        // Every option here is a real meaning of the same word out of the same
        // dictionary, so they already have the same shape — the right answer is
        // the longest line on 36% of these cards against 25% by chance, and
        // cutting every option down to the shortest one's length moves that to
        // 35% while throwing away most of what the buttons say. The remaining
        // 11 points are the lengths of Japanese words, which is not something
        // the card can or should flatten.
        val correct = choiceLine(sense)
        val others = wrong.map { choiceLine(it) }.filter { it != correct }.distinct()
        if (others.isEmpty()) return null
        val choices = (listOf(correct) + others).shuffled(random)
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "この文の ${entry.lemma} はどの意味？",
            prompt = example.en,
            // Deliberately no Japanese with the question. A translation of the
            // sentence is a translation of the word in it, so printing it under
            // the English hands over the answer — on 1,787 of these cards
            // (18.2%) the correct option appeared in it character for
            // character (`get` 「到着」 over 「彼女は7時に家に到着した」). It
            // is worth reading afterwards, so it moves below the line with the
            // rest of the answer.
            promptNotes = heading(entry),
            mode = AnswerMode.CHOICE,
            choices = choices,
            correctIndex = choices.indexOf(correct),
            answerTitle = correct,
            answerNotes = buildList {
                if (example.ja.isNotBlank()) add("この文の訳" to example.ja)
                if (sense.ja.size > MAX_CHOICE_GLOSSES) add("訳語" to sense.jaLine)
                addAll(senseNotes(sense))
            },
        )
    }

    private fun produce(card: UserDb.DueCard, entry: Entry, sense: Sense): StudyCard {
        val notes = heading(entry).toMutableList()
        notes.add("ヒント" to spellingHint(entry.lemma))
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "この意味の英語を書く",
            prompt = sense.jaLine,
            promptNotes = notes,
            mode = AnswerMode.TYPE,
            accepted = listOf(entry.lemma),
            answerTitle = entry.lemma,
            answerNotes = senseNotes(sense),
            examples = content.senseExamples(sense).take(2),
        )
    }

    /**
     * True when blanking the word out of this sentence actually removes it.
     *
     * The blank falls on one spelling, and a sentence can carry two: "Plums grow
     * on ______ trees", "The President ______ the bill, but Congress overrode
     * his veto". Eight sentences in the database do this, which is few enough to
     * simply not ask about — blanking both would make two answers out of one
     * question.
     */
    private fun blanksCleanly(entry: Entry, linked: ContentDb.LinkedSentence): Boolean {
        val rest = blankOut(linked.example.en, linked.surface)
        val others = entry.surfaces().filter { !it.equals(linked.surface, ignoreCase = true) }
        return others.none { blankOut(rest, it) != rest }
    }

    private fun cloze(card: UserDb.DueCard, entry: Entry): StudyCard? {
        val id = card.extra.toLongOrNull() ?: return null
        val linked = content.sentences(entry.id, limit = 8).firstOrNull { it.id == id }
            ?: return null
        if (!blanksCleanly(entry, linked)) return null
        val sense = content.senses(entry.id).firstOrNull { it.ja.isNotEmpty() }
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "空所に入る語を書く",
            prompt = blankOut(linked.example.en, linked.surface),
            promptJa = linked.example.ja,
            promptNotes = buildList {
                sense?.let { add("意味" to it.jaLine) }
                add("ヒント" to spellingHint(linked.surface))
            },
            mode = AnswerMode.TYPE,
            accepted = listOf(linked.surface),
            alsoAccepted = entry.surfaces(),
            answerTitle = linked.surface,
            answerNotes = buildList {
                add("原形" to entry.lemma)
                if (entry.forms.isNotEmpty()) {
                    add("活用" to entry.forms.joinToString(" / ") { "${formJa(it.first)} ${it.second}" })
                }
            },
            examples = listOf(linked.example),
        )
    }

    /**
     * Which word goes with which. The blank falls on the half that is not
     * predictable from the meaning: you can guess `decision` from 決定, but
     * nothing tells you it is `make` and not `do`.
     *
     * [blanked] says which half goes and what is left on the screen.
     */
    private fun blanked(coll: Collocation): Pair<String, String> {
        val blankHead = coll.pattern == "v+n" || coll.pattern == "adj+n"
        val answer = if (blankHead) coll.head else coll.collocate
        val shown = if (blankHead) {
            coll.phrase.replaceFirst(coll.head, "______")
        } else {
            coll.phrase.replaceFirst(Regex("\\b${Regex.escape(coll.collocate)}$"), "______")
        }
        return answer to shown
    }

    /** `wee wee`: blanking one `wee` leaves the other one standing. */
    private fun leaksAnswer(coll: Collocation): Boolean {
        val (answer, shown) = blanked(coll)
        return blankOut(shown, answer) != shown
    }

    private fun collocation(card: UserDb.DueCard, entry: Entry): StudyCard? {
        val coll = content.collocations(entry.id, limit = 10)
            .firstOrNull { it.collocate == card.extra } ?: return null
        if (leaksAnswer(coll)) return null
        val blankHead = coll.pattern == "v+n" || coll.pattern == "adj+n"
        val (answer, shown) = blanked(coll)
        val exclude = setOf(answer, coll.head, coll.collocate)
        val distractors = if (blankHead) {
            content.collocationDistractors(coll.pattern, exclude, 3)
        } else {
            listOf("of", "on", "in", "to", "for", "with", "from", "at", "by", "about")
                .filter { it !in exclude }.shuffled(random).take(3)
        }
        val choices = (listOf(answer) + distractors).shuffled(random)
        val sense = content.senses(entry.id).firstOrNull { it.ja.isNotEmpty() }
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "空所に入るのは？",
            prompt = shown,
            promptNotes = buildList {
                add("型" to coll.patternJa)
                sense?.let { add("意味" to it.jaLine) }
            },
            mode = if (distractors.size >= 2) AnswerMode.CHOICE else AnswerMode.TYPE,
            choices = choices,
            correctIndex = choices.indexOf(answer),
            accepted = listOf(answer),
            answerTitle = coll.phrase,
            answerNotes = listOf(
                "型" to coll.patternJa,
                "コーパス出現" to "${coll.count} 回",
            ),
            examples = coll.example.takeIf { it.isNotBlank() }?.let { listOf(Example(it, "")) }
                ?: emptyList(),
        )
    }

    /** `put ___` = 延期する. The particle is the part that carries the meaning. */
    private fun particle(card: UserDb.DueCard, entry: Entry, sense: Sense): StudyCard? {
        val parts = entry.lemma.split(' ')
        if (parts.size < 2) return null
        val tail = parts.drop(1).joinToString(" ")
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "この意味になるように続きを書く",
            prompt = parts.first() + " " + parts.drop(1).joinToString(" ") { "____" },
            promptJa = sense.jaLine,
            promptNotes = listOf("種類" to entry.kind.ja),
            mode = AnswerMode.TYPE,
            accepted = listOf(tail),
            answerTitle = entry.lemma,
            answerNotes = senseNotes(sense),
            examples = content.senseExamples(sense).take(2) +
                content.sentences(entry.id, limit = 1).map { it.example },
        )
    }

    private fun root(card: UserDb.DueCard, entry: Entry, sense: Sense): StudyCard {
        val gloss = entry.rootGloss.takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "語根と意味から語を作る",
            prompt = "${entry.rootLang} ${entry.root}$gloss",
            promptJa = sense.jaLine,
            promptNotes = listOf("品詞" to entry.pos.ja, "ヒント" to spellingHint(entry.lemma)),
            mode = AnswerMode.TYPE,
            accepted = listOf(entry.lemma),
            answerTitle = entry.lemma,
            answerNotes = senseNotes(sense),
        )
    }

    /**
     * Write the English for a Japanese sentence, then mark yourself.
     *
     * Automatic marking of free composition cannot be done honestly — several
     * English sentences are right and a string comparison would fail all but
     * one — so the card shows the model answer and asks you to check specific
     * points, the way 和文英訳 is actually marked.
     */
    private fun composition(card: UserDb.DueCard, entry: Entry): StudyCard? {
        val id = card.extra.toLongOrNull() ?: return null
        val example = content.sentence(id) ?: return null
        if (example.ja.isBlank()) return null
        val sense = content.senses(entry.id).firstOrNull { it.ja.isNotEmpty() }
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "英語にする",
            prompt = example.ja,
            promptNotes = listOf("使う語" to entry.lemma),
            mode = AnswerMode.SELF_CHECK,
            answerTitle = example.en,
            answerNotes = buildList {
                sense?.let { add("${entry.lemma} の意味" to it.jaLine) }
            },
            checklist = listOf(
                "${entry.lemma} を使えたか",
                "時制と数は合っているか",
                "冠詞・前置詞は正しいか",
                "日本語の意味が伝わっているか",
            ),
        )
    }

    companion object {
        /** How many meanings of one word are worth telling apart on sight. */
        const val MAX_CONTEXT = 3

        /**
         * How many Japanese words one multiple-choice option may list.
         *
         * A sense can carry eight, and four of those side by side is not a
         * question anyone reads. On the meaning card every option lists the
         * same number as well, so that the answer is never the one that simply
         * looks longest — see [ContentDb.distractorMeanings].
         */
        const val MAX_CHOICE_GLOSSES = 3

        fun tagJa(tag: String): String = when (tag) {
            "transitive" -> "他動詞"
            "intransitive" -> "自動詞"
            "ditransitive" -> "二重目的語"
            "countable" -> "可算"
            "uncountable" -> "不可算"
            "formal" -> "かたい語"
            "informal", "colloquial" -> "くだけた語"
            "literary" -> "文語"
            "figurative" -> "比喩"
            "idiomatic" -> "慣用"
            "attributive" -> "限定用法"
            "predicative" -> "叙述用法"
            "reflexive" -> "再帰"
            "passive" -> "受動"
            "not-comparable" -> "比較変化なし"
            "comparable" -> "比較変化あり"
            "plural-only" -> "複数形のみ"
            "singular-only" -> "単数形のみ"
            "British" -> "英"
            "US" -> "米"
            "slang" -> "俗語"
            "auxiliary" -> "助動詞"
            "modal" -> "法助動詞"
            else -> tag
        }

        fun formJa(label: String): String = when (label) {
            "3sg" -> "三単現"
            "ing" -> "-ing形"
            "past" -> "過去・過去分詞"
            "plural" -> "複数形"
            "comparative" -> "比較級"
            "superlative" -> "最上級"
            else -> label
        }
    }
}
