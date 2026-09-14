package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.Deck
import com.eitangoze.data.Entry
import com.eitangoze.data.Repository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the decks are allowed to teach, and in what order.
 *
 * Both halves of this were reported from the app rather than caught here. The
 * first card of a first session was `be` 「ベリリウム」 — the element, because
 * Wiktionary leaves the ordinary senses of `be` unglossed and the leftover
 * gloss won. Behind it came `and`「アンド」, `we`「朕」, `he`「ヘリウム」,
 * `at`「アスタチン」: the whole head of the A1 deck was chemistry and archaic
 * pronouns, and it was the same head for everyone, every time, because new
 * words came out in strict frequency order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeachableTest {

    private lateinit var repo: Repository

    @Before
    fun setUp() {
        repo = Repository(ApplicationProvider.getApplicationContext())
        repo.enabledDecks = listOf("A1")
        repo.newPerDay = 60
    }

    @After
    fun tearDown() = repo.close()

    private fun a1(limit: Int = 120) =
        repo.content.deckEntries(Deck.byId("A1")!!, exclude = emptySet(), limit = limit)

    @Test
    fun `the words English is built from are not taught as vocabulary`() {
        val head = a1()
        assertTrue("the A1 deck came back empty", head.size >= 60)
        val structure = head.filter { it.isStructural }
        assertTrue(
            "taught as vocabulary: ${structure.map { "${it.lemma}(${it.pos.code})" }}",
            structure.isEmpty(),
        )
        assertTrue(head.all { it.rank > Entry.STRUCTURE_RANK })
    }

    /**
     * The reported bug, named. Each of these is a real fact about the spelling
     * and a wrong answer to "what does this word mean".
     */
    @Test
    fun `the leftover glosses of grammar words never reach a card`() {
        val queue = repo.buildQueue()
        assertTrue("no queue", queue.size >= 20)
        val glosses = queue.flatMap { card -> card.entry.ja + card.sense?.ja.orEmpty() }
        listOf("ベリリウム", "アンド", "朕", "ヘリウム", "アスタチン").forEach { junk ->
            assertTrue(
                "$junk was taught as a meaning",
                glosses.none { it.contains(junk) },
            )
        }
    }

    /**
     * `say` is a verb. `say` the noun (意見) is real English, and not what
     * anyone means by learning the word. Where a sense-tagged corpus attests one
     * part of speech of a spelling and not another, only the attested one is
     * taught.
     */
    @Test
    fun `a spelling is taught in the part of speech the corpus actually uses`() {
        val taught = a1(400).map { "${it.lemma}:${it.pos.code}" }.toSet()
        assertTrue("say was dropped entirely", "say:v" in taught)
        listOf("say:n", "go:n", "know:n", "think:n", "like:n").forEach {
            assertTrue("$it is still taught", it !in taught)
        }
    }

    /**
     * SemCor is 360,000 words, not the language. A word it never saw is a word
     * we know nothing about, so nothing is dropped on its silence alone —
     * otherwise `bike`, `cake` and `burger` would quietly leave the A1 deck.
     */
    @Test
    fun `a word the corpus never saw is still taught`() {
        val all = (1..6).flatMap { level ->
            repo.content.deckEntries(
                Deck.byId(listOf("A1", "A2", "B1", "B2", "C1", "C2")[level - 1])!!,
                exclude = emptySet(), limit = 3000,
            )
        }.map { it.lemma }.toSet()
        listOf("bike", "cake", "burger", "college", "butterfly").forEach {
            assertTrue("$it is no longer taught anywhere", it in all)
        }
    }

    /**
     * The dictionary holds every chemical symbol, letter name, unit and US
     * state code as a one- or two-letter headword: `cd` 「カドミウム」,
     * `mm` 「ミリメートル」, `sc` 「スカンジウム」, `el` 「エル」. `be` was the
     * one that got reported, but `am` 「アメリシウム」 and `cd` sat in A1 behind
     * it. None is a word anybody learns, and the published word lists say so by
     * not having them.
     */
    @Test
    fun `a two-letter spelling is taught only when a word list vouches for it`() {
        val everywhere = listOf("A1", "A2", "B1", "B2", "C1", "C2").flatMap { level ->
            repo.content.deckEntries(
                Deck.byId(level)!!, exclude = emptySet(), limit = 6000,
            )
        }.map { it.lemma }.toSet()

        listOf("am", "cd", "mm", "cm", "sc", "el", "en", "ba", "fe", "vt", "nc")
            .forEach { assertTrue("$it is still taught", it !in everywhere) }
        // The short words that are words stay: every one of these is in CEFR-J,
        // NGSL or NAWL.
        listOf("go", "up", "no", "hi", "ox", "pi", "pa").forEach {
            assertTrue("$it was dropped with the symbols", it in everywhere)
        }
    }

    /** Nothing was deleted: the dictionary still knows what it knew. */
    @Test
    fun `the dictionary still answers for the words the decks skip`() {
        val found = repo.search("be")
        assertTrue("be is not in the dictionary any more", found.any { it.lemma == "be" })
        val beryllium = found.firstOrNull { it.lemma == "be" && it.pos.code == "n" }
        assertNotNull("the element sense was deleted rather than left alone", beryllium)
        assertTrue(beryllium!!.ja.any { it.contains("ベリリウム") })
    }

    /**
     * Frequency decides roughly what comes next; chance decides exactly. Without
     * this every learner meets the same words in the same order for months, and
     * a session whose shape you already know is a session you skim.
     *
     * Two learners cannot be compared here — one Robolectric test has one study
     * database — so the comparison is against the order chance was supposed to
     * break: strict frequency. Twelve words drawn from a window of forty-eight
     * come out as the top twelve about once in 70 billion draws.
     */
    @Test
    fun `new words are not handed out in strict frequency order`() {
        repo.newPerDay = 20
        val taught = repo.buildQueue().map { it.entry.id }.distinct()
        assertTrue("empty session", taught.isNotEmpty())
        val strict = a1(limit = taught.size).map { it.id }
        assertTrue(
            "the session was exactly the top ${taught.size} words by frequency",
            taught.toSet() != strict.toSet(),
        )
        // Chance picks inside a window; it does not wander off into rare words.
        val ranks = repo.buildQueue().map { it.entry.rank }
        assertTrue("rank ${ranks.max()} is far past the frontier", ranks.max() < 1200)
    }

    @Test
    fun `the deck total counts what can be taught, not what is in the dictionary`() {
        val status = repo.deckStatuses().first { it.deck.id == "A1" }
        val counted = a1(limit = 5000).size
        assertEquals(counted, status.total)
        assertTrue("A1 shrank to nothing: $counted", counted in 1000..1674)
    }
}
