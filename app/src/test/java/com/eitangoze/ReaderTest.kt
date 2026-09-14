package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.Deck
import com.eitangoze.data.Knowledge
import com.eitangoze.data.Repository
import com.eitangoze.data.TextReport
import com.eitangoze.srs.Rating
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reading-coverage feature, against the shipped dictionary.
 *
 * The claim the screen makes is specific — "you can read 87% of this, and these
 * 12 words take you to 98%" — so these tests check the arithmetic behind it, and
 * that the words it points at are really the ones missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderTest {

    private lateinit var repo: Repository

    private val passage = """
        The ubiquitous smartphone has transformed how we read. Critics argue that
        constant access to information has undermined our capacity for sustained
        attention, and that the habit of skimming has displaced deep reading.
        Others contend that the evidence is far from conclusive.
    """.trimIndent()

    @Before
    fun setUp() {
        repo = Repository(ApplicationProvider.getApplicationContext())
        repo.enabledDecks = listOf("A1")
    }

    @After
    fun tearDown() = repo.close()

    @Test
    fun `a fresh learner starts at the grammar words and nothing else`() {
        val report = repo.analyze(passage)
        assertTrue("no words were counted", report.tokens > 40)
        // Grammar words count as read; every content word is still to learn.
        assertTrue("${report.coverage}", report.coverage in 0.2..0.6)
        assertTrue("nothing was recognised as a word", report.gaps.size > 20)
        // Every word is either in the dictionary or honestly reported as not.
        assertEquals(report.tokens, report.byKnowledge.values.sum())
    }

    @Test
    fun `the dictionary covers ordinary English`() {
        val report = repo.analyze(passage)
        val unlistedShare = report.unlisted.toDouble() / report.tokens
        assertTrue("too much is outside the dictionary: $unlistedShare", unlistedShare < 0.03)
    }

    @Test
    fun `coverage rises as words are learned`() {
        val before = repo.analyze(passage)
        studyEverythingIn(before, answers = 3)
        val after = repo.analyze(passage)
        assertTrue(
            "coverage did not rise: ${before.coverage} -> ${after.coverage}",
            after.coverage > before.coverage + 0.3,
        )
        assertTrue(after.gaps.size < before.gaps.size)
    }

    @Test
    fun `the gap count is the number of words that reach the threshold`() {
        val report = repo.analyze(passage)
        val covered = report.known +
            report.gaps.take(report.gapsToThreshold).sumOf { it.occurrences }
        assertTrue(
            "$covered of ${report.tokens} is short of 98%",
            covered.toDouble() / report.tokens >= TextReport.UNASSISTED ||
                report.gapsToThreshold == report.gaps.size,
        )
        // And one word fewer would not have been enough.
        if (report.gapsToThreshold > 0) {
            val oneLess = covered - report.gaps[report.gapsToThreshold - 1].occurrences
            assertTrue(oneLess.toDouble() / report.tokens < TextReport.UNASSISTED)
        }
    }

    @Test
    fun `the most frequent missing word is listed first`() {
        val report = repo.analyze("The reading of a reading about reading is reading.")
        val first = report.gaps.first()
        // `reading` is matched back to the entry it inflects from.
        assertEquals("read", first.entry?.lemma ?: first.surface)
        assertTrue(first.occurrences >= 4)
    }

    @Test
    fun `a phrase is read as one item, not as its parts`() {
        val report = repo.analyze("I look up to my sister.")
        val lemmas = report.gaps.mapNotNull { it.entry?.lemma }
        assertTrue("phrase not detected: $lemmas", "look up to" in lemmas)
        assertTrue("its parts were counted separately", "up" !in lemmas)
    }

    @Test
    fun `picking gap words puts them in the learner's own deck`() {
        val report = repo.analyze(passage)
        val wanted = report.gaps.take(5).mapNotNull { it.entry?.id }
        val added = repo.pick(wanted, source = "text")
        assertEquals(5, added)
        assertEquals("picking the same words twice adds nothing", 0,
            repo.pick(wanted, source = "text"))

        assertTrue("the deck was not switched on", Repository.MINE in repo.enabledDecks)
        val status = repo.deckStatuses().first { it.deck.id == Repository.MINE }
        assertEquals(5, status.total)

        // And they are actually studiable, in the order the analysis chose.
        repo.enabledDecks = listOf(Repository.MINE)
        val queue = repo.buildQueue()
        assertTrue(queue.isNotEmpty())
        assertTrue(queue.all { it.entry.id in wanted })
    }

    @Test
    fun `a word list is matched against the dictionary`() {
        val result = repo.importWordList(
            "abandon\nubiquitous\tよく見かける\nzzzznotaword\nconclusive"
        )
        val lemmas = result.matched.map { it.lemma }
        assertTrue("$lemmas", "abandon" in lemmas && "ubiquitous" in lemmas)
        assertTrue("zzzznotaword" in result.missing)
    }

    @Test
    fun `an estimated level is reported and is plausible`() {
        val easy = repo.analyze("I have a cat. The cat is big. I like my cat very much.")
        val hard = repo.analyze(passage)
        assertTrue("easy text came out as ${easy.estimatedLevel}", easy.estimatedLevel <= "B1")
        assertTrue("hard text came out as ${hard.estimatedLevel}",
            hard.estimatedLevel >= easy.estimatedLevel)
    }

    @Test
    fun `a declared level counts towards reading but is overruled by an answer`() {
        val plain = repo.analyze(passage)
        repo.baselineLevel = "B1"
        val declared = repo.analyze(passage)
        assertTrue(
            "declaring B1 changed nothing: ${plain.coverage} -> ${declared.coverage}",
            declared.coverage > plain.coverage + 0.2,
        )
        // Nothing was marked as studied by the declaration.
        assertEquals(0, repo.stats().cardsTotal)

        // An A1 word actually answered wrong stops counting as read.
        repo.enabledDecks = listOf("A1")
        // A spelling belongs to one entry when a text is measured — reading has
        // no parser to tell `time` the noun from `time` the verb — so the card
        // has to be one whose own spelling comes back to it, or the answer would
        // be recorded against a different entry than the text credits.
        val card = repo.buildQueue().first { card ->
            repo.content.surfaces(setOf(card.entry.lemma))[card.entry.lemma] == card.entry.id
        }
        repo.answer(card, Rating.AGAIN, correct = false)
        val text = "${card.entry.lemma} ${card.entry.lemma} ${card.entry.lemma}"
        val after = repo.analyze(text)
        assertTrue(
            "an answered-wrong word still counted as read",
            after.gaps.any { it.entry?.id == card.entry.id },
        )
    }

    /** Answer every card of the words in a report until they count as known. */
    private fun studyEverythingIn(report: TextReport, answers: Int) {
        repo.pick(report.gaps.mapNotNull { it.entry?.id }, source = "text")
        repo.enabledDecks = listOf(Repository.MINE)
        repo.newPerDay = 200
        repeat(answers) {
            val queue = repo.buildQueue(limit = 400)
            if (queue.isEmpty()) return
            queue.forEach { repo.answer(it, Rating.EASY, correct = true) }
        }
    }

    @Test
    fun `every deck listed on the home screen reports a size`() {
        val statuses = repo.deckStatuses()
        assertEquals(Deck.ALL.size, statuses.size)
        statuses.filter { !it.deck.custom }.forEach {
            assertTrue("${it.deck.id} is empty", it.total > 0)
        }
    }
}
