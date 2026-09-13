package com.eitangoze.data

import android.content.Context
import com.eitangoze.srs.CardPhase
import com.eitangoze.srs.Fsrs
import com.eitangoze.srs.FsrsScheduler
import com.eitangoze.srs.Rating
import com.eitangoze.srs.SrsState
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Everything the screens talk to. Owns both databases and the scheduler. */
class Repository(context: Context) {

    val content: ContentDb = ContentDb.open(context)
    private val user = UserDb(context)
    private val factory = CardFactory(content)
    private val analyzer = TextAnalyzer(content)

    // ---- settings -----------------------------------------------------------

    var desiredRetention: Double
        get() = user.setting(KEY_RETENTION, "0.9").toDoubleOrNull() ?: 0.9
        set(value) = user.putSetting(KEY_RETENTION, value.coerceIn(0.7, 0.98).toString())

    /** Epoch millis of the exam, or 0. Changes what "due" means; see [scheduler]. */
    var examDate: Long
        get() = user.setting(KEY_EXAM, "0").toLongOrNull() ?: 0L
        set(value) = user.putSetting(KEY_EXAM, value.toString())

    var newPerDay: Int
        get() = user.setting(KEY_NEW_PER_DAY, "20").toIntOrNull() ?: 20
        set(value) = user.putSetting(KEY_NEW_PER_DAY, value.coerceIn(0, 200).toString())

    var reviewLimit: Int
        get() = user.setting(KEY_REVIEW_LIMIT, "200").toIntOrNull() ?: 200
        set(value) = user.putSetting(KEY_REVIEW_LIMIT, value.coerceIn(10, 2000).toString())

    var enabledDecks: List<String>
        get() = user.setting(KEY_DECKS, "A1,A2,B1").split(",").filter { it.isNotBlank() }
        set(value) = user.putSetting(KEY_DECKS, value.joinToString(","))

    /**
     * The level the learner says they already have, or "" for none.
     *
     * Only the reading measurement uses it. Without it a first run reports that
     * you cannot read `to` or `have`, which is true of the study database and
     * false of the person — and a headline number that is obviously wrong is
     * worse than no number.
     */
    var baselineLevel: String
        get() = user.setting(KEY_BASELINE, "")
        set(value) = user.putSetting(KEY_BASELINE, value)

    var enabledKinds: Set<CardKind>
        get() {
            val stored = user.setting(KEY_KINDS, "")
            if (stored.isBlank()) return CardKind.entries.filter { it.defaultOn }.toSet()
            return stored.split(",").mapNotNull { code ->
                CardKind.entries.firstOrNull { it.code == code }
            }.toSet()
        }
        set(value) = user.putSetting(KEY_KINDS, value.joinToString(",") { it.code })

    /**
     * Target retention, raised as the exam approaches.
     *
     * Without an exam date the question is "is it due today?". With one it
     * becomes "will it still be there on the day?", and the honest way to answer
     * that is to tighten intervals gradually over the last two months rather
     * than to cram at the end.
     */
    fun effectiveRetention(now: Long = System.currentTimeMillis()): Double {
        val exam = examDate
        if (exam <= 0L) return desiredRetention
        val daysLeft = ((exam - now).toDouble() / DAY_MS)
        if (daysLeft <= 0) return 0.97
        if (daysLeft >= RAMP_DAYS) return desiredRetention
        val progress = 1.0 - daysLeft / RAMP_DAYS
        return desiredRetention + (0.97 - desiredRetention) * progress
    }

    fun scheduler(now: Long = System.currentTimeMillis()) =
        FsrsScheduler(desiredRetention = effectiveRetention(now))

    // ---- decks --------------------------------------------------------------

    data class DeckStatus(
        val deck: Deck,
        val total: Int,
        val introduced: Int,
        val newToday: Int,
        val learning: Int,
        val due: Int,
    ) {
        val waiting: Int get() = learning + due
    }

    fun deckStatuses(now: Long = System.currentTimeMillis()): List<DeckStatus> {
        val counts = user.countsByDeck(now)
        val since = startOfDay(now)
        return Deck.ALL.map { deck ->
            val (_, learning, due) = counts[deck.id] ?: Triple(0, 0, 0)
            DeckStatus(
                deck = deck,
                total = if (deck.custom) user.pickedCount() else content.deckSize(deck),
                introduced = user.introducedEntries(CardKind.MEANING, deck.id).size,
                newToday = user.introducedToday(deck.id, since),
                learning = learning,
                due = due,
            )
        }
    }

    // ---- the queue ----------------------------------------------------------

    /**
     * The cards to study now.
     *
     * Reviews come first by due time, then new material is spread evenly through
     * them rather than piled at the front, and two questions about the same word
     * are pushed apart — answering `abandon` four ways in a row tests short-term
     * memory, which is not the thing being trained.
     */
    fun buildQueue(now: Long = System.currentTimeMillis(), limit: Int = 120): List<StudyCard> {
        val decks = enabledDecks.ifEmpty { listOf("A1") }
        val due = user.dueCards(decks, now, min(limit, reviewLimit))
        val fresh = introduceNew(decks, now, budget = newBudget(decks, now))
        val ordered = interleave(due, fresh)
        val built = ArrayList<StudyCard>(ordered.size)
        for (card in ordered) {
            factory.build(card)?.let(built::add)
            if (built.size >= limit) break
        }
        return spaceOutSiblings(built)
    }

    private fun newBudget(decks: List<String>, now: Long): Map<String, Int> {
        val since = startOfDay(now)
        return decks.associateWith { deck ->
            max(0, newPerDay - user.introducedToday(deck, since))
        }
    }

    /**
     * Create study rows for material not seen before, in teaching order.
     *
     * A word arrives with all of its enabled question types at once, so the
     * first encounter covers recognition, production and context together. The
     * budget counts cards, not words, which is what actually costs time.
     */
    private fun introduceNew(
        decks: List<String>,
        now: Long,
        budget: Map<String, Int>,
    ): List<UserDb.DueCard> {
        val kinds = enabledKinds
        val out = ArrayList<UserDb.DueCard>()
        for (deckId in decks) {
            var left = budget[deckId] ?: 0
            if (left <= 0) continue
            val deck = Deck.byId(deckId) ?: continue
            val taken = user.introducedEntries(CardKind.MEANING, deckId)
            val entries = if (deck.custom) {
                val ids = user.pickedEntries().filter { it !in taken }.take(max(4, left / 2 + 2))
                val byId = content.entries(ids)
                ids.mapNotNull { byId[it] }
            } else {
                content.deckEntries(deck, taken, limit = max(4, left / 2 + 2))
            }
            for (entry in entries) {
                if (left <= 0) break
                val specs = factory.specs(entry, deckId, kinds)
                for (spec in specs) {
                    if (left <= 0) break
                    if (user.state(spec.key) != null) continue
                    user.introduce(spec.key, spec.kind, spec.entryId, spec.senseId,
                        deckId, spec.extra, now)
                    out.add(
                        UserDb.DueCard(
                            key = spec.key, kind = spec.kind, entryId = spec.entryId,
                            senseId = spec.senseId, deck = deckId, extra = spec.extra,
                            due = now, stability = 0.0, lastReview = null,
                            phase = CardPhase.NEW,
                        )
                    )
                    left--
                }
            }
        }
        return out
    }

    private fun interleave(
        due: List<UserDb.DueCard>,
        fresh: List<UserDb.DueCard>,
    ): List<UserDb.DueCard> {
        if (fresh.isEmpty()) return due
        if (due.isEmpty()) return fresh
        val out = ArrayList<UserDb.DueCard>(due.size + fresh.size)
        val every = max(1, due.size / fresh.size)
        var next = 0
        due.forEachIndexed { i, card ->
            out.add(card)
            if ((i + 1) % every == 0 && next < fresh.size) out.add(fresh[next++])
        }
        while (next < fresh.size) out.add(fresh[next++])
        return out
    }

    /**
     * Keep two questions about the same word apart.
     *
     * A word arrives with all of its question types at once, so without this the
     * session opens with `abandon` asked five ways in a row — which tests short
     * term memory rather than recall. Each step takes the earliest card whose
     * word differs from the one just asked, so the original teaching order is
     * disturbed as little as possible.
     */
    private fun spaceOutSiblings(cards: List<StudyCard>): List<StudyCard> {
        val remaining = cards.toMutableList()
        val out = ArrayList<StudyCard>(cards.size)
        while (remaining.isNotEmpty()) {
            val previous = out.lastOrNull()?.entry?.id
            val index = remaining.indexOfFirst { it.entry.id != previous }
            out.add(remaining.removeAt(if (index >= 0) index else 0))
        }
        return out
    }

    // ---- answering ----------------------------------------------------------

    fun stateOf(card: StudyCard): SrsState = user.state(card.key) ?: SrsState()

    fun previewDelays(card: StudyCard, now: Long = System.currentTimeMillis()): Map<Rating, Long> =
        scheduler(now).previewDelays(stateOf(card), now)

    fun answer(card: StudyCard, rating: Rating, correct: Boolean,
               now: Long = System.currentTimeMillis()) {
        val state = stateOf(card)
        val next = scheduler(now).review(state, rating, now)
        user.save(card.key, next)
        user.log(card.key, card.entry.id, card.kind, rating, now, correct)
    }

    // ---- reference view -----------------------------------------------------

    data class EntryDetail(
        val entry: Entry,
        val senses: List<Sense>,
        val examplesBySense: Map<Long, List<Example>>,
        val sentences: List<ContentDb.LinkedSentence>,
        val collocations: List<Collocation>,
        val relations: List<Relation>,
        val starred: Boolean,
        val cards: List<UserDb.DueCard>,
        val family: RootFamily?,
        val familyMembers: List<Entry>,
    )

    fun detail(entryId: Long): EntryDetail? {
        val entry = content.entry(entryId) ?: return null
        val senses = content.senses(entryId)
        return EntryDetail(
            entry = entry,
            senses = senses,
            examplesBySense = senses.associate { it.id to content.senseExamples(it) },
            sentences = content.sentences(entryId, limit = 5),
            collocations = content.collocations(entryId),
            relations = content.relations(entryId),
            starred = entryId in user.starred(),
            cards = user.allCards(Deck.ALL.map { it.id }).filter { it.entryId == entryId },
            family = entry.rootId.takeIf { it > 0 }?.let { content.family(it) },
            familyMembers = if (entry.rootId > 0) content.familyMembers(entry.rootId)
            else emptyList(),
        )
    }

    fun setStarred(entryId: Long, value: Boolean) = user.setStarred(entryId, value)

    fun search(query: String) = content.search(query)

    // ---- reading your own English -------------------------------------------

    /**
     * How much of a passage this learner can read, right now.
     *
     * A word counts as readable when a *recognition* card for it is predicted to
     * be recallable: reading needs you to know the word when you see it, which
     * is a weaker demand than producing it from Japanese, and grading reading by
     * the production card would understate what you can actually read.
     */
    fun analyze(text: String, now: Long = System.currentTimeMillis()): TextReport =
        analyzer.analyze(text) { entries ->
            val cards = user.cardsOfEntries(entries.map { it.id })
            entries.associate { entry -> entry.id to memoryOf(entry, cards[entry.id].orEmpty(), now) }
        }

    /**
     * What is known about one word, including the curve it will decay along.
     *
     * Grammar words and anything inside the declared baseline carry no curve:
     * they were not learned here, so this app has no basis for predicting that
     * they will be forgotten, and inventing one would be the wrong kind of
     * drama. Everything the learner actually answered decays for real.
     */
    private fun memoryOf(entry: Entry, cards: List<UserDb.DueCard>, now: Long): WordMemory {
        if (entry.kind == EntryKind.FUNCTION) {
            return WordMemory(Knowledge.KNOWN, permanent = true)
        }
        if (cards.isEmpty()) {
            return if (withinBaseline(entry)) WordMemory(Knowledge.KNOWN, permanent = true)
            else WordMemory(Knowledge.NEW)
        }
        val recognition = cards.filter {
            it.kind == CardKind.MEANING || it.kind == CardKind.CONTEXT ||
                it.kind == CardKind.CLOZE
        }.ifEmpty { cards }
        // A card still in its learning steps has just been seen, so its predicted
        // recall is near 1 whatever the learner actually did. Only a card that
        // has graduated to review says anything about tomorrow's reading.
        val settled = recognition.filter { it.phase == CardPhase.REVIEW }
        val best = settled.maxByOrNull { recallAt(it, now) }
            ?: return WordMemory(Knowledge.LEARNING)
        val recall = recallAt(best, now)
        return WordMemory(
            knowledge = if (recall >= READABLE) Knowledge.KNOWN else Knowledge.LEARNING,
            stability = best.stability,
            lastReview = best.lastReview,
        )
    }

    private fun withinBaseline(entry: Entry): Boolean {
        val baseline = LEVELS.indexOf(baselineLevel)
        if (baseline < 0) return false
        val level = LEVELS.indexOf(entry.cefr)
        return level in 0..baseline
    }

    /** Take words from your own material into the study list. */
    fun pick(entryIds: List<Long>, source: String, now: Long = System.currentTimeMillis()): Int {
        val added = user.pick(entryIds, source, now)
        if (added > 0 && MINE !in enabledDecks) enabledDecks = enabledDecks + MINE
        return added
    }

    data class ImportResult(val matched: List<Entry>, val missing: List<String>)

    /**
     * Read a word list — one word per line, or the first column of a TSV or CSV.
     *
     * The words are matched against the shipped dictionary rather than stored
     * with whatever glosses came with them: a headword list is the learner's own
     * study order, but the meanings, examples and grammar come from sources this
     * app can stand behind.
     */
    fun importWordList(text: String): ImportResult {
        val wanted = text.lineSequence()
            .map { it.split('\t', ',', ';').first().trim().lowercase() }
            .filter { it.isNotEmpty() && it.first().isLetter() }
            .distinct()
            .toList()
        if (wanted.isEmpty()) return ImportResult(emptyList(), emptyList())
        val found = content.surfaces(wanted)
        val entries = content.entries(found.values.toSet())
        val matched = ArrayList<Entry>()
        val missing = ArrayList<String>()
        val seen = HashSet<Long>()
        for (word in wanted) {
            val entry = found[word]?.let { entries[it] }
            if (entry == null) {
                missing.add(word)
            } else if (seen.add(entry.id)) {
                matched.add(entry)
            }
        }
        return ImportResult(matched, missing)
    }

    // ---- statistics ---------------------------------------------------------

    data class Stats(
        val answeredToday: Int,
        val correctToday: Int,
        val streakDays: Int,
        val cardsTotal: Int,
        val dueNextWeek: List<Int>,
        val examReadiness: Double?,
        val atRiskAtExam: Int,
        val trouble: List<Pair<Entry, Int>>,
    )

    fun stats(now: Long = System.currentTimeMillis()): Stats {
        val today = startOfDay(now)
        val recent = user.reviewsSince(today - 60L * DAY_MS)
        val todayRows = recent.filter { it.first >= today }
        val days = recent.map { startOfDay(it.first) }.toSortedSet().toList().reversed()
        var streak = 0
        var cursor = today
        for (day in days) {
            if (day == cursor) {
                streak++
                cursor -= DAY_MS
            } else if (day < cursor) {
                break
            }
        }

        val all = user.allCards(enabledDecks)
        val forecast = IntArray(7)
        all.forEach { card ->
            val days = ((card.due - today) / DAY_MS).toInt()
            if (days in 0..6) forecast[days]++
        }

        val exam = examDate
        var readiness: Double? = null
        var atRisk = 0
        if (exam > now && all.isNotEmpty()) {
            var sum = 0.0
            all.forEach { card ->
                val recall = recallAt(card, exam)
                sum += recall
                if (recall < effectiveRetention(now)) atRisk++
            }
            readiness = sum / all.size
        }

        val trouble = user.troubleEntries(12)
        val entries = content.entries(trouble.map { it.first })
        return Stats(
            answeredToday = todayRows.size,
            correctToday = todayRows.count { it.third },
            streakDays = streak,
            cardsTotal = user.totalCards(),
            dueNextWeek = forecast.toList(),
            examReadiness = readiness,
            atRiskAtExam = atRisk,
            trouble = trouble.mapNotNull { (id, misses) -> entries[id]?.let { it to misses } },
        )
    }

    /** Predicted recall of a card at some future instant. */
    fun recallAt(card: UserDb.DueCard, at: Long): Double {
        val last = card.lastReview ?: return 0.0
        if (card.stability <= 0.0) return 0.0
        val elapsed = max(0.0, (at - last).toDouble() / DAY_MS)
        return Fsrs.recallAfter(elapsed, card.stability)
    }

    /** The cards that will be weakest on exam day, weakest first. */
    fun weakestAtExam(limit: Int = 60): List<StudyCard> {
        val exam = examDate.takeIf { it > 0 } ?: return emptyList()
        return user.allCards(enabledDecks)
            .sortedBy { recallAt(it, exam) }
            .asSequence()
            .mapNotNull { factory.build(it) }
            .take(limit)
            .toList()
    }

    fun close() {
        content.close()
        user.close()
    }

    companion object {
        private const val KEY_RETENTION = "retention"
        private const val KEY_EXAM = "exam_date"
        private const val KEY_NEW_PER_DAY = "new_per_day"
        private const val KEY_REVIEW_LIMIT = "review_limit"
        private const val KEY_DECKS = "decks"
        private const val KEY_KINDS = "kinds"
        private const val KEY_BASELINE = "baseline_level"

        const val DAY_MS = 86_400_000L

        /** Predicted recall at which a word counts as readable on sight. */
        const val READABLE = TextSpan.READABLE

        const val MINE = "mine"

        val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")

        /** Days before the exam over which the retention target is tightened. */
        const val RAMP_DAYS = 60.0

        fun startOfDay(at: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = at
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /** "3日" / "2か月" for the interval hints on the rating buttons. */
        fun humanDelay(ms: Long): String {
            val minutes = ms / 60_000
            if (minutes < 60) return "${max(1, minutes)}分"
            val hours = minutes / 60
            if (hours < 24) return "${hours}時間"
            val days = hours / 24
            if (days < 30) return "${days}日"
            val months = days / 30
            if (months < 12) return "${months}か月"
            return "${days / 365}年"
        }
    }
}
