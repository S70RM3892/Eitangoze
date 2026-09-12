package com.eitangoze.data

/**
 * "How much of *this* English can you read right now?"
 *
 * Vocabulary research puts the threshold for reading without stopping to look
 * things up at 98% of the running words — 95% leaves roughly one unknown word
 * per two lines, which is enough to break comprehension (Hu & Nation 2000;
 * Nation 2006). Those numbers are about a text and a reader together, so they
 * can only be answered by something that knows both: the lexicon and what this
 * particular learner has actually retained.
 *
 * That is what this file computes. Paste a passage, and it reports the share you
 * can already read, and the shortest list of words that would take you to 98%.
 */

enum class Knowledge(val ja: String) {
    /** A card exists and is predicted to be recallable now. */
    KNOWN("覚えている"),

    /** Introduced, but predicted to be shaky. */
    LEARNING("学習中"),

    /** In the dictionary, never studied. */
    NEW("未学習"),

    /** Not in the dictionary: a name, a technical term, a typo. */
    UNLISTED("辞書外"),
}

/** One word of the text, with what is known about it. */
data class TextWord(
    val surface: String,
    val entry: Entry?,
    val knowledge: Knowledge,
    val occurrences: Int,
)

data class TextReport(
    val tokens: Int,
    val types: Int,
    val byKnowledge: Map<Knowledge, Int>,
    /** Share of running words the learner is predicted to know. */
    val coverage: Double,
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
        get() = if (tokens == 0) 0.0
        else (tokens - unlisted).toDouble() / tokens

    companion object {
        /** Reading without looking words up needs this share of the text. */
        const val UNASSISTED = 0.98

        /** Below this, comprehension breaks down even with a dictionary to hand. */
        const val ASSISTED = 0.95

        val EMPTY = TextReport(0, 0, emptyMap(), 0.0, emptyList(), 0, emptyMap(), "—")
    }
}

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
     * @param knowledgeOf classifies every entry found, in one call: a passage can
     *   mention several hundred distinct words and asking the study database
     *   about each one separately would be hundreds of queries.
     */
    fun analyze(text: String, knowledgeOf: (List<Entry>) -> Map<Long, Knowledge>): TextReport {
        val matches = WORD.findAll(text).map { it.value.lowercase().trimEnd('’', '\'') }
            .filter { it.length > 1 || it == "a" || it == "i" }
            .toList()
        if (matches.isEmpty()) return TextReport.EMPTY

        // One query for every candidate: the single words, and every 2..4 word
        // run that could be a phrase.
        val candidates = HashSet<String>(matches)
        for (n in 2..4) {
            for (i in 0..matches.size - n) {
                candidates.add(matches.subList(i, i + n).joinToString(" "))
            }
        }
        val found = content.surfaces(candidates)

        val counts = LinkedHashMap<Long, Int>()
        val unlistedCounts = LinkedHashMap<String, Int>()
        var tokens = 0
        var index = 0
        while (index < matches.size) {
            var taken = 0
            var entryId: Long? = null
            for (n in minOf(4, matches.size - index) downTo 2) {
                val gram = matches.subList(index, index + n).joinToString(" ")
                val hit = found[gram]
                if (hit != null) {
                    entryId = hit
                    taken = n
                    break
                }
            }
            if (entryId == null) {
                entryId = found[matches[index]]
                taken = 1
            }
            tokens++
            if (entryId != null) {
                counts[entryId] = (counts[entryId] ?: 0) + 1
            } else {
                val word = matches[index]
                unlistedCounts[word] = (unlistedCounts[word] ?: 0) + 1
            }
            index += taken
        }

        val entries = content.entries(counts.keys)
        val knowledge = knowledgeOf(entries.values.toList())
        val words = ArrayList<TextWord>(counts.size + unlistedCounts.size)
        val byKnowledge = LinkedHashMap<Knowledge, Int>()
        val levelProfile = LinkedHashMap<String, Int>()

        for ((id, occurrences) in counts) {
            val entry = entries[id] ?: continue
            val status = knowledge[id] ?: Knowledge.NEW
            words.add(TextWord(entry.lemma, entry, status, occurrences))
            byKnowledge[status] = (byKnowledge[status] ?: 0) + occurrences
            levelProfile[entry.cefr] = (levelProfile[entry.cefr] ?: 0) + occurrences
        }
        for ((word, occurrences) in unlistedCounts) {
            words.add(TextWord(word, null, Knowledge.UNLISTED, occurrences))
            byKnowledge[Knowledge.UNLISTED] =
                (byKnowledge[Knowledge.UNLISTED] ?: 0) + occurrences
        }

        val known = byKnowledge[Knowledge.KNOWN] ?: 0
        val coverage = known.toDouble() / tokens

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
            tokens = tokens,
            types = words.size,
            byKnowledge = byKnowledge,
            coverage = coverage,
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
