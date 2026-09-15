package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.CardFactory
import com.eitangoze.data.CardKind
import com.eitangoze.data.ContentDb
import com.eitangoze.data.Deck
import com.eitangoze.data.EntryKind
import com.eitangoze.data.Morpheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks against the database that actually ships.
 *
 * These are not unit tests of a pure function: they open the real asset and ask
 * whether the generated data is fit to teach from. A pipeline change that
 * quietly drops the Japanese, or duplicates a headword, or produces a card with
 * no answer, fails here rather than on someone's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentDbTest {

    // Opened per test: Robolectric installs its instrumentation around each test
    // method, so there is no application context yet in @BeforeClass.
    private lateinit var db: ContentDb

    @Before
    fun open() {
        db = ContentDb.open(ApplicationProvider.getApplicationContext())
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `the database is installed from assets and is the expected size`() {
        assertTrue("entries: ${db.entryCount}", db.entryCount > 15000)
        assertTrue("senses: ${db.senseCount}", db.senseCount > 50000)
        assertTrue("sentences: ${db.sentenceCount}", db.sentenceCount > 20000)
    }

    @Test
    fun `every deck has material and the levels are ordered`() {
        var previousFirstRank = 0
        for (deck in Deck.ALL.filter { !it.custom }) {
            val size = db.deckSize(deck)
            assertTrue("${deck.id} is empty", size > 30)
            val first = db.deckEntries(deck, emptySet(), 1).first()
            if (deck.cefr != null) {
                // The CEFR decks are taught in order, so each starts after the
                // previous one ends.
                assertTrue(
                    "${deck.id} starts at rank ${first.rank}, before the previous deck",
                    first.rank > previousFirstRank,
                )
                previousFirstRank = first.rank
                assertEquals(deck.cefr, first.cefr)
            }
        }
    }

    @Test
    fun `a headword appears once per part of speech`() {
        val seen = HashMap<String, Int>()
        for (deck in Deck.ALL.filter { !it.custom }) {
            db.deckEntries(deck, emptySet(), 400).forEach { entry ->
                val key = "${entry.lemma}|${entry.pos.code}"
                seen[key] = (seen[key] ?: 0) + 1
            }
        }
        val duplicates = seen.filterValues { it > 1 }
        assertTrue("duplicated entries: ${duplicates.keys.take(5)}", duplicates.isEmpty())
    }

    @Test
    fun `every entry can be answered in Japanese`() {
        var checked = 0
        for (deck in Deck.ALL.filter { !it.custom }) {
            db.deckEntries(deck, emptySet(), 150).forEach { entry ->
                val senses = db.senses(entry.id)
                assertTrue("${entry.lemma} has no senses", senses.isNotEmpty())
                assertTrue(
                    "${entry.lemma} (${entry.pos.code}) has no Japanese",
                    senses.any { it.ja.isNotEmpty() },
                )
                assertTrue("${entry.lemma} has no headline gloss", entry.ja.isNotEmpty())
                checked++
            }
        }
        assertTrue(checked > 1000)
    }

    @Test
    fun `phrases carry the phrase, and words do not`() {
        db.deckEntries(Deck.byId("pv")!!, emptySet(), 40).forEach {
            assertEquals(EntryKind.PHRASAL_VERB, it.kind)
            assertTrue("${it.lemma} is not multi-word", it.lemma.contains(' '))
        }
        db.deckEntries(Deck.byId("B1")!!, emptySet(), 40).forEach {
            assertEquals(EntryKind.WORD, it.kind)
        }
    }

    @Test
    fun `collocations are between real words, not grammar`() {
        val grammar = setOf("me", "it", "him", "them", "the", "a", "an", "no", "see")
        var found = 0
        for (deck in listOf("B1", "B2")) {
            db.deckEntries(Deck.byId(deck)!!, emptySet(), 200).forEach { entry ->
                db.collocations(entry.id).forEach { coll ->
                    assertTrue(
                        "${coll.phrase} collocates with a function word",
                        coll.collocate !in grammar || coll.pattern.endsWith("prep"),
                    )
                    assertTrue(coll.count >= 2)
                    found++
                }
            }
        }
        assertTrue("no collocations at all", found > 50)
    }

    @Test
    fun `a multiple-choice meaning card has four distinct options`() {
        val factory = CardFactory(db)
        var built = 0
        db.deckEntries(Deck.byId("B1")!!, emptySet(), 40).forEach { entry ->
            val specs = factory.specs(entry, "B1", setOf(CardKind.MEANING))
            specs.forEach { spec ->
                val card = factory.build(fakeDue(spec)) ?: return@forEach
                assertEquals(4, card.choices.size)
                assertEquals(card.choices.size, card.choices.toSet().size)
                assertTrue(card.correctIndex in card.choices.indices)
                built++
            }
        }
        assertTrue(built > 20)
    }

    /**
     * The context card is the one this app is built around, so it gets its own
     * check: the wrong answers must be the *other meanings of the same word*.
     */
    @Test
    fun `a context card offers the words own other meanings`() {
        val factory = CardFactory(db)
        var built = 0
        for (deckId in listOf("A2", "B1", "B2")) {
            db.deckEntries(Deck.byId(deckId)!!, emptySet(), 120).forEach { entry ->
                val senses = db.senses(entry.id).filter { it.ja.isNotEmpty() }
                if (senses.size < 2) return@forEach
                factory.specs(entry, deckId, setOf(CardKind.CONTEXT)).forEach { spec ->
                    val card = factory.build(fakeDue(spec)) ?: return@forEach
                    // As they go on a button: capped at CardFactory.MAX_CHOICE_GLOSSES,
                    // so that no option is the one that simply looks longest.
                    val ownMeanings = senses.map {
                        it.ja.take(CardFactory.MAX_CHOICE_GLOSSES).joinToString("、")
                    }.toSet()
                    assertTrue(
                        "${entry.lemma}: choices are not its own senses",
                        card.choices.all { it in ownMeanings },
                    )
                    assertTrue(card.prompt.isNotBlank())
                    built++
                }
            }
        }
        assertTrue("no context cards could be built", built > 10)
    }

    /**
     * The fold that makes the context card answerable at all.
     *
     * WordNet splits `make` into senses that the Japanese renders 為す twice
     * over; shown as options they are one answer printed on two buttons. No two
     * meanings of a word may share a gloss once [ContentDb.senses] has spoken.
     */
    @Test
    fun `no two meanings of a word share a Japanese gloss`() {
        var checked = 0
        for (deckId in listOf("A1", "A2", "B1", "B2", "C1")) {
            db.deckEntries(Deck.byId(deckId)!!, emptySet(), 200).forEach { entry ->
                val senses = db.senses(entry.id).filter { it.ja.isNotEmpty() }
                for (i in senses.indices) {
                    for (j in i + 1 until senses.size) {
                        val shared = senses[i].ja.filter { it in senses[j].ja }
                        assertTrue(
                            "${entry.lemma}: meanings ${senses[i].jaLine} and " +
                                "${senses[j].jaLine} both claim $shared",
                            shared.isEmpty(),
                        )
                    }
                }
                checked++
            }
        }
        assertTrue(checked > 500)
    }

    /** A folded meaning keeps the frequency and the examples of every row in it. */
    @Test
    fun `folding a meaning adds up its rows`() {
        var folded = 0
        for (deckId in listOf("A1", "A2", "B1")) {
            db.deckEntries(Deck.byId(deckId)!!, emptySet(), 200).forEach { entry ->
                db.senses(entry.id).filter { it.ids.size > 1 }.forEach { sense ->
                    val parts = sense.ids.mapNotNull { db.sense(it) }
                    assertEquals(sense.ids.size, parts.size)
                    assertEquals(parts.sumOf { it.semcor }, sense.semcor)
                    assertEquals(sense.ids.first(), sense.id)
                    assertTrue(
                        "${entry.lemma}: folded gloss list is too wide to read",
                        sense.ja.size <= 5,
                    )
                    // Every row's examples are reachable through the fold.
                    val all = db.senseExamples(sense).toSet()
                    sense.ids.forEach { id ->
                        assertTrue(db.senseExamples(id).all { it in all })
                    }
                    folded++
                }
            }
        }
        assertTrue("nothing was folded at all", folded > 50)
    }

    /**
     * The decompositions have to come from Wiktionary's etymology, never from
     * stripping letters. A cut is only trustworthy if every piece is really in
     * the word and the pieces really spell it.
     */
    @Test
    fun `a word is cut into pieces that spell it`() {
        var cut = 0
        for (deckId in listOf("A2", "B1", "B2", "C1")) {
            db.deckEntries(Deck.byId(deckId)!!, emptySet(), 300).forEach { entry ->
                val parts = db.morphemes(entry.id)
                if (parts.isEmpty()) return@forEach
                assertTrue("${entry.lemma}: a single piece is not a cut", parts.size >= 2)
                assertTrue(
                    "${entry.lemma}: cut into stems only, which teaches no operator",
                    parts.any { it.isAffix },
                )
                assertTrue(
                    "${entry.lemma}: no piece has a page to open",
                    parts.any { it.hasPage },
                )
                parts.forEach { part ->
                    val letters = part.form.trim('-')
                    assertTrue("${entry.lemma}: empty piece", letters.isNotEmpty())
                    assertEquals(part.form, part.form.lowercase())
                }
                cut++
            }
        }
        assertTrue("nothing was cut at all", cut > 100)
    }

    /** Every affix with a page says what it does, and builds enough to matter. */
    @Test
    fun `an affix explains itself and is productive`() {
        val affixes = db.affixes(limit = 500)
        assertTrue("only ${affixes.size} affixes", affixes.size >= 80)
        affixes.forEach { affix ->
            assertTrue("${affix.form} says nothing", affix.ja.isNotEmpty() || affix.gloss.isNotEmpty())
            assertTrue("${affix.form} builds only ${affix.uses} words", affix.uses >= 4)
            assertTrue(
                "${affix.form} is not shaped like an affix",
                affix.form.startsWith("-") || affix.form.endsWith("-"),
            )
        }
        // The grid's whole claim is that an affix does the same job across many
        // stems, so the productive end of the list has to be genuinely long.
        assertTrue(affixes.first().uses >= 40)
    }

    /**
     * The grid, and the flip that is the point of it: hold `-tion` still and
     * the stems line up; hold a stem still and the affixes do.
     */
    @Test
    fun `the grid can be read from either end`() {
        val affix = db.affix("-tion") ?: db.affixes(limit = 1).first()
        val down = db.gridByAffix(affix.id)
        assertTrue("${affix.form} builds no grid", down.size >= 5)
        down.forEach { cell ->
            assertEquals(affix.id, cell.fixed.affixId)
            assertTrue(
                "${cell.entry.lemma}: the varying piece is the fixed one",
                cell.varying.form != cell.fixed.form,
            )
        }
        // Flipping on any stem in that grid must land on a grid again.
        val stem = down.firstOrNull { it.varying.kind == Morpheme.Kind.STEM }?.varying?.form
        if (stem != null) {
            val across = db.gridByStem(stem)
            assertTrue(
                "$stem does not come back as a grid",
                across.isEmpty() || across.all { it.fixed.form == stem },
            )
        }
    }

    @Test
    fun `a cloze hides the word and keeps the translation`() {
        val factory = CardFactory(db)
        var built = 0
        db.deckEntries(Deck.byId("A2")!!, emptySet(), 120).forEach { entry ->
            factory.specs(entry, "A2", setOf(CardKind.CLOZE)).forEach { spec ->
                val card = factory.build(fakeDue(spec)) ?: return@forEach
                assertTrue("${entry.lemma}: nothing was blanked", card.prompt.contains("______"))
                assertTrue(card.promptJa.isNotBlank())
                assertTrue(card.accepted.isNotEmpty())
                built++
            }
        }
        assertTrue(built > 20)
    }

    /**
     * The word families are the part of the database a stranger sees first, so
     * they have to be right: a family whose shared letters are a prefix teaches
     * nothing, and one whose members do not show the pattern is not a family.
     */
    @Test
    fun `every word family shares a visible stem`() {
        val families = db.families()
        assertTrue("only ${families.size} families", families.size >= 50)
        val prefixes = setOf("pro", "pre", "con", "com", "inter", "trans", "sub", "anti")
        families.forEach { (family, size) ->
            assertTrue("${family.pattern} is an affix", family.pattern !in prefixes)
            assertTrue("${family.pattern} is too short", family.pattern.length >= 3)
            assertTrue("${family.pattern} has $size members", size >= 4)
            val members = db.familyMembers(family.id)
            members.forEach {
                assertTrue(
                    "${it.lemma} does not contain ${family.pattern}",
                    family.pattern in it.lemma,
                )
            }
            // One row per spelling: `reject` the noun and the verb are one word.
            assertEquals(members.size, members.map { it.lemma }.toSet().size)
        }
    }

    @Test
    fun `the classic stems are among the families`() {
        val patterns = db.families().map { it.first.pattern }.toSet()
        val wanted = listOf("duc", "ject", "scrib", "port", "pend")
        val missing = wanted.filter { it !in patterns }
        assertTrue("missing families: $missing (have ${patterns.take(20)})", missing.size <= 1)
    }

    @Test
    fun `sense shares are only claimed where the corpus counted them`() {
        var withCounts = 0
        for (deck in Deck.ALL.filter { !it.custom }) {
            db.deckEntries(deck, emptySet(), 120).forEach { entry ->
                val senses = db.senses(entry.id)
                val counted = senses.filter { it.semcor > 0 }
                if (counted.size >= 2) withCounts++
                // A count belongs to a sense that can be shown in Japanese.
                counted.forEach {
                    assertTrue("${entry.lemma}: counted sense has no Japanese", it.ja.isNotEmpty())
                }
            }
        }
        assertTrue("no word had a usable sense split", withCounts > 40)
    }

    private fun fakeDue(spec: com.eitangoze.data.CardSpec) = com.eitangoze.data.UserDb.DueCard(
        key = spec.key,
        kind = spec.kind,
        entryId = spec.entryId,
        senseId = spec.senseId,
        deck = "test",
        extra = spec.extra,
        due = 0L,
        stability = 0.0,
        lastReview = null,
        phase = com.eitangoze.srs.CardPhase.NEW,
    )
}
