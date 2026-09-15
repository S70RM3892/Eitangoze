package com.eitangoze.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The shipped vocabulary database: read-only, replaced wholesale by a release.
 *
 * It arrives gzipped in assets and is inflated once into the app's files
 * directory, because SQLite needs a real file to seek around in. Study progress
 * is deliberately *not* in here — see [UserDb] — so a bigger or better lexicon
 * can be shipped without anyone losing their review history.
 */
class ContentDb private constructor(private val db: SQLiteDatabase) {

    val entryCount: Int by lazy { meta("entries")?.toIntOrNull() ?: 0 }
    val senseCount: Int by lazy { meta("senses")?.toIntOrNull() ?: 0 }
    val sentenceCount: Int by lazy { meta("sentences")?.toIntOrNull() ?: 0 }
    val surfaceCount: Int by lazy { meta("surfaces")?.toIntOrNull() ?: 0 }
    val rootCount: Int by lazy { meta("roots")?.toIntOrNull() ?: 0 }

    fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    // ---- entries ------------------------------------------------------------

    private fun readEntry(c: Cursor) = Entry(
        id = c.getLong(0),
        lemma = c.getString(1),
        pos = Pos.of(c.getString(2)),
        kind = EntryKind.of(c.getString(3)),
        cefr = c.getString(4),
        cefrEstimated = c.getInt(5) == 1,
        rank = c.getInt(6),
        freq = c.getInt(7),
        lists = c.getString(8).splitField(),
        ipa = c.getString(9),
        forms = c.getString(10).splitField().mapNotNull {
            val i = it.indexOf(':')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        },
        root = c.getString(11),
        rootLang = c.getString(12),
        rootGloss = c.getString(13),
        rootId = c.getLong(14),
        ja = c.getString(15).splitField().dedupeGlosses(),
    )

    private val entryColumns =
        "id, lemma, pos, kind, cefr, cefr_est, rank, freq, lists, ipa, forms, " +
            "root, root_lang, root_gloss, root_id, ja"

    fun entry(id: Long): Entry? =
        db.rawQuery("SELECT $entryColumns FROM entry WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) readEntry(it) else null }

    fun entries(ids: Collection<Long>): Map<Long, Entry> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Entry>(ids.size)
        ids.chunked(400).forEach { chunk ->
            val holes = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT $entryColumns FROM entry WHERE id IN ($holes)",
                chunk.map { it.toString() }.toTypedArray(),
            ).use { c -> while (c.moveToNext()) readEntry(c).let { out[it.id] = it } }
        }
        return out
    }

    /**
     * The next entries of a deck in teaching order, skipping ones already taken.
     *
     * [exclude] is the set of entry ids that already have a card of the kind
     * being introduced. Passing it in rather than joining against the study
     * database keeps the two files independent.
     */
    fun deckEntries(deck: Deck, exclude: Set<Long>, limit: Int): List<Entry> {
        if (deck.custom) return emptyList()
        val where = StringBuilder("1 = 1")
        val args = mutableListOf<String>()
        deck.cefr?.let { where.append(" AND cefr = ?").also { _ -> args.add(it) } }
        deck.kind?.let { where.append(" AND kind = ?").also { _ -> args.add(it.code) } }
        deck.list?.let {
            where.append(" AND (lists = ? OR lists LIKE ? OR lists LIKE ? OR lists LIKE ?)")
            args.add(it); args.add("$it|%"); args.add("%|$it"); args.add("%|$it|%")
        }
        if (deck.kind == null) where.append(" AND kind = 'word'")
        where.append(" AND $TEACHABLE")
        val out = ArrayList<Entry>(limit)
        db.rawQuery(
            "SELECT $entryColumns FROM entry e WHERE $where ORDER BY rank LIMIT ?",
            (args + ((limit + exclude.size) * 2).coerceAtMost(20000).toString()).toTypedArray(),
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val e = readEntry(c)
                if (e.id !in exclude) out.add(e)
            }
        }
        return out
    }

    fun deckSize(deck: Deck): Int {
        if (deck.custom) return 0
        val where = StringBuilder("1 = 1")
        val args = mutableListOf<String>()
        deck.cefr?.let { where.append(" AND cefr = ?").also { _ -> args.add(it) } }
        deck.kind?.let { where.append(" AND kind = ?").also { _ -> args.add(it.code) } }
        deck.list?.let {
            where.append(" AND (lists = ? OR lists LIKE ? OR lists LIKE ? OR lists LIKE ?)")
            args.add(it); args.add("$it|%"); args.add("%|$it"); args.add("%|$it|%")
        }
        if (deck.kind == null) where.append(" AND kind = 'word'")
        where.append(" AND $TEACHABLE")
        return db.rawQuery("SELECT COUNT(*) FROM entry e WHERE $where", args.toTypedArray())
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun search(query: String, limit: Int = 60): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        db.rawQuery(
            "SELECT $entryColumns FROM entry WHERE lemma = ? OR lemma LIKE ? " +
                "ORDER BY (lemma = ?) DESC, rank LIMIT ?",
            arrayOf(q, "$q%", q, limit.toString()),
        ).use { c -> while (c.moveToNext()) out.add(readEntry(c)) }
        if (out.size < limit) {
            // Fall back to the Japanese side so 「延期」 finds `put off`.
            db.rawQuery(
                "SELECT $entryColumns FROM entry WHERE ja LIKE ? ORDER BY rank LIMIT ?",
                arrayOf("%$query%", (limit - out.size).toString()),
            ).use { c ->
                val seen = out.mapTo(HashSet()) { it.id }
                while (c.moveToNext()) readEntry(c).takeIf { it.id !in seen }?.let(out::add)
            }
        }
        return out
    }

    // ---- senses -------------------------------------------------------------

    private fun readSense(c: Cursor) = Sense(
        id = c.getLong(0),
        entryId = c.getLong(1),
        ord = c.getInt(2),
        ja = c.getString(3).splitField().dedupeGlosses(),
        jaDefinition = c.getString(4),
        definition = c.getString(5),
        parent = c.getString(6),
        tags = c.getString(7).splitField(),
        topics = c.getString(8).splitField(),
        synonyms = c.getString(9).splitField(),
        antonyms = c.getString(10).splitField(),
        semcor = c.getInt(11),
        synset = c.getString(12),
    )

    private val senseColumns =
        "id, entry_id, ord, ja, ja_def, def_en, parent, tags, topics, syn, ant, semcor, synset"

    /**
     * The meanings of a word — one per *memory*, not one per WordNet row.
     *
     * WordNet splits senses far finer than a Japanese gloss can follow. Of the
     * 3,743 words that carry sense frequencies, 1,572 (42%) have a first and a
     * second meaning whose Japanese is the same word, and the damage shows up
     * twice: the ratio bar on the word's page reads "為す 62% ／ 為す 32%", and
     * the context card — which draws its wrong answers from the word's *own*
     * other meanings — offers two options nobody can tell apart.
     *
     * So: **if two senses of a word share any Japanese gloss, they are one
     * memory, and they are folded together.** Their frequencies add, their
     * glosses and labels join, and the earliest row stands for the group, which
     * keeps both the canonical order and the card keys already in the study
     * database pointing somewhere real.
     *
     * The distinctions this app exists to teach are untouched by that rule,
     * because genuinely different meanings get genuinely different Japanese:
     * `spare` is 予備の ／ 惜しむ ／ 見逃す, with no word in common.
     */
    fun senses(entryId: Long): List<Sense> {
        val out = ArrayList<Sense>()
        db.rawQuery(
            "SELECT $senseColumns FROM sense WHERE entry_id = ? ORDER BY ord",
            arrayOf(entryId.toString()),
        ).use { c -> while (c.moveToNext()) out.add(readSense(c)) }
        return foldSharedGlosses(out)
    }

    /** Senses of one word, grouped so that no two of them share a gloss. */
    private fun foldSharedGlosses(senses: List<Sense>): List<Sense> {
        if (senses.size < 2) return senses

        // Union-find over "shares a Japanese gloss", keeping the lowest index as
        // each group's root so the result stays in the database's sense order.
        val parent = IntArray(senses.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) {
                parent[root] = parent[parent[root]]
                root = parent[root]
            }
            return root
        }
        val firstSeenAt = HashMap<String, Int>()
        for (i in senses.indices) {
            for (gloss in senses[i].ja) {
                val seen = firstSeenAt[gloss]
                if (seen == null) {
                    firstSeenAt[gloss] = i
                } else {
                    val a = find(seen)
                    val b = find(i)
                    if (a != b) parent[maxOf(a, b)] = minOf(a, b)
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<Sense>>()
        for (i in senses.indices) groups.getOrPut(find(i)) { ArrayList() }.add(senses[i])
        if (groups.size == senses.size) return senses

        return groups.values.map { members ->
            val head = members.first()
            if (members.size == 1) {
                head
            } else {
                head.copy(
                    // Capped at the width a single row already uses: a merged
                    // meaning has to stay readable as one answer on a button.
                    ja = members.flatMap { it.ja }.dedupeGlosses().take(MAX_GLOSSES),
                    tags = members.flatMap { it.tags }.distinct(),
                    topics = members.flatMap { it.topics }.distinct(),
                    synonyms = members.flatMap { it.synonyms }.distinct(),
                    antonyms = members.flatMap { it.antonyms }.distinct(),
                    semcor = members.sumOf { it.semcor },
                    ids = members.map { it.id },
                )
            }
        }
    }

    fun sense(id: Long): Sense? =
        db.rawQuery("SELECT $senseColumns FROM sense WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) readSense(it) else null }

    // ---- morphology ---------------------------------------------------------

    /** How a word breaks up, in reading order. Empty when Wiktionary never said. */
    fun morphemes(entryId: Long): List<Morpheme> {
        val out = ArrayList<Morpheme>()
        db.rawQuery(
            "SELECT form, kind, affix_id, gloss FROM morph WHERE entry_id = ? ORDER BY ord",
            arrayOf(entryId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Morpheme(
                        form = c.getString(0),
                        kind = Morpheme.Kind.of(c.getString(1)),
                        affixId = c.getLong(2),
                        gloss = c.getString(3),
                    ),
                )
            }
        }
        return out
    }

    private fun readAffix(c: Cursor) = Affix(
        id = c.getLong(0),
        form = c.getString(1),
        kind = Morpheme.Kind.of(c.getString(2)),
        gloss = c.getString(3).splitField(),
        ja = c.getString(4).splitField(),
        uses = c.getInt(5),
    )

    private val affixColumns = "id, form, kind, gloss, ja, uses"

    fun affix(id: Long): Affix? =
        db.rawQuery("SELECT $affixColumns FROM affix WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) readAffix(it) else null }

    fun affix(form: String): Affix? =
        db.rawQuery("SELECT $affixColumns FROM affix WHERE form = ?", arrayOf(form))
            .use { if (it.moveToFirst()) readAffix(it) else null }

    /** The affixes worth a page, the most productive first. */
    fun affixes(kind: Morpheme.Kind? = null, limit: Int = 200): List<Affix> {
        val out = ArrayList<Affix>()
        val where = if (kind == null) "" else "WHERE kind = '${kind.code}' "
        db.rawQuery(
            "SELECT $affixColumns FROM affix ${where}ORDER BY uses DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> while (c.moveToNext()) out.add(readAffix(c)) }
        return out
    }

    /**
     * The grid, read down a stem: hold `duce` still and the prefixes line up.
     *
     * `reduce`, `deduce` and `produce` differ in exactly one piece, and putting
     * that piece in a column is the thing a paper etymology book cannot do —
     * paper has one dimension, so it can only ever list a stem's children.
     *
     * Stems are matched as Wiktionary spells them and are not normalised, so
     * `duce` and `duct` stay apart. What unites *those* is the Proto-Indo-European
     * root, which [family] already answers; inventing a stem neither word shows
     * would be the same mistake as stripping letters off the front.
     */
    fun gridByStem(stem: String, limit: Int = 60): List<GridCell> =
        grid("SELECT entry_id FROM morph WHERE form = ? AND kind = 'stem'", stem, limit) {
            it.form == stem && it.kind == Morpheme.Kind.STEM
        }

    /**
     * The same grid read the other way: hold `re-` still and the stems line up.
     *
     * This is the direction that pays. Seeing that `re-` does the same job in
     * reduce, reject, report and resist is what makes the next unknown `re-`
     * word guessable, and it is only visible once the axis can be flipped.
     */
    fun gridByAffix(affixId: Long, limit: Int = 60): List<GridCell> =
        grid(
            "SELECT entry_id FROM morph WHERE affix_id = ?", affixId.toString(), limit,
        ) { it.affixId == affixId }

    private fun grid(
        sql: String,
        arg: String,
        limit: Int,
        isVarying: (Morpheme) -> Boolean,
    ): List<GridCell> {
        val ids = ArrayList<Long>()
        db.rawQuery("$sql LIMIT ?", arrayOf(arg, (limit * 2).toString()))
            .use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        val cells = ArrayList<GridCell>()
        for (id in ids) {
            val entry = entry(id) ?: continue
            val parts = morphemes(id)
            // Along a stem the prefix varies; along an affix the rest of the
            // word does. Either way there must be exactly one of each or the
            // row has nothing to say.
            val fixed = parts.firstOrNull(isVarying) ?: continue
            val varying = parts.firstOrNull { it !== fixed } ?: continue
            cells.add(GridCell(entry = entry, varying = varying, fixed = fixed))
        }
        // Commonest words first: the grid is read top to bottom as a lesson.
        return cells.sortedBy { it.entry.rank }.take(limit)
    }

    fun senseExamples(senseId: Long): List<Example> = senseExamples(listOf(senseId))

    /**
     * Examples for a meaning, gathered from every row behind it.
     *
     * A folded sense would otherwise show only the examples of the row that
     * happened to come first, discarding the rest for no reason.
     */
    fun senseExamples(sense: Sense): List<Example> = senseExamples(sense.sourceIds)

    private fun senseExamples(senseIds: List<Long>): List<Example> {
        if (senseIds.isEmpty()) return emptyList()
        val out = ArrayList<Example>()
        val holes = senseIds.joinToString(",") { "?" }
        db.rawQuery(
            "SELECT en, ja FROM sense_example WHERE sense_id IN ($holes)",
            senseIds.map { it.toString() }.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(Example(c.getString(0), c.getString(1))) }
        return out
    }

    /**
     * Japanese meanings of *other* words, for multiple choice.
     *
     * Drawn from entries of a nearby rank so the wrong answers are the same
     * difficulty as the right one — distractors that are obviously too easy
     * turn a meaning question into a reading-speed question. And written the
     * same way as the right one.
     *
     * [glosses] is how many Japanese words the correct option lists, and the
     * wrong ones list the same number. They did not, and the effect was that on
     * 78% of the meaning cards the correct answer was the only option with a
     * 「、」 in it — 「方策、策、術」 against 「毛布」「予算」「病院」. A learner
     * who cannot read the English at all could pick the long one and be right
     * five times in six, which means the card measured nothing.
     *
     * The wrong answers are drawn from words the decks would teach, for the same
     * reason the decks won't: a card offering 「ベリリウム」 against `if` puts
     * the element on the screen as though it were a thing to learn, and the
     * learner has no way to know it is only there to be rejected.
     */
    fun distractorMeanings(
        near: Entry,
        exclude: Set<String>,
        count: Int,
        glosses: Int = 1,
    ): List<String> {
        val out = LinkedHashSet<String>()
        val used = HashSet(exclude)

        fun consider(id: Long, field: String): Boolean {
            if (id in untaught) return false
            val words = field.splitField().dedupeGlosses().take(glosses)
            // Same shape or nothing: an option with fewer words than the rest is
            // as much of a tell as one with more.
            if (words.size < glosses || words.any { it in used }) return false
            used.addAll(words)
            return out.add(words.joinToString("、"))
        }

        val window = 400
        db.rawQuery(
            "SELECT id, ja FROM entry WHERE pos = ? AND ja <> '' AND rank BETWEEN ? AND ? " +
                "AND id <> ? ORDER BY RANDOM() LIMIT ?",
            arrayOf(
                near.pos.code, (near.rank - window).coerceAtLeast(1).toString(),
                (near.rank + window).toString(), near.id.toString(), (count * 12).toString(),
            ),
        ).use { c ->
            while (c.moveToNext() && out.size < count) consider(c.getLong(0), c.getString(1))
        }
        if (out.size < count) {
            db.rawQuery(
                "SELECT id, ja FROM entry WHERE ja <> '' AND id <> ? ORDER BY RANDOM() LIMIT ?",
                arrayOf(near.id.toString(), (count * 12).toString()),
            ).use { c ->
                while (c.moveToNext() && out.size < count) consider(c.getLong(0), c.getString(1))
            }
        }
        return out.toList()
    }

    // ---- sentences, collocations, relations ---------------------------------

    /** A Tatoeba pair, the surface form of the entry in it, and its id. */
    data class LinkedSentence(val id: Long, val example: Example, val surface: String)

    fun sentences(entryId: Long, limit: Int = 6): List<LinkedSentence> {
        val out = ArrayList<LinkedSentence>()
        db.rawQuery(
            "SELECT s.id, s.en, s.ja, se.surface FROM sentence_entry se " +
                "JOIN sentence s ON s.id = se.sentence_id WHERE se.entry_id = ? LIMIT ?",
            arrayOf(entryId.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LinkedSentence(
                        c.getLong(0), Example(c.getString(1), c.getString(2)), c.getString(3),
                    )
                )
            }
        }
        return out
    }

    fun sentence(id: Long): Example? =
        db.rawQuery("SELECT en, ja FROM sentence WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) Example(it.getString(0), it.getString(1)) else null }

    /**
     * A pool of Japanese sentences to draw a 和文英訳 drill from.
     *
     * Drawn at random inside a length window and filtered afterwards by what the
     * learner can read, rather than chosen by any property of the sentence
     * itself: the corpus carries no difficulty label, and the only definition of
     * "too hard" that matters here is this particular learner's vocabulary. The
     * window keeps a sentence long enough to have a shape and short enough to
     * hold in the head while writing it.
     */
    fun writingPool(minWords: Int = 6, maxWords: Int = 18, limit: Int = 60): List<Example> {
        val out = ArrayList<Example>(limit)
        // The bounds go into the statement rather than into arguments: a bound
        // argument arrives as text, and SQLite sorts every integer below every
        // string, so `BETWEEN '6' AND '18'` against a computed word count is
        // false for every row in the table. They are Ints, so there is nothing
        // to escape.
        db.rawQuery(
            "SELECT en, ja FROM sentence WHERE ja <> '' " +
                "AND (length(en) - length(replace(en, ' ', '')) + 1) " +
                "BETWEEN $minWords AND $maxWords " +
                // Contracted forms are not in the dictionary, so `can't` would be
                // measured as an unknown word and then asked for as one. They are
                // also not what 和文英訳 is marked on.
                "AND en NOT LIKE '%''%' AND en NOT LIKE '%’%' " +
                "ORDER BY RANDOM() LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(Example(c.getString(0), c.getString(1)))
        }
        return out
    }

    /**
     * Every English the corpus pairs with one Japanese sentence.
     *
     * Tatoeba often has several, and they are all right — `失礼だが、上記の記事に
     * ある３つの誤りを指摘しておきたい。` arrives with both `Excuse me; allow me to
     * point out…` and `Excuse me, let me point out…`. A drill that held up one of
     * them as *the* answer would be marking the corpus, not the learner.
     */
    fun parallels(ja: String, limit: Int = 8): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        db.rawQuery(
            "SELECT id, en FROM sentence WHERE ja = ? LIMIT ?",
            arrayOf(ja, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getString(1)
        }
        return out
    }

    /**
     * Collocations whose two halves both appear in a piece of writing.
     *
     * Only ever used to say what *is* attested. The table is 15,088 pairs, which
     * is nowhere near all the English there is, so a pairing missing from it is
     * no evidence of anything and is never reported as a mistake.
     */
    fun collocationsAmong(lemmas: Collection<String>, limit: Int = 40): List<Collocation> {
        if (lemmas.size < 2) return emptyList()
        val words = lemmas.map { it.lowercase() }.distinct().take(120)
        val holes = words.joinToString(",") { "?" }
        val args = words.map { it } + words + listOf(limit.toString())
        val out = ArrayList<Collocation>()
        db.rawQuery(
            "SELECT entry_id, pattern, head, collocate, phrase, count, example " +
                "FROM collocation WHERE head IN ($holes) AND collocate IN ($holes) " +
                "ORDER BY score DESC LIMIT ?",
            args.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Collocation(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getInt(5), c.getString(6),
                    )
                )
            }
        }
        return out
    }

    fun collocations(entryId: Long, limit: Int = 10): List<Collocation> {
        val out = ArrayList<Collocation>()
        db.rawQuery(
            "SELECT entry_id, pattern, head, collocate, phrase, count, example " +
                "FROM collocation WHERE entry_id = ? ORDER BY score DESC LIMIT ?",
            arrayOf(entryId.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Collocation(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getInt(5), c.getString(6),
                    )
                )
            }
        }
        return out
    }

    /** Other collocates of the same head, as wrong answers for a collocation card. */
    fun collocationDistractors(pattern: String, exclude: Set<String>, count: Int): List<String> {
        val out = LinkedHashSet<String>()
        db.rawQuery(
            "SELECT collocate FROM collocation WHERE pattern = ? ORDER BY RANDOM() LIMIT ?",
            arrayOf(pattern, (count * 5).toString()),
        ).use { c ->
            while (c.moveToNext() && out.size < count) {
                val w = c.getString(0)
                if (w !in exclude) out.add(w)
            }
        }
        return out.toList()
    }

    /**
     * Every entry the decks will never teach. See [TEACHABLE]; loaded once,
     * because [surfaces] needs the answer for a few hundred words at a time and
     * the sibling test is too slow to ask per row.
     */
    internal val untaught: Set<Long> by lazy {
        val out = HashSet<Long>()
        db.rawQuery("SELECT id FROM entry e WHERE NOT ($TEACHABLE)", null)
            .use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        out
    }

    /**
     * Match spellings from a text to entries.
     *
     * Returns the best entry per spelling: where a form belongs to several
     * entries (`run` the noun and the verb), the one taught earliest wins, since
     * that is the reading a learner meets first and the one their card records.
     *
     * "Taught" has to mean it, though. `so` the conjunction outranks `so` the
     * adverb and `one` the numeral outranks `one` the noun, and neither of those
     * is ever taught — so reading would credit a memory that can never exist and
     * the word would count as unknown however much the learner studied it. A
     * spelling goes to the best entry the decks can actually teach, and only
     * falls back to the rest when there is none: reading still has to recognise
     * every word, including the ones nobody is quizzed on.
     */
    fun surfaces(forms: Collection<String>): Map<String, Long> {
        if (forms.isEmpty()) return emptyMap()
        val best = HashMap<String, Pair<Long, Int>>()
        val fallback = HashMap<String, Pair<Long, Int>>()
        forms.chunked(300).forEach { chunk ->
            val holes = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT s.form, s.entry_id, e.rank FROM surface s " +
                    "JOIN entry e ON e.id = s.entry_id WHERE s.form IN ($holes)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val form = c.getString(0)
                    val id = c.getLong(1)
                    val rank = c.getInt(2)
                    val into = if (id in untaught) fallback else best
                    val current = into[form]
                    if (current == null || rank < current.second) into[form] = id to rank
                }
            }
        }
        fallback.forEach { (form, hit) -> best.putIfAbsent(form, hit) }
        return best.mapValues { it.value.first }
    }

    // ---- word families ------------------------------------------------------

    private fun readFamily(c: Cursor) = RootFamily(
        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
        c.getString(4), c.getString(5),
    )

    fun family(id: Long): RootFamily? =
        db.rawQuery(
            "SELECT id, pattern, pie, lang, form, gloss FROM root WHERE id = ?",
            arrayOf(id.toString()),
        ).use { if (it.moveToFirst()) readFamily(it) else null }

    /** Every family, largest first: the app's etymology index. */
    fun families(): List<Pair<RootFamily, Int>> {
        val out = ArrayList<Pair<RootFamily, Int>>()
        db.rawQuery(
            "SELECT r.id, r.pattern, r.pie, r.lang, r.form, r.gloss, " +
                "COUNT(DISTINCT e.lemma) FROM root r JOIN entry e ON e.root_id = r.id " +
                "GROUP BY r.id ORDER BY COUNT(DISTINCT e.lemma) DESC",
            null,
        ).use { c -> while (c.moveToNext()) out.add(readFamily(c) to c.getInt(6)) }
        return out
    }

    /**
     * The words of a family, one per spelling.
     *
     * Entries are per part of speech, so `reject` the noun and the verb are two
     * rows; a family list wants the word once.
     */
    fun familyMembers(rootId: Long, limit: Int = 40): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        db.rawQuery(
            "SELECT $entryColumns FROM entry WHERE root_id = ? ORDER BY rank LIMIT ?",
            arrayOf(rootId.toString(), (limit * 3).toString()),
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val e = readEntry(c)
                if (seen.add(e.lemma)) out.add(e)
            }
        }
        return out
    }

    /** Words this one is easy to mix up with: adapt / adopt / adept. */
    fun confusables(entryId: Long, limit: Int = 3): List<Entry> =
        relations(entryId, limit = 12)
            .filter { it.kind == RelationKind.CONFUSE && it.other.ja.isNotEmpty() }
            .map { it.other }
            .take(limit)

    fun relations(entryId: Long, limit: Int = 12): List<Relation> {
        val pairs = ArrayList<Pair<Long, RelationKind>>()
        db.rawQuery(
            "SELECT b, kind FROM relation WHERE a = ? UNION ALL " +
                "SELECT a, kind FROM relation WHERE b = ? LIMIT ?",
            arrayOf(entryId.toString(), entryId.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) pairs.add(c.getLong(0) to RelationKind.of(c.getString(1)))
        }
        val entries = entries(pairs.map { it.first })
        return pairs.mapNotNull { (id, kind) -> entries[id]?.let { Relation(it, kind) } }
    }

    fun close() = db.close()

    companion object {
        /**
         * Bumped whenever a release ships a different content database.
         *
         * 2: the Japanese of each meaning is ordered by how common the word is
         * rather than by whether JMdict calls it common at all, which had
         * `child` arriving as 「キッド」 and `money` as 「ゲル」.
         */
        const val VERSION = 2

        /** How many Japanese glosses one meaning may show. */
        private const val MAX_GLOSSES = 5

        /**
         * A meaning that is a chemical symbol, an element or the name of a
         * letter — true of the spelling, and never what a learner is asking
         * when they ask what the word means. Only ever applied to one- and
         * two-letter spellings: `sodium` really does mean ナトリウム.
         */
        private const val SYMBOL =
            "s.def_en LIKE '%chemical element%' OR s.def_en LIKE '%metallic element%' " +
                "OR s.def_en LIKE '%nonmetallic element%' " +
                "OR s.def_en LIKE '%radioactive%element%' " +
                "OR s.def_en LIKE '%The name of the%letter%' " +
                "OR s.def_en LIKE '%symbol for%'"

        /**
         * What may be *taught*, as opposed to what the dictionary knows.
         *
         * The dictionary is built from Wiktionary and keeps everything it can
         * stand behind, including facts nobody should be quizzed on. Two of
         * those leak into the decks, and both did:
         *
         * - **Structure is not vocabulary.** See [Entry.isStructural]. The
         *   ordinary senses of `be`, `and`, `we` carry no Japanese, so the gloss
         *   that survived was the leftover one — `be` 「ベリリウム」, `and`
         *   「アンド」, `we` 「朕」 — and because rank is the lemma's, these sat
         *   at the very top of the A1 deck. They were the first cards anyone saw.
         *
         * - **A spelling with several parts of speech is taught in the one the
         *   corpus actually uses.** `say` is a verb; `say` the noun (意見) is
         *   real English and not what anyone means by learning `say`. Where a
         *   sense-tagged corpus attests one entry of a lemma and not another,
         *   only the attested one is taught. Where it attests none — SemCor
         *   covers 360,000 words, not the language — nothing is dropped, so a
         *   word it never saw (`bike`, `cake`, `burger`) is unaffected.
         *
         * - **A spelling of one or two letters has to be vouched for.** The
         *   dictionary holds the chemical symbols, the letter names, the units
         *   and the US state codes: `cd` 「カドミウム」, `mm` 「ミリメートル」,
         *   `sc` 「スカンジウム」, `el` 「エル」, `vt` 「バーモント州」. None is a
         *   word anybody learns, and the published word lists agree — they are
         *   the ones with no CEFR-J, NGSL, NAWL or TSL entry at all. A short
         *   spelling is taught when a list has it (`go` `up` `no` `hi` `ox`
         *   `pi`) and not otherwise, and never when the meaning that would be
         *   taught is an element or a letter (`am` 「アメリシウム」).
         *
         * Nothing is deleted: every row stays searchable in the dictionary,
         * where `be` 「ベリリウム」 is a true thing to be able to look up.
         */
        private const val TEACHABLE =
            "e.rank > ${Entry.STRUCTURE_RANK} AND (" +
                "EXISTS (SELECT 1 FROM sense s WHERE s.entry_id = e.id " +
                "AND s.ja <> '' AND s.semcor > 0) OR NOT EXISTS (" +
                // The index hint is not decoration: without it the planner walks
                // entry(kind, rank) for every candidate and counting one deck
                // takes twelve seconds.
                "SELECT 1 FROM entry o INDEXED BY entry_lemma " +
                "WHERE o.lemma = e.lemma AND o.id <> e.id AND o.kind = 'word' " +
                "AND o.rank > ${Entry.STRUCTURE_RANK} AND EXISTS (" +
                "SELECT 1 FROM sense s WHERE s.entry_id = o.id " +
                "AND s.ja <> '' AND s.semcor > 0))) AND (" +
                // The length test comes first so the rest is only ever asked
                // about the few hundred one- and two-letter spellings.
                "length(e.lemma) > 2 OR (e.lists <> '' AND NOT EXISTS (" +
                "SELECT 1 FROM sense s WHERE s.entry_id = e.id AND s.ja <> '' " +
                "AND s.ord = (SELECT MIN(m.ord) FROM sense m " +
                "WHERE m.entry_id = e.id AND m.ja <> '') AND ($SYMBOL))))"

        // Deliberately not named `.gz`: the Android asset merger expands any
        // asset with that extension at build time, which would put 31 MB of
        // uncompressed database into the APK.
        private const val ASSET = "content.dbz"
        private const val FILE = "content.db"

        fun open(context: Context): ContentDb {
            val file = File(context.filesDir, FILE)
            val stamp = File(context.filesDir, "content.version")
            val installed = stamp.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
            if (!file.exists() || installed != VERSION) {
                install(context, file)
                stamp.writeText(VERSION.toString())
            }
            val db = SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READONLY,
            )
            return ContentDb(db)
        }

        private fun install(context: Context, target: File) {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            context.assets.open(ASSET).use { raw ->
                GZIPInputStream(raw, 1 shl 16).use { gz ->
                    tmp.outputStream().buffered(1 shl 16).use { out -> gz.copyTo(out) }
                }
            }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "could not install the content database" }
        }
    }
}

internal fun String.splitField(): List<String> =
    if (isEmpty()) emptyList() else split('|').filter { it.isNotEmpty() }

/**
 * A word's Japanese, with the same word removed when it is written twice.
 *
 * WordNet takes its katakana from more than one source and they spell it to
 * taste: `measure` carries 「クオンティティ」「クォンティティー」「クォンティティ」,
 * `week` 「ウィーク」 and 「ウイーク」. To the database these are distinct strings;
 * to a reader they are one word, and side by side on a button they read as a
 * list of five meanings where there are three. 185 sense rows even repeat a
 * gloss character for character (`他の｜他の｜別の｜別の`), which merging sense
 * rows produced and nothing removed.
 *
 * Only katakana is folded, and only over the marks that carry no distinction of
 * meaning: the long vowel `ー`, the interpunct, and small kana written large.
 * `毛` and `気` are never touched, because in kanji a different character is a
 * different word.
 */
internal fun List<String>.dedupeGlosses(): List<String> {
    if (size < 2) return this
    val seen = HashSet<String>(size * 2)
    return filter { seen.add(if (KATAKANA.matches(it)) foldKatakana(it) else it) }
}

private val KATAKANA = Regex("[ァ-ヿ・ｰ]+")

private const val SMALL = "ァィゥェォャュョヮヵヶ"
private const val LARGE = "アイウエオヤユヨワカケ"

private fun foldKatakana(text: String): String = buildString(text.length) {
    text.forEach { c ->
        when (val i = SMALL.indexOf(c)) {
            -1 -> if (c != 'ー' && c != '・' && c != 'ｰ') append(c)
            else -> append(LARGE[i])
        }
    }
}
