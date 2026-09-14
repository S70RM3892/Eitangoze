package com.eitangoze.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The reading library that ships with the app: passages, already parsed.
 *
 * Nobody is asked to paste anything. Pasting is where almost everyone stops, so
 * the passages are here from the first launch and the app decides which one to
 * hand out, from the reader's own coverage rather than from a menu.
 *
 * Its own file, separate from [ContentDb]. The vocabulary database is expensive
 * to rebuild and rarely changes; the library is the opposite — it is meant to
 * keep growing — and a release that only adds reading should not have to ship
 * 16 MB of unchanged dictionary with it.
 *
 * The dependency trees were computed once, at build time, by two parsers that
 * had to agree (see `tools/step8_syntax.py`). Nothing is parsed on the phone.
 */
class PassageDb private constructor(private val db: SQLiteDatabase) {

    /**
     * How many passages the library offers — counted, not read off the build
     * metadata, because a handful of what was collected is not English and is
     * never shelved. See [READABLE].
     */
    val passageCount: Int by lazy {
        db.rawQuery("SELECT COUNT(*) FROM passage WHERE $READABLE", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }
    val sentenceCount: Int by lazy { meta("sentences")?.toIntOrNull() ?: 0 }
    val agreedCount: Int by lazy { meta("agreed")?.toIntOrNull() ?: 0 }

    fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    // ---- passages -----------------------------------------------------------

    private val passageColumns =
        "id, title, genre, source, url, license, words, cefr, coverable, text"

    private fun readPassage(c: Cursor) = Passage(
        id = c.getLong(0),
        title = c.getString(1),
        genre = Genre.of(c.getString(2)),
        source = c.getString(3),
        url = c.getString(4),
        license = c.getString(5),
        words = c.getInt(6),
        cefr = c.getString(7),
        coverable = c.getDouble(8),
        text = c.getString(9),
    )

    fun passage(id: Long): Passage? =
        db.rawQuery("SELECT $passageColumns FROM passage WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) readPassage(it) else null }

    /**
     * Candidate passages, newest constraint first: genre, then level.
     *
     * Deliberately more than are needed. Which one is actually handed out is
     * decided against the reader's own coverage, which only the study database
     * knows, so the choice belongs in [Repository] and not in a SQL clause.
     */
    fun passages(
        genre: Genre? = null,
        cefr: String? = null,
        limit: Int = 40,
    ): List<Passage> {
        val where = buildList {
            add(READABLE)
            if (genre != null) add("genre = '${genre.code}'")
            if (cefr != null) add("cefr = '${cefr}'")
        }.joinToString(" AND ")
        val out = ArrayList<Passage>()
        db.rawQuery(
            "SELECT $passageColumns FROM passage WHERE $where ORDER BY RANDOM() LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> while (c.moveToNext()) out.add(readPassage(c)) }
        return out
    }

    /** How many passages sit at each level, per genre. Drives the library screen. */
    fun shelf(): List<Triple<Genre, String, Int>> {
        val out = ArrayList<Triple<Genre, String, Int>>()
        db.rawQuery(
            "SELECT genre, cefr, COUNT(*) FROM passage WHERE $READABLE " +
                "GROUP BY genre, cefr", null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Triple(Genre.of(c.getString(0)), c.getString(1), c.getInt(2)))
            }
        }
        return out
    }

    // ---- sentences ----------------------------------------------------------

    fun sentences(passageId: Long): List<ParsedSentence> {
        val out = ArrayList<ParsedSentence>()
        db.rawQuery(
            "SELECT ord, start, end, agreed, offsets, heads, deps, pos, folds, roles " +
                "FROM sentence WHERE passage_id = ? ORDER BY ord",
            arrayOf(passageId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ParsedSentence(
                        ord = c.getInt(0),
                        start = c.getInt(1),
                        end = c.getInt(2),
                        confirmed = c.getInt(3) == 1,
                        tokens = parseOffsets(c.getString(4)),
                        heads = parseInts(c.getString(5)),
                        deps = splitCommas(c.getString(6)),
                        pos = splitCommas(c.getString(7)),
                        folds = parseFolds(c.getString(8)),
                        roles = parseRoles(c.getString(9)),
                    ),
                )
            }
        }
        return out
    }

    fun close() = db.close()

    companion object {
        /** Bumped whenever a release ships a different passage library. */
        const val VERSION = 1

        /** The longest a passage can be and still be one exam passage. */
        private const val LONGEST = 1200

        /**
         * A passage worth handing to a reader of English.
         *
         * `coverable` is the share of a passage's running words the dictionary
         * can account for at all — its ceiling, whatever anyone studies. Four of
         * the 4,126 collected sit far under it: a Korean-language paper that
         * reached the medicine genre, a volume of Cicero that is mostly Latin,
         * and two biographies thick with names. They are not hard English, they
         * are not English, and putting one in front of a learner as "the passage
         * for you" would be the app failing loudly.
         *
         * Nine more are simply too long — up to 2,499 words, where the splitter
         * ran past the end of a Gutenberg essay. 京大 sets 730 and 580; a
         * passage of that length is the exercise, and one of 2,499 is three of
         * them with a reading speed measured across the lot.
         */
        private const val READABLE = "coverable >= 0.5 AND words <= $LONGEST"

        // Not `.gz`: the Android asset merger expands assets with that extension.
        private const val ASSET = "passages.dbz"
        private const val FILE = "passages.db"

        fun open(context: Context): PassageDb {
            val file = File(context.filesDir, FILE)
            val stamp = File(context.filesDir, "passages.version")
            val installed = stamp.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
            if (!file.exists() || installed != VERSION) {
                install(context, file)
                stamp.writeText(VERSION.toString())
            }
            val db = SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READONLY,
            )
            return PassageDb(db)
        }

        private fun install(context: Context, target: File) {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            context.assets.open(ASSET).use { raw ->
                GZIPInputStream(raw, 1 shl 16).use { gz ->
                    tmp.outputStream().buffered(1 shl 16).use { out -> gz.copyTo(out) }
                }
            }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "could not install the passage library" }
        }

        // ---- the packed columns ---------------------------------------------
        //
        // Everything a sentence needs is one comma-separated string per column.
        // A row is read whole and never queried into, so a compact encoding
        // costs nothing at read time and saves a table of a million rows.

        internal fun splitCommas(field: String): List<String> =
            if (field.isEmpty()) emptyList() else field.split(',')

        internal fun parseInts(field: String): List<Int> =
            splitCommas(field).mapNotNull { it.toIntOrNull() }

        internal fun parseOffsets(field: String): List<IntRange> =
            splitCommas(field).mapNotNull {
                val cut = it.indexOf(':')
                val start = it.substring(0, cut).toIntOrNull() ?: return@mapNotNull null
                val end = it.substring(cut + 1).toIntOrNull() ?: return@mapNotNull null
                start until end
            }

        internal fun parseFolds(field: String): List<Fold> =
            splitCommas(field).mapNotNull {
                val parts = it.split(':')
                if (parts.size != 3) return@mapNotNull null
                val start = parts[0].toIntOrNull() ?: return@mapNotNull null
                val end = parts[1].toIntOrNull() ?: return@mapNotNull null
                Fold(start, end, parts[2])
            }

        internal fun parseRoles(field: String): Map<Int, String> =
            splitCommas(field).mapNotNull {
                val cut = it.indexOf(':')
                val index = it.substring(0, cut).toIntOrNull() ?: return@mapNotNull null
                index to it.substring(cut + 1)
            }.toMap()
    }
}
