package com.eitangoze.data

import com.eitangoze.srs.Fsrs

/**
 * What a piece of English will look like to this learner, now and later.
 *
 * "How much of this can I read today" is a question anyone can answer by
 * reading it. The question nobody can answer by reading is what the same page
 * will look like in three months, because forgetting is invisible while it
 * happens. That is what this file computes: every word of a passage carries its
 * own predicted recall curve, so the text can be redrawn at any future date with
 * the words that will have gone by then faded out of it.
 *
 * The threshold it is measured against is the lexical coverage a reader needs to
 * get through a text unassisted — 98% of the running words (Hu & Nation 2000;
 * Nation 2006). 95% leaves roughly one unknown word every two lines.
 */

enum class Knowledge(val ja: String) {
    /** Predicted to be recallable now. */
    KNOWN("読める"),

    /** Studied, but predicted to be shaky. */
    LEARNING("あやしい"),

    /** In the dictionary, never studied. */
    NEW("未学習"),

    /** Not in the dictionary: a name, a technical term, a typo. */
    UNLISTED("辞書外"),
}

/**
 * One word as it sits in the text, with everything needed to redraw it at any
 * date. [stability] and [lastReview] are the learner's own memory of this word;
 * [permanent] marks the words that carry no memory model at all — grammar words
 * and anything the learner has declared they already know.
 */
data class TextSpan(
    val start: Int,
    val end: Int,
    val entryId: Long?,
    val permanent: Boolean,
    val stability: Double,
    val lastReview: Long?,
) {
    /** Predicted probability of recalling this word at [at]. */
    fun recallAt(at: Long): Double = when {
        permanent -> 1.0
        lastReview == null || stability <= 0.0 -> 0.0
        else -> Fsrs.recallAfter((at - lastReview).toDouble() / DAY_MS, stability)
    }

    fun readableAt(at: Long): Boolean = recallAt(at) >= READABLE

    companion object {
        private const val DAY_MS = 86_400_000.0

        /** Predicted recall at which a word counts as readable on sight. */
        const val READABLE = 0.8
    }
}

/** One word of the text, aggregated over its occurrences. */
data class TextWord(
    val surface: String,
    val entry: Entry?,
    val knowledge: Knowledge,
    val occurrences: Int,
)

data class TextReport(
    /** The text as given, so it can be redrawn. */
    val text: String,
    val tokens: Int,
    val types: Int,
    val byKnowledge: Map<Knowledge, Int>,
    /** Share of running words the learner is predicted to know now. */
    val coverage: Double,
    /** Every word occurrence, in order, with its memory curve. */
    val spans: List<TextSpan>,
    /**
     * Every distinct word of the text with what is known about it, including the
     * ones no entry matched. Written English needs the whole list — a spelling
     * nothing in the dictionary recognises, and a word used five times, are both
     * things to say about a composition and neither is a gap to study.
     */
    val words: List<TextWord> = emptyList(),
    /** Words to learn, most blocking first. */
    val gaps: List<TextWord>,
    /** How many of [gaps] take coverage to 98%. */
    val gapsToThreshold: Int,
    /** Running words per CEFR band, for the level estimate. */
    val levelProfile: Map<String, Int>,
    /** The band at which cumulative coverage of the text passes 95%. */
    val estimatedLevel: String,
) {
    val known: Int get() = byKnowledge[Knowledge.KNOWN] ?: 0
    val unlisted: Int get() = byKnowledge[Knowledge.UNLISTED] ?: 0

    /** Coverage the learner would reach after studying every gap word. */
    val reachableCoverage: Double
        get() = if (tokens == 0) 0.0 else (tokens - unlisted).toDouble() / tokens

    /** Share of the text readable at [at]. This is the number reading cannot give you. */
    fun coverageAt(at: Long): Double {
        if (tokens == 0) return 0.0
        return spans.count { it.readableAt(at) }.toDouble() / tokens
    }

    /**
     * Words that are readable now but will not be at [at] — what the next few
     * months will quietly take away if nothing is reviewed.
     */
    fun fadingBy(at: Long, now: Long = System.currentTimeMillis()): List<TextSpan> =
        spans.filter { it.readableAt(now) && !it.readableAt(at) }

    companion object {
        /** Reading without looking words up needs this share of the text. */
        const val UNASSISTED = 0.98

        /** Below this, comprehension breaks down even with a dictionary to hand. */
        const val ASSISTED = 0.95

        val EMPTY = TextReport(
            text = "", tokens = 0, types = 0, byKnowledge = emptyMap(), coverage = 0.0,
            spans = emptyList(), words = emptyList(), gaps = emptyList(), gapsToThreshold = 0,
            levelProfile = emptyMap(), estimatedLevel = "—",
        )
    }
}

/** What the study database knows about one word. */
data class WordMemory(
    val knowledge: Knowledge,
    /** True when there is no forgetting curve: a grammar word, or a declared level. */
    val permanent: Boolean = false,
    val stability: Double = 0.0,
    val lastReview: Long? = null,
)

private val WORD = Regex("[A-Za-z][A-Za-z'’-]*")
private val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")

/**
 * Split English into words, longest phrases first.
 *
 * `look up to` has to be read as one item and not as `look` plus `up` plus `to`,
 * or a learner who knows the three words would be told they can read a sentence
 * whose meaning they would miss entirely.
 */
class TextAnalyzer(private val content: ContentDb) {

    /**
     * @param memoryOf describes every entry found, in one call: a passage can
     *   mention several hundred distinct words and asking the study database
     *   about each one separately would be hundreds of queries.
     */
    fun analyze(text: String, memoryOf: (List<Entry>) -> Map<Long, WordMemory>): TextReport {
        val hits = WORD.findAll(text).toList()
        val forms = hits.map { it.value.lowercase().trimEnd('’', '\'') }
        if (forms.isEmpty()) return TextReport.EMPTY

        // One query for every candidate: the single words, and every 2..4 word
        // run that could be a phrase.
        val candidates = HashSet<String>(forms)
        for (n in 2..4) {
            for (i in 0..forms.size - n) {
                candidates.add(forms.subList(i, i + n).joinToString(" "))
            }
        }
        val found = content.surfaces(candidates)

        // Walk the text once, taking the longest phrase that matches at each
        // position, and remember where each item sat so the passage can be drawn
        // back with each word's own memory attached.
        data class Hit(val start: Int, val end: Int, val entryId: Long?, val form: String)

        val items = ArrayList<Hit>()
        var index = 0
        while (index < forms.size) {
            var taken = 1
            var entryId: Long? = null
            for (n in minOf(4, forms.size - index) downTo 2) {
                val gram = forms.subList(index, index + n).joinToString(" ")
                val hit = found[gram]
                if (hit != null) {
                    entryId = hit
                    taken = n
                    break
                }
            }
            if (entryId == null) entryId = found[forms[index]]
            items.add(
                Hit(
                    start = hits[index].range.first,
                    end = hits[index + taken - 1].range.last + 1,
                    entryId = entryId,
                    form = forms[index],
                )
            )
            index += taken
        }

        val counts = LinkedHashMap<Long, Int>()
        val unlistedCounts = LinkedHashMap<String, Int>()
        items.forEach { hit ->
            if (hit.entryId != null) counts[hit.entryId] = (counts[hit.entryId] ?: 0) + 1
            else unlistedCounts[hit.form] = (unlistedCounts[hit.form] ?: 0) + 1
        }

        val entries = content.entries(counts.keys)
        val memory = memoryOf(entries.values.toList())
        val spans = items.map { hit ->
            val own = hit.entryId?.let { memory[it] }
            TextSpan(
                start = hit.start,
                end = hit.end,
                entryId = hit.entryId,
                permanent = own?.permanent == true,
                stability = own?.stability ?: 0.0,
                lastReview = own?.lastReview,
            )
        }

        val words = ArrayList<TextWord>(counts.size + unlistedCounts.size)
        val byKnowledge = LinkedHashMap<Knowledge, Int>()
        val levelProfile = LinkedHashMap<String, Int>()
        for ((id, occurrences) in counts) {
            val entry = entries[id] ?: continue
            val status = memory[id]?.knowledge ?: Knowledge.NEW
            words.add(TextWord(entry.lemma, entry, status, occurrences))
            byKnowledge[status] = (byKnowledge[status] ?: 0) + occurrences
            levelProfile[entry.cefr] = (levelProfile[entry.cefr] ?: 0) + occurrences
        }
        for ((word, occurrences) in unlistedCounts) {
            words.add(TextWord(word, null, Knowledge.UNLISTED, occurrences))
            byKnowledge[Knowledge.UNLISTED] = (byKnowledge[Knowledge.UNLISTED] ?: 0) + occurrences
        }

        val tokens = items.size
        val known = byKnowledge[Knowledge.KNOWN] ?: 0

        // The gaps that block the most reading come first: a word used four
        // times is four times the obstacle of one used once, and an easier word
        // is likelier to recur in the next text too.
        val gaps = words
            .filter { it.knowledge == Knowledge.LEARNING || it.knowledge == Knowledge.NEW }
            .sortedWith(
                compareByDescending<TextWord> { it.occurrences }
                    .thenBy { it.entry?.rank ?: Int.MAX_VALUE }
            )

        var covered = known
        var needed = 0
        val target = tokens * TextReport.UNASSISTED
        for (gap in gaps) {
            if (covered >= target) break
            covered += gap.occurrences
            needed++
        }

        return TextReport(
            text = text,
            tokens = tokens,
            types = words.size,
            byKnowledge = byKnowledge,
            coverage = known.toDouble() / tokens,
            spans = spans,
            words = words,
            gaps = gaps,
            gapsToThreshold = needed,
            levelProfile = levelProfile,
            estimatedLevel = estimateLevel(levelProfile, tokens),
        )
    }

    /**
     * The level a reader needs to cover 95% of the text.
     *
     * Words outside the dictionary count against the total but belong to no
     * band, so a text full of names lands one level higher — which is honest:
     * it really is harder to read.
     */
    private fun estimateLevel(profile: Map<String, Int>, tokens: Int): String {
        if (tokens == 0) return "—"
        var cumulative = 0
        for (level in LEVELS) {
            cumulative += profile[level] ?: 0
            if (cumulative.toDouble() / tokens >= TextReport.ASSISTED) return level
        }
        return "C2+"
    }
}
