package com.eitangoze.data

/**
 * The words around one word, and which of them this learner can already read.
 *
 * The dictionary already knows how words connect — 11,419 derivations, 8,761
 * synonyms, 4,900 opposites, 3,307 confusable pairs, and 71 families that share
 * a Latin or Greek root. The entry page lists them, and a list is the wrong
 * shape for the question they answer. A list says "here are eight more words".
 * A map says **where you are**: this cluster is yours, that one is not, and this
 * word is one step from four you already have.
 *
 * The colouring is what makes it worth drawing at all. Every other vocabulary
 * map is a picture of English; this one is a picture of the learner's English,
 * because every node carries the same memory state the reading screens use. A
 * cluster with one word missing is the vocabulary equivalent of the affix
 * grid's empty cell — the place where studying one word closes something.
 *
 * Nothing is inferred. An edge exists because a row in the database says so, and
 * the kinds are kept apart rather than merged into "related": 類義 and 対義 are
 * opposite claims, and 混同注意 is a warning rather than a connection of
 * meaning at all.
 */

/** One word on the map, and how it reaches the word in the middle. */
data class MapNode(
    val entry: Entry,
    val kind: RelationKind,
    val knowledge: Knowledge,
) {
    /** True when this is a word the learner is predicted to read on sight. */
    val known: Boolean get() = knowledge == Knowledge.KNOWN
}

/**
 * One word's neighbourhood.
 *
 * [nodes] is ordered by kind and then by how common the word is, so the ring a
 * node lands in is stable between visits: a map that reshuffles itself every
 * time teaches nothing about where anything is.
 */
data class WordMap(
    val center: Entry,
    val centerKnowledge: Knowledge,
    val nodes: List<MapNode>,
    /** The family in the middle, when the centre word has a root. */
    val family: RootFamily?,
) {
    val known: Int get() = nodes.count { it.known }

    val byKind: Map<RelationKind, List<MapNode>>
        get() = nodes.groupBy { it.kind }

    val isEmpty: Boolean get() = nodes.isEmpty()

    /**
     * The one line worth saying under the picture.
     *
     * It is about the gap, not the total: a map whose words are all known is a
     * finished corner of the language, and one with a single unknown word is an
     * afternoon's work. Both are worth knowing and neither is a score.
     */
    val verdict: String
        get() = when {
            nodes.isEmpty() ->
                "この語につながる語は、辞書の中にありません。"
            known == nodes.size ->
                "つながる ${nodes.size} 語はすべて読めます。ここは埋まっています。"
            nodes.size - known == 1 ->
                "あと1語で、この周りが埋まります。"
            else ->
                "つながる ${nodes.size} 語のうち、読めるのは $known 語です。"
        }

    companion object {
        /** How many neighbours fit on a phone screen before it turns to soup. */
        const val LIMIT = 12
    }
}

/**
 * Builds the map for one word.
 *
 * The study database stays on the other side of [Repository]: this class knows
 * about English and takes what is known about the learner as a function, the
 * same way [TextAnalyzer] does.
 */
class WordMapper(private val content: ContentDb) {

    fun map(
        entryId: Long,
        memoryOf: (List<Entry>) -> Map<Long, WordMemory>,
    ): WordMap? {
        val center = content.entry(entryId) ?: return null
        val related = LinkedHashMap<Long, RelationKind>()

        // Same root first: it is the strongest connection the database has, and
        // the one a learner can use on a word they have never seen.
        val family = center.rootId.takeIf { it != 0L }?.let { content.family(it) }
        if (family != null) {
            content.familyMembers(center.rootId, limit = WordMap.LIMIT).forEach {
                if (it.id != center.id) related[it.id] = RelationKind.ROOT
            }
        }
        // The relation table stores a pair once per source that claimed it, so
        // the same word arrives two and three times over; the first kind wins.
        content.relations(center.id, limit = 40).forEach { relation ->
            if (relation.other.id != center.id) {
                related.putIfAbsent(relation.other.id, relation.kind)
            }
        }
        if (related.isEmpty()) {
            return WordMap(center, Knowledge.NEW, emptyList(), family)
        }

        val entries = content.entries(related.keys)
        val memory = memoryOf(entries.values + center)
        val nodes = related.mapNotNull { (id, kind) ->
            val entry = entries[id] ?: return@mapNotNull null
            MapNode(entry, kind, memory[id]?.knowledge ?: Knowledge.NEW)
        }
            // Kinds keep their order so a ring means the same thing every time,
            // and the commonest word comes first inside a ring.
            .sortedWith(compareBy({ ORDER.indexOf(it.kind) }, { it.entry.rank }))
            // One circle per word. `embrace` is listed against `abandon` as both
            // a verb and a noun, and two circles with the same name on them read
            // as a drawing mistake — which, on a map of vocabulary rather than
            // of parts of speech, is what it would be.
            .distinctBy { it.entry.lemma }
            .take(WordMap.LIMIT)

        return WordMap(
            center = center,
            centerKnowledge = memory[center.id]?.knowledge ?: Knowledge.NEW,
            nodes = nodes,
            family = family,
        )
    }

    private companion object {
        /** Rings, from the middle out. Nearest first: a shared root, then meaning. */
        val ORDER = listOf(
            RelationKind.ROOT,
            RelationKind.FAMILY,
            RelationKind.SYNONYM,
            RelationKind.ANTONYM,
            RelationKind.CONFUSE,
        )
    }
}
