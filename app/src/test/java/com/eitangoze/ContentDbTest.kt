package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.CardFactory
import com.eitangoze.data.CardKind
import com.eitangoze.data.ContentDb
import com.eitangoze.data.Deck
import com.eitangoze.data.EntryKind
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
                    val ownMeanings = senses.map { it.jaLine }.toSet()
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
