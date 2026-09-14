package com.eitangoze.data

/**
 * What the app knows about English, and what it can ask you about it.
 *
 * The content side ([Entry], [Sense], [Example], [Collocation]) is read-only and
 * ships with the app. The study side lives in [com.eitangoze.data.UserDb]; the
 * two meet in [CardKey], a string that names one question about one piece of
 * content and is stable across releases of the database.
 */

enum class Pos(val code: String, val ja: String) {
    NOUN("n", "名"),
    VERB("v", "動"),
    ADJ("adj", "形"),
    ADV("adv", "副"),
    PREP("prep", "前"),
    CONJ("conj", "接"),
    PRON("pron", "代"),
    DET("det", "限"),
    NUM("num", "数"),
    INTJ("intj", "間"),
    PHRASE("phr", "句");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: String): Pos = byCode[code] ?: PHRASE
    }
}

enum class EntryKind(val code: String, val ja: String) {
    WORD("word", "単語"),
    PHRASAL_VERB("phrasal_verb", "句動詞"),
    IDIOM("idiom", "熟語"),
    PROVERB("proverb", "ことわざ"),

    /**
     * A grammar word (`the`, `is`, `of`). Present so that measuring a text does
     * not report a seventh of ordinary English as unknown; never taught, because
     * there is nothing to teach — a reader meets these before anything else.
     */
    FUNCTION("function", "機能語");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: String): EntryKind = byCode[code] ?: WORD
    }
}

/**
 * A headword. One row per lemma *and* part of speech: `run` the noun and `run`
 * the verb are scheduled apart because knowing one does not give you the other.
 */
data class Entry(
    val id: Long,
    val lemma: String,
    val pos: Pos,
    val kind: EntryKind,
    val cefr: String,
    val cefrEstimated: Boolean,
    val rank: Int,
    val freq: Int,
    val lists: List<String>,
    val ipa: String,
    val forms: List<Pair<String, String>>,
    val root: String,
    val rootLang: String,
    val rootGloss: String,
    /** The word family this belongs to, or 0. See [RootFamily]. */
    val rootId: Long,
    val ja: List<String>,
) {
    val isPhrase: Boolean get() = kind != EntryKind.WORD

    /**
     * True for the words English is built out of rather than made of: the, be,
     * and, of, to, a, in, have, it, you…
     *
     * [EntryKind.FUNCTION] is meant to carry this and does not: the label comes
     * from Wiktionary's part of speech, so `the` and `of` have it while `be`,
     * `to`, `have`, `do` and `a` do not — and those resolve to the ordinary
     * headword, which has the lower rank. Frequency settles it instead. Ranks
     * 1–71 are the, be, and, of, to, a, in, have, it, you, he, for, they, not,
     * that, we, on, with, this, i, do, as, at, she, but, from, by, will, or:
     * every one of them structure, and the first content word in the list is
     * `say` at 72.
     *
     * Nothing here is taught. A learner meets these before the app does, and the
     * dictionary has no usable Japanese for them anyway — Wiktionary leaves the
     * ordinary senses unglossed, so the only gloss that survives is an exotic
     * one and the card ends up claiming `be` means ベリリウム.
     */
    val isStructural: Boolean get() = kind == EntryKind.FUNCTION || rank in 1..STRUCTURE_RANK

    companion object {
        /** The last rank that is grammar rather than vocabulary. See [isStructural]. */
        const val STRUCTURE_RANK = 71
    }

    /** Every spelling this entry can appear as in a sentence. */
    fun surfaces(): List<String> = buildList {
        add(lemma)
        forms.forEach { add(it.second) }
    }
}

/**
 * One meaning. [semcor] is how often this meaning was the intended one in a
 * hand-tagged corpus, which is the app's answer to "which meaning first?".
 */
data class Sense(
    val id: Long,
    val entryId: Long,
    val ord: Int,
    val ja: List<String>,
    val jaDefinition: String,
    val definition: String,
    val parent: String,
    val tags: List<String>,
    val topics: List<String>,
    val synonyms: List<String>,
    val antonyms: List<String>,
    val semcor: Int,
    val synset: String,
    /**
     * The `sense` rows this meaning was built from, when more than one was
     * folded together; empty when the sense stands alone. See
     * [com.eitangoze.data.ContentDb.senses] for why rows are folded at all.
     */
    val ids: List<Long> = emptyList(),
) {
    val headJa: String get() = ja.firstOrNull().orEmpty()
    val jaLine: String get() = ja.joinToString("、")

    /** Every row behind this meaning, whether or not it was merged. */
    val sourceIds: List<Long> get() = ids.ifEmpty { listOf(id) }

    /** True when [other] would read as the same answer to a Japanese learner. */
    fun sharesGloss(other: Sense): Boolean = ja.any { it in other.ja }
}

data class Example(val en: String, val ja: String)

/**
 * A family of words that visibly share a stem: `duc` in introduce, reduce,
 * conduct, education. [pattern] is the letters they have in common, [form] and
 * [gloss] the Latin or Greek word they descend from, [pie] the reconstructed
 * root that groups them.
 */
data class RootFamily(
    val id: Long,
    val pattern: String,
    val pie: String,
    val lang: String,
    val form: String,
    val gloss: String,
) {
    val label: String get() = if (form.isBlank()) pattern else "$lang $form"
}

/**
 * A piece of a word: a prefix, the stem, or a suffix.
 *
 * The cut comes from Wiktionary's own etymology, never from stripping letters
 * off the front of a word — that turns `region` into re- + gion and teaches
 * something false. [affixId] is 0 for a stem, and for an affix Wiktionary does
 * not define well enough to give a page of its own.
 */
data class Morpheme(
    val form: String,
    val kind: Kind,
    val affixId: Long,
    /** What the etymology template said this piece means, if it said anything. */
    val gloss: String,
) {
    enum class Kind(val code: String, val ja: String) {
        PREFIX("prefix", "接頭辞"),
        STEM("stem", "語根"),
        SUFFIX("suffix", "接尾辞"),
        INTERFIX("interfix", "連結辞");

        companion object {
            private val byCode = entries.associateBy { it.code }
            fun of(code: String): Kind = byCode[code] ?: STEM
        }
    }

    val isAffix: Boolean get() = kind != Kind.STEM
    val hasPage: Boolean get() = affixId != 0L
}

/**
 * An affix as a thing in its own right: `re-`, `-tion`, `un-`.
 *
 * The app treats a stem as *meaning* and an affix as an *operator* on it, which
 * is what makes the grid possible — hold the stem still and the prefixes line
 * up, hold the prefix still and the stems do. [uses] is how many headwords it
 * builds, and is the whole reason it is worth a page.
 */
data class Affix(
    val id: Long,
    val form: String,
    val kind: Morpheme.Kind,
    val gloss: List<String>,
    val ja: List<String>,
    val uses: Int,
) {
    val jaLine: String get() = ja.joinToString("、")
    val glossLine: String get() = gloss.joinToString("; ")
}

/** One cell of the grid: a word, and the piece that varies along the axis. */
data class GridCell(
    val entry: Entry,
    /** The piece being varied — a prefix when the stem is held still. */
    val varying: Morpheme,
    /** The piece held still. */
    val fixed: Morpheme,
)

data class Collocation(
    val entryId: Long,
    val pattern: String,
    val head: String,
    val collocate: String,
    val phrase: String,
    val count: Int,
    val example: String,
) {
    /** Japanese label for the grammatical shape, shown next to the answer. */
    val patternJa: String
        get() = when (pattern) {
            "v+n" -> "動詞＋名詞"
            "adj+n" -> "形容詞＋名詞"
            "v+prep" -> "動詞＋前置詞"
            "n+prep" -> "名詞＋前置詞"
            "adj+prep" -> "形容詞＋前置詞"
            else -> pattern
        }
}

/** How two entries are related; drives the "and while you are here" panel. */
enum class RelationKind(val code: String, val ja: String) {
    ROOT("root", "同語根"),
    SYNONYM("syn", "類義"),
    ANTONYM("ant", "対義"),
    FAMILY("family", "派生"),
    CONFUSE("confuse", "混同注意");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: String): RelationKind = byCode[code] ?: FAMILY
    }
}

data class Relation(val other: Entry, val kind: RelationKind)

/**
 * The question types. Each one is a different thing to know about a word, and
 * each is scheduled separately — the whole point of the app is that "I know
 * what `spare` means" and "I can produce `spare` from 予備の" are not the same
 * memory, and neither is "I can tell which `spare` this sentence means".
 */
enum class CardKind(
    val code: String,
    val title: String,
    val description: String,
    val defaultOn: Boolean = true,
) {
    MEANING("mean", "英→和", "語を見て意味を答える"),
    CONTEXT("ctx", "文脈判別", "例文の中でどの意味かを選ぶ"),
    PRODUCE("prod", "和→英（入力）", "意味から語を書く"),
    CLOZE("cloze", "例文穴埋め", "対訳つきの例文の空所に入れる"),
    COLLOCATION("coll", "コロケーション", "結びつく語を答える"),
    PARTICLE("part", "句動詞の前置詞", "句動詞の後ろを答える"),
    ROOT_WORD("root", "語源→語", "語根の意味から語を作る"),
    COMPOSITION("comp", "和文英訳", "日本語の文を英語で書く（自己採点）", defaultOn = false);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: String): CardKind = byCode[code] ?: MEANING
    }
}

/**
 * Names one question. Everything needed to rebuild the card is in here, so the
 * study table survives a content-database update: if the referenced row is gone
 * the card is simply skipped, and nothing else is disturbed.
 */
@JvmInline
value class CardKey(val value: String) {
    val kind: CardKind get() = CardKind.of(value.substringBefore(':'))
    val entryId: Long get() = value.split(':')[1].toLong()
    val extra: String get() = value.substringAfter(':').substringAfter(':', "")

    companion object {
        fun of(kind: CardKind, entryId: Long, extra: String = ""): CardKey =
            CardKey(if (extra.isEmpty()) "${kind.code}:$entryId" else "${kind.code}:$entryId:$extra")
    }
}

/**
 * What a passage is about. Wide on purpose.
 *
 * 京大 has set 科学史, 大気生物学 and 文明論; other years and other universities
 * set narrative and essay prose that a science-only library would never reach.
 * A reader who cannot find their subject stops reading, so every genre a
 * learner might ask for is already in the app.
 */
enum class Genre(val code: String, val ja: String) {
    SCIENCE("science", "自然科学"),
    MEDICINE("medicine", "医学・脳科学・心理"),
    TECHNOLOGY("technology", "技術・AI・情報"),
    ENVIRONMENT("environment", "環境・気候"),
    ECONOMY("economy", "経済・開発"),
    HISTORY("history", "歴史・文明論"),
    PHILOSOPHY("philosophy", "哲学・思想"),
    LANGUAGE("language", "言語・教育"),
    ARTS("arts", "芸術・文化"),
    SOCIETY("society", "社会・メディア・政治"),
    NEWS("news", "時事"),
    LITERATURE("literature", "物語・文学"),
    ESSAY("essay", "随筆・演説"),
    PLAIN("plain", "やさしい英語");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: String): Genre = byCode[code] ?: SCIENCE
    }
}

/**
 * One reading, about the length of an exam passage.
 *
 * [coverable] is the share of its running words the vocabulary database can
 * account for at all — the rest are names and jargon. It is the passage's own
 * ceiling: no amount of study takes you past it, and reporting a coverage
 * figure without it would be dishonest.
 */
data class Passage(
    val id: Long,
    val title: String,
    val genre: Genre,
    val source: String,
    val url: String,
    val license: String,
    val words: Int,
    val cefr: String,
    val coverable: Double,
    val text: String,
) {
    /** Attribution, as the licences require it to appear. */
    val credit: String get() = "$source ・ $license"
}

/** A subtree that can be collapsed, in token indices within its sentence. */
data class Fold(val start: Int, val end: Int, val label: String) {
    val length: Int get() = end - start + 1

    fun overlaps(other: Fold): Boolean = start <= other.end && other.start <= end

    /** Japanese for what kind of thing this is, for the label on the handle. */
    val ja: String
        get() = when (label) {
            "relcl", "acl:relcl" -> "関係節"
            "acl" -> "分詞・不定詞句"
            "advcl" -> "副詞節"
            "prep", "obl", "nmod" -> "前置詞句"
            "appos" -> "同格"
            "ccomp", "xcomp" -> "補文"
            "conj" -> "等位"
            "advmod" -> "副詞"
            else -> label
        }
}

/**
 * One sentence of a passage, with the tree that was computed for it at build
 * time.
 *
 * [confirmed] is whether a second parser agreed about the main verb and the
 * tokenisation. Only confirmed sentences carry [folds] — an unconfirmed one is
 * read exactly like any other, it simply never offers to fold itself, because a
 * tree that is wrong about where a clause ends teaches the opposite of the
 * lesson.
 */
data class ParsedSentence(
    val ord: Int,
    /** Character offsets of the whole sentence within `Passage.text`. */
    val start: Int,
    val end: Int,
    val confirmed: Boolean,
    /** Character offsets of each token, also within `Passage.text`. */
    val tokens: List<IntRange>,
    val heads: List<Int>,
    val deps: List<String>,
    val pos: List<String>,
    val folds: List<Fold>,
    /** S / V / O / C on the main clause, by token index. */
    val roles: Map<Int, String>,
) {
    val size: Int get() = tokens.size

    fun text(passage: String): String = passage.substring(start, end)

    /**
     * The sentence with [hidden] collapsed, each one replaced by a single mark.
     *
     * This is the whole exercise: take the modifiers away one at a time until
     * what is left is the clause the sentence is actually about. The main verb
     * can never be inside a fold (the build step refuses to emit one), so the
     * skeleton survives however much is folded.
     */
    fun folded(passage: String, hidden: Collection<Fold>, mark: String = "⌄"): String {
        if (hidden.isEmpty()) return text(passage)
        val byStart = hidden.associateBy { it.start }
        val out = StringBuilder()
        var index = 0
        var written = -1
        // Spacing is read off the original text rather than guessed from the
        // characters: a rule about punctuation gets `models (` wrong, because an
        // opening bracket takes its space on the other side from a comma. The
        // offsets already say where the gaps were.
        fun spaceBefore(start: Int) {
            if (out.isNotEmpty() && (written < 0 || start > written)) out.append(' ')
        }
        while (index < tokens.size) {
            val fold = byStart[index]
            if (fold != null && fold in hidden) {
                spaceBefore(tokens[index].first)
                out.append(mark)
                written = tokens[fold.end].last + 1
                index = fold.end + 1
                continue
            }
            val range = tokens[index]
            spaceBefore(range.first)
            out.append(passage, range.first, range.last + 1)
            written = range.last + 1
            index++
        }
        return out.toString()
    }

    /** Non-overlapping folds, widest first: one tap folds the most it can. */
    fun outermostFolds(): List<Fold> {
        val out = ArrayList<Fold>()
        for (fold in folds.sortedByDescending { it.length }) {
            if (out.none { it.overlaps(fold) }) out.add(fold)
        }
        return out.sortedBy { it.start }
    }
}

/** A deck is a slice of the vocabulary, in the order it should be learned. */
data class Deck(
    val id: String,
    val name: String,
    val subtitle: String,
    val cefr: String? = null,
    val kind: EntryKind? = null,
    val list: String? = null,
    /** Filled by the learner (from a pasted text or word list), not by a query. */
    val custom: Boolean = false,
) {
    companion object {
        val ALL: List<Deck> = listOf(
            Deck("A1", "A1 中学基礎", "英語の土台。ここが空くと上が崩れる", cefr = "A1"),
            Deck("A2", "A2 中学完成", "高校英語に入る前の基本語", cefr = "A2"),
            Deck("B1", "B1 高校標準", "共通テストで確実に要る語", cefr = "B1"),
            Deck("B2", "B2 入試標準", "二次試験の長文がこの層でできている", cefr = "B2"),
            Deck("C1", "C1 入試上級", "京大の下線部訳で差がつく層", cefr = "C1"),
            Deck("C2", "C2 難語", "読めなくてよい語ではなく、読めると速い語", cefr = "C2"),
            Deck("nawl", "学術語 NAWL", "論説・科学英文の骨格をなす 963 語", list = "nawl"),
            Deck("pv", "句動詞", "put off / look up to の類", kind = EntryKind.PHRASAL_VERB),
            Deck("idiom", "熟語・慣用句", "語をばらしても意味が出ない表現", kind = EntryKind.IDIOM),
            Deck("proverb", "ことわざ", "和文英訳に出る言い回し", kind = EntryKind.PROVERB),
            Deck("mine", "自分の英文・単語帳から", "読みたい英文や手持ちのリストから取り込んだ語",
                custom = true),
        )

        fun byId(id: String): Deck? = ALL.firstOrNull { it.id == id }
    }
}
