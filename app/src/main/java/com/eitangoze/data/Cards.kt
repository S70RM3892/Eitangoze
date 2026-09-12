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
        // confuse, and only when an example pins the one being asked about.
        if (senses.size >= 2) {
            for (sense in senses) {
                val hasExample = content.senseExamples(sense.id).any { it.en.isNotBlank() }
                if (hasExample) add(CardKind.CONTEXT, sense.id, sense.id.toString())
            }
        }

        content.sentences(entry.id, limit = 2).forEach {
            add(CardKind.CLOZE, senses.first().id, it.id.toString())
            add(CardKind.COMPOSITION, senses.first().id, it.id.toString())
        }

        content.collocations(entry.id, limit = 3).forEach {
            add(CardKind.COLLOCATION, senses.first().id, it.collocate)
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
        val sense = senses.firstOrNull { it.id == card.senseId } ?: withJa.firstOrNull()
        return when (card.kind) {
            CardKind.MEANING -> meaning(card, entry, withJa)
            CardKind.CONTEXT -> context(card, entry, withJa)
            CardKind.PRODUCE -> produce(card, entry, sense ?: return null)
            CardKind.CLOZE -> cloze(card, entry)
            CardKind.COLLOCATION -> collocation(card, entry)
            CardKind.PARTICLE -> particle(card, entry, sense ?: return null)
            CardKind.ROOT_WORD -> root(card, entry, sense ?: return null)
            CardKind.COMPOSITION -> composition(card, entry)
        }
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

    private fun meaning(card: UserDb.DueCard, entry: Entry, senses: List<Sense>): StudyCard? {
        val sense = senses.firstOrNull() ?: return null
        val correct = sense.jaLine
        val distractors = content.distractorMeanings(entry, setOf(correct) + sense.ja, 3)
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
            answerNotes = senseNotes(sense) + senses.drop(1).take(3)
                .map { "他の意味 ${it.ord + 1}" to it.jaLine },
            examples = content.senseExamples(sense.id).take(2),
        )
    }

    /**
     * The card this app exists for: the same word in a sentence, and the question
     * is which of *its own* meanings is in play. The wrong answers are the other
     * senses of the very same word, so guessing from the shape of the options
     * gains you nothing.
     */
    private fun context(card: UserDb.DueCard, entry: Entry, senses: List<Sense>): StudyCard? {
        val sense = senses.firstOrNull { it.id == card.senseId } ?: return null
        if (senses.size < 2) return null
        val example = content.senseExamples(sense.id).firstOrNull { it.en.isNotBlank() }
            ?: return null
        val correct = sense.jaLine
        val others = senses.filter { it.id != sense.id }.map { it.jaLine }
            .filter { it != correct }.take(3)
        if (others.isEmpty()) return null
        val choices = (listOf(correct) + others).shuffled(random)
        return StudyCard(
            key = card.key, kind = card.kind, deck = card.deck, entry = entry, sense = sense,
            instruction = "この文の ${entry.lemma} はどの意味？",
            prompt = example.en,
            promptJa = example.ja,
            promptNotes = heading(entry),
            mode = AnswerMode.CHOICE,
            choices = choices,
            correctIndex = choices.indexOf(correct),
            answerTitle = correct,
            answerNotes = senseNotes(sense),
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
            examples = content.senseExamples(sense.id).take(2),
        )
    }

    private fun cloze(card: UserDb.DueCard, entry: Entry): StudyCard? {
        val id = card.extra.toLongOrNull() ?: return null
        val linked = content.sentences(entry.id, limit = 8).firstOrNull { it.id == id }
            ?: return null
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
     */
    private fun collocation(card: UserDb.DueCard, entry: Entry): StudyCard? {
        val coll = content.collocations(entry.id, limit = 10)
            .firstOrNull { it.collocate == card.extra } ?: return null
        val blankHead = coll.pattern == "v+n" || coll.pattern == "adj+n"
        val answer = if (blankHead) coll.head else coll.collocate
        val shown = if (blankHead) {
            coll.phrase.replaceFirst(coll.head, "______")
        } else {
            coll.phrase.replaceFirst(Regex("\\b${Regex.escape(coll.collocate)}$"), "______")
        }
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
            examples = content.senseExamples(sense.id).take(2) +
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
