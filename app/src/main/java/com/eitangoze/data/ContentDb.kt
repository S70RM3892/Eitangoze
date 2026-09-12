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
        ja = c.getString(15).splitField(),
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
        val out = ArrayList<Entry>(limit)
        db.rawQuery(
            "SELECT $entryColumns FROM entry WHERE $where ORDER BY rank LIMIT ?",
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
        return db.rawQuery("SELECT COUNT(*) FROM entry WHERE $where", args.toTypedArray())
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
        ja = c.getString(3).splitField(),
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

    fun senses(entryId: Long): List<Sense> {
        val out = ArrayList<Sense>()
        db.rawQuery(
            "SELECT $senseColumns FROM sense WHERE entry_id = ? ORDER BY ord",
            arrayOf(entryId.toString()),
        ).use { c -> while (c.moveToNext()) out.add(readSense(c)) }
        return out
    }

    fun sense(id: Long): Sense? =
        db.rawQuery("SELECT $senseColumns FROM sense WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) readSense(it) else null }

    fun senseExamples(senseId: Long): List<Example> {
        val out = ArrayList<Example>()
        db.rawQuery(
            "SELECT en, ja FROM sense_example WHERE sense_id = ?",
            arrayOf(senseId.toString()),
        ).use { c -> while (c.moveToNext()) out.add(Example(c.getString(0), c.getString(1))) }
        return out
    }

    /**
     * Japanese meanings of *other* words, for multiple choice.
     *
     * Drawn from entries of a nearby rank so the wrong answers are the same
     * difficulty as the right one — distractors that are obviously too easy
     * turn a meaning question into a reading-speed question.
     */
    fun distractorMeanings(near: Entry, exclude: Set<String>, count: Int): List<String> {
        val out = LinkedHashSet<String>()
        val window = 400
        db.rawQuery(
            "SELECT ja FROM entry WHERE pos = ? AND ja <> '' AND rank BETWEEN ? AND ? " +
                "AND id <> ? ORDER BY RANDOM() LIMIT ?",
            arrayOf(
                near.pos.code, (near.rank - window).coerceAtLeast(1).toString(),
                (near.rank + window).toString(), near.id.toString(), (count * 4).toString(),
            ),
        ).use { c ->
            while (c.moveToNext() && out.size < count) {
                val ja = c.getString(0).splitField().firstOrNull() ?: continue
                if (ja !in exclude) out.add(ja)
            }
        }
        if (out.size < count) {
            db.rawQuery(
                "SELECT ja FROM entry WHERE ja <> '' AND id <> ? ORDER BY RANDOM() LIMIT ?",
                arrayOf(near.id.toString(), (count * 4).toString()),
            ).use { c ->
                while (c.moveToNext() && out.size < count) {
                    val ja = c.getString(0).splitField().firstOrNull() ?: continue
                    if (ja !in exclude) out.add(ja)
                }
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
     * Match spellings from a text to entries.
     *
     * Returns the best entry per spelling: where a form belongs to several
     * entries (`run` the noun and the verb), the one taught earliest wins, since
     * that is the reading a learner meets first and the one their card records.
     */
    fun surfaces(forms: Collection<String>): Map<String, Long> {
        if (forms.isEmpty()) return emptyMap()
        val best = HashMap<String, Pair<Long, Int>>()
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
                    val current = best[form]
                    if (current == null || rank < current.second) best[form] = id to rank
                }
            }
        }
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
        /** Bumped whenever a release ships a different content database. */
        const val VERSION = 1

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
