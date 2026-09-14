package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.AnswerMode
import com.eitangoze.data.CardKind
import com.eitangoze.data.Entry
import com.eitangoze.data.Repository
import com.eitangoze.srs.CardPhase
import com.eitangoze.srs.Rating
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
 * The study session end to end: pick a deck, get a queue, answer it, come back.
 *
 * Everything here runs against the real shipped database and a real (empty)
 * study database, because the parts worth testing are the seams — new cards
 * being created on first sight, siblings being kept apart, and an answer
 * actually moving a card's due date.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var repo: Repository

    @Before
    fun setUp() {
        repo = Repository(ApplicationProvider.getApplicationContext())
        repo.enabledDecks = listOf("A1")
        repo.newPerDay = 20
    }

    @After
    fun tearDown() = repo.close()

    @Test
    fun `a first session is made entirely of new cards, in teaching order`() {
        val queue = repo.buildQueue()
        assertTrue("empty queue", queue.size >= 15)
        queue.forEach { card ->
            assertEquals("A1", card.deck)
            assertEquals("A1", card.entry.cefr)
            assertTrue(card.instruction.isNotBlank())
            assertTrue(card.prompt.isNotBlank())
        }
        // The words introduced first are the ones taught first — but not in a
        // fixed order, and never the grammar words at the very top of the
        // frequency list, which the app does not teach at all.
        val ranks = queue.map { it.entry.rank }.distinct()
        assertTrue("taught structure: rank ${ranks.min()}", ranks.min() > Entry.STRUCTURE_RANK)
        assertTrue("started at rank ${ranks.first()}", ranks.first() < 600)
    }

    @Test
    fun `the daily limit counts cards, and holds across sessions`() {
        repo.newPerDay = 12
        val first = repo.buildQueue()
        assertEquals(12, first.size)
        // Nothing was answered, so the same cards are still waiting — and no
        // further new ones were created on top of them.
        val second = repo.buildQueue()
        assertEquals(12, second.size)
        assertEquals(first.map { it.key.value }.toSet(), second.map { it.key.value }.toSet())
    }

    @Test
    fun `two questions about the same word are not shown back to back`() {
        val queue = repo.buildQueue()
        for (i in 1 until queue.size) {
            assertTrue(
                "${queue[i].entry.lemma} repeats at $i",
                queue[i].entry.id != queue[i - 1].entry.id,
            )
        }
    }

    @Test
    fun `answering moves the card forward and records the answer`() {
        val card = repo.buildQueue().first()
        assertEquals(CardPhase.NEW, repo.stateOf(card).phase)

        repo.answer(card, Rating.GOOD, correct = true)

        val state = repo.stateOf(card)
        assertTrue("phase did not advance", state.phase != CardPhase.NEW)
        assertEquals(1, state.reps)
        assertTrue("not scheduled ahead", state.due > System.currentTimeMillis())
        assertEquals(1, repo.stats().answeredToday)
        assertEquals(1, repo.stats().correctToday)
    }

    @Test
    fun `the rating buttons show four increasing intervals`() {
        val card = repo.buildQueue().first()
        val delays = repo.previewDelays(card)
        assertEquals(4, delays.size)
        val ordered = Rating.entries.map { delays.getValue(it) }
        assertEquals(ordered.sorted(), ordered)
    }

    @Test
    fun `an exam date tightens the target as it approaches`() {
        val now = System.currentTimeMillis()
        repo.desiredRetention = 0.9
        assertEquals(0.9, repo.effectiveRetention(now), 1e-9)

        repo.examDate = now + 200L * Repository.DAY_MS
        assertEquals("far off, nothing changes", 0.9, repo.effectiveRetention(now), 1e-9)

        repo.examDate = now + 30L * Repository.DAY_MS
        val halfway = repo.effectiveRetention(now)
        assertTrue("$halfway", halfway > 0.9 && halfway < 0.97)

        repo.examDate = now + Repository.DAY_MS
        assertTrue(repo.effectiveRetention(now) > halfway)
    }

    @Test
    fun `every card kind that is switched on can actually be built`() {
        repo.enabledDecks = listOf("A2", "B1", "B2", "pv")
        repo.enabledKinds = CardKind.entries.toSet()
        repo.newPerDay = 120

        // A word gets four of its question types a day and the rest the next
        // time round, so the repertoire is a week's worth rather than a
        // session's: collocation and word-root cards sit behind recognition and
        // production in every word's list and would never appear in one sitting.
        val kinds = (0..6).flatMap { day ->
            repo.buildQueue(
                now = System.currentTimeMillis() + day * Repository.DAY_MS,
                limit = 400,
            ).map { it.kind }
        }.toSet()
        // Not every word has a collocation or a root, but across four decks the
        // whole repertoire should turn up.
        for (kind in CardKind.entries) {
            assertTrue("$kind was never generated", kind in kinds)
        }
    }

    @Test
    fun `a typed card carries an answer and a choice card carries options`() {
        repo.enabledDecks = listOf("B1")
        repo.newPerDay = 80
        repo.buildQueue(limit = 200).forEach { card ->
            when (card.mode) {
                AnswerMode.CHOICE -> {
                    // A context card offers only the word's own meanings, so a
                    // two-sense word legitimately gives a two-way choice.
                    val minimum = if (card.kind == CardKind.CONTEXT) 2 else 3
                    assertTrue("${card.kind} has too few options",
                        card.choices.size >= minimum)
                    assertTrue("${card.kind} has no right answer",
                        card.correctIndex in card.choices.indices)
                }
                AnswerMode.TYPE ->
                    assertTrue("${card.kind} accepts nothing", card.accepted.any { it.isNotBlank() })
                AnswerMode.SELF_CHECK -> {
                    assertNotNull(card.answerTitle)
                    assertTrue(card.answerTitle.isNotBlank())
                }
            }
        }
    }
}
