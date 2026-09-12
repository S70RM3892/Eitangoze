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
    val ja: List<String>,
) {
    val isPhrase: Boolean get() = kind != EntryKind.WORD

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
) {
    val headJa: String get() = ja.firstOrNull().orEmpty()
    val jaLine: String get() = ja.joinToString("、")
}

data class Example(val en: String, val ja: String)

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
