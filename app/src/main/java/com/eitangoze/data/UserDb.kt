package com.eitangoze.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.eitangoze.srs.CardPhase
import com.eitangoze.srs.Rating
import com.eitangoze.srs.SrsState

/**
 * Everything the learner owns: which cards exist, when each is due, and every
 * answer ever given.
 *
 * Separate from the shipped lexicon on purpose. Cards reference content by id
 * and by [CardKey]; if a future database drops or renumbers a row, the affected
 * card is skipped and the rest of the history is untouched.
 */
class UserDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE card (
              key TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              entry_id INTEGER NOT NULL,
              sense_id INTEGER NOT NULL DEFAULT 0,
              deck TEXT NOT NULL,
              extra TEXT NOT NULL DEFAULT '',
              stability REAL NOT NULL DEFAULT 0,
              difficulty REAL NOT NULL DEFAULT 0,
              due INTEGER NOT NULL DEFAULT 0,
              last_review INTEGER,
              phase TEXT NOT NULL DEFAULT 'NEW',
              step INTEGER NOT NULL DEFAULT 0,
              reps INTEGER NOT NULL DEFAULT 0,
              lapses INTEGER NOT NULL DEFAULT 0,
              created INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX card_due ON card(due)")
        db.execSQL("CREATE INDEX card_deck ON card(deck, kind)")
        db.execSQL("CREATE INDEX card_entry ON card(entry_id)")
        db.execSQL(
            """
            CREATE TABLE review_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              key TEXT NOT NULL,
              entry_id INTEGER NOT NULL,
              kind TEXT NOT NULL,
              rating INTEGER NOT NULL,
              at INTEGER NOT NULL,
              correct INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX review_at ON review_log(at)")
        db.execSQL("CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE picked (
              entry_id INTEGER PRIMARY KEY,
              ord INTEGER NOT NULL,
              source TEXT NOT NULL,
              added INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE marker (
              entry_id INTEGER PRIMARY KEY,
              starred INTEGER NOT NULL DEFAULT 0,
              note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations are additive only, so an upgrade never drops review history.
    }

    // ---- settings -----------------------------------------------------------

    fun setting(key: String, fallback: String): String =
        readableDatabase.rawQuery(
            "SELECT value FROM setting WHERE key = ?", arrayOf(key)
        ).use { if (it.moveToFirst()) it.getString(0) else fallback }

    fun putSetting(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT INTO setting(key, value) VALUES(?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            arrayOf(key, value),
        )
    }

    // ---- cards --------------------------------------------------------------

    fun state(key: CardKey): SrsState? =
        readableDatabase.rawQuery(
            "SELECT stability, difficulty, due, last_review, phase, step, reps, lapses " +
                "FROM card WHERE key = ?",
            arrayOf(key.value),
        ).use {
            if (!it.moveToFirst()) null else SrsState(
                stability = it.getDouble(0),
                difficulty = it.getDouble(1),
                due = it.getLong(2),
                lastReview = if (it.isNull(3)) null else it.getLong(3),
                phase = CardPhase.fromName(it.getString(4)),
                step = it.getInt(5),
                reps = it.getInt(6),
                lapses = it.getInt(7),
            )
        }

    /** Insert a card the first time it is shown. Existing cards are left alone. */
    fun introduce(key: CardKey, kind: CardKind, entryId: Long, senseId: Long,
                  deck: String, extra: String, now: Long) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO card(key, kind, entry_id, sense_id, deck, extra, " +
                "due, created) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(key.value, kind.code, entryId, senseId, deck, extra, now, now),
        )
    }

    /**
     * Put a card back in today's queue without touching what is known about it.
     *
     * Used when a failure turns up outside the study screen — a word the learner
     * could read but could not write. The memory model is left alone on purpose:
     * the drill it failed in is not the card it will be asked as, so it is
     * evidence that the card should come round again, not evidence about the
     * strength of that memory. Returns false when it was already due.
     */
    fun bringForward(key: CardKey, now: Long): Boolean =
        writableDatabase.compileStatement(
            "UPDATE card SET due = ? WHERE key = ? AND due > ?"
        ).use { statement ->
            statement.bindLong(1, now)
            statement.bindString(2, key.value)
            statement.bindLong(3, now)
            statement.executeUpdateDelete() > 0
        }

    fun save(key: CardKey, state: SrsState) {
        val values = ContentValues().apply {
            put("stability", state.stability)
            put("difficulty", state.difficulty)
            put("due", state.due)
            state.lastReview?.let { put("last_review", it) } ?: putNull("last_review")
            put("phase", state.phase.name)
            put("step", state.step)
            put("reps", state.reps)
            put("lapses", state.lapses)
        }
        writableDatabase.update("card", values, "key = ?", arrayOf(key.value))
    }

    fun log(key: CardKey, entryId: Long, kind: CardKind, rating: Rating, at: Long,
            correct: Boolean) {
        writableDatabase.execSQL(
            "INSERT INTO review_log(key, entry_id, kind, rating, at, correct) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf(key.value, entryId, kind.code, rating.value, at, if (correct) 1 else 0),
        )
    }

    data class DueCard(
        val key: CardKey,
        val kind: CardKind,
        val entryId: Long,
        val senseId: Long,
        val deck: String,
        val extra: String,
        val due: Long,
        val stability: Double,
        val lastReview: Long?,
        val phase: CardPhase,
    )

    private fun readDue(c: android.database.Cursor) = DueCard(
        key = CardKey(c.getString(0)),
        kind = CardKind.of(c.getString(1)),
        entryId = c.getLong(2),
        senseId = c.getLong(3),
        deck = c.getString(4),
        extra = c.getString(5),
        due = c.getLong(6),
        stability = c.getDouble(7),
        lastReview = if (c.isNull(8)) null else c.getLong(8),
        phase = CardPhase.fromName(c.getString(9)),
    )

    fun dueCards(decks: Collection<String>, now: Long, limit: Int): List<DueCard> {
        if (decks.isEmpty()) return emptyList()
        val holes = decks.joinToString(",") { "?" }
        val out = ArrayList<DueCard>()
        readableDatabase.rawQuery(
            // Cards introduced together share a due time to the millisecond,
            // so plain `ORDER BY due` hands them back in the order they were
            // created — the same running order every session until the first
            // review moves them apart. The tie-break is what stops that.
            "SELECT $DUE_COLUMNS FROM card WHERE deck IN ($holes) AND due <= ? " +
                "ORDER BY due, RANDOM() LIMIT ?",
            (decks + listOf(now.toString(), limit.toString())).toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(readDue(c)) }
        return out
    }

    /** Cards of a deck, ordered by how soon they will be forgotten. */
    fun allCards(decks: Collection<String>): List<DueCard> {
        if (decks.isEmpty()) return emptyList()
        val holes = decks.joinToString(",") { "?" }
        val out = ArrayList<DueCard>()
        readableDatabase.rawQuery(
            "SELECT $DUE_COLUMNS FROM card WHERE deck IN ($holes)",
            decks.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(readDue(c)) }
        return out
    }

    /**
     * The words of a deck whose study started most recently, newest first.
     *
     * A word does not arrive with every question type at once any more — four a
     * day, so that one word with six meanings cannot fill a session — so the
     * next session has to know where to go back and finish.
     */
    fun recentlyIntroduced(deck: String, limit: Int): List<Long> {
        val out = ArrayList<Long>(limit)
        readableDatabase.rawQuery(
            "SELECT entry_id, MAX(created) AS started FROM card WHERE deck = ? " +
                "GROUP BY entry_id ORDER BY started DESC LIMIT ?",
            arrayOf(deck, limit.toString()),
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    /** Which of [entryIds] the learner has met, whatever deck it came from. */
    fun metEntries(entryIds: Collection<Long>): Set<Long> {
        if (entryIds.isEmpty()) return emptySet()
        val holes = entryIds.joinToString(",") { "?" }
        val out = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT entry_id FROM card WHERE entry_id IN ($holes)",
            entryIds.map { it.toString() }.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    /** Entry ids that already have a card of [kind]; used to pick what is new. */
    fun introducedEntries(kind: CardKind, deck: String): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT entry_id FROM card WHERE kind = ? AND deck = ?",
            arrayOf(kind.code, deck),
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun countsByDeck(now: Long): Map<String, Triple<Int, Int, Int>> {
        val out = HashMap<String, Triple<Int, Int, Int>>()
        readableDatabase.rawQuery(
            "SELECT deck, " +
                "SUM(CASE WHEN phase = 'NEW' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN phase IN ('LEARNING','RELEARNING') THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN phase = 'REVIEW' AND due <= ? THEN 1 ELSE 0 END) " +
                "FROM card GROUP BY deck",
            arrayOf(now.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Triple(c.getInt(1), c.getInt(2), c.getInt(3))
            }
        }
        return out
    }

    fun introducedToday(deck: String, since: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM card WHERE deck = ? AND created >= ?",
            arrayOf(deck, since.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun reviewsSince(since: Long): List<Triple<Long, Int, Boolean>> {
        val out = ArrayList<Triple<Long, Int, Boolean>>()
        readableDatabase.rawQuery(
            "SELECT at, rating, correct FROM review_log WHERE at >= ? ORDER BY at",
            arrayOf(since.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Triple(c.getLong(0), c.getInt(1), c.getInt(2) == 1))
            }
        }
        return out
    }

    /** Entries answered wrong most often — the personal weak list. */
    fun troubleEntries(limit: Int): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        readableDatabase.rawQuery(
            "SELECT entry_id, COUNT(*) AS misses FROM review_log WHERE correct = 0 " +
                "GROUP BY entry_id ORDER BY misses DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0) to c.getInt(1)) }
        return out
    }

    // ---- the learner's own list ---------------------------------------------

    /**
     * Words the learner pulled in from their own material.
     *
     * Kept in the order they were added, which for a pasted text means "most
     * blocking first" and for an imported word list means the order of the book
     * it came from.
     */
    fun pick(entryIds: List<Long>, source: String, now: Long): Int {
        val db = writableDatabase
        var next = db.rawQuery("SELECT COALESCE(MAX(ord), 0) FROM picked", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        var added = 0
        db.beginTransaction()
        try {
            for (id in entryIds) {
                next++
                val before = db.rawQuery(
                    "SELECT 1 FROM picked WHERE entry_id = ?", arrayOf(id.toString())
                ).use { it.moveToFirst() }
                if (before) continue
                db.execSQL(
                    "INSERT INTO picked(entry_id, ord, source, added) VALUES(?,?,?,?)",
                    arrayOf(id, next, source, now),
                )
                added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun pickedEntries(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT entry_id FROM picked ORDER BY ord", null)
            .use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun pickedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM picked", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Every card of the given entries, for judging what the learner can read. */
    fun cardsOfEntries(ids: Collection<Long>): Map<Long, List<DueCard>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<DueCard>>()
        ids.chunked(400).forEach { chunk ->
            val holes = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT $DUE_COLUMNS FROM card WHERE entry_id IN ($holes)",
                chunk.map { it.toString() }.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val card = readDue(c)
                    out.getOrPut(card.entryId) { ArrayList() }.add(card)
                }
            }
        }
        return out
    }

    fun starred(): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT entry_id FROM marker WHERE starred = 1", null)
            .use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun setStarred(entryId: Long, value: Boolean) {
        writableDatabase.execSQL(
            "INSERT INTO marker(entry_id, starred) VALUES(?, ?) " +
                "ON CONFLICT(entry_id) DO UPDATE SET starred = excluded.starred",
            arrayOf(entryId, if (value) 1 else 0),
        )
    }

    fun totalCards(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM card", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        private const val NAME = "study.db"
        private const val VERSION = 1
        private const val DUE_COLUMNS =
            "key, kind, entry_id, sense_id, deck, extra, due, stability, last_review, phase"
    }
}
